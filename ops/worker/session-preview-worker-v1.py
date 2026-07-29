#!/usr/bin/env python3
"""Authenticated WorkSession-owned private preview coordinator v1."""

from __future__ import annotations

import argparse
import hashlib
import hmac
import json
import os
import re
import signal
import socket
import socketserver
import tempfile
import threading
import time
import uuid
from datetime import datetime, timedelta, timezone
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any
from urllib.parse import parse_qs, urlparse

PROTOCOL = "session-preview/v1"
LEASE_SECONDS = 5 * 60
HARD_LIFETIME_SECONDS = 8 * 60 * 60
MAX_REQUEST_BYTES = 32 * 1024
PROJECT_ID = re.compile(r"^[a-z][a-z0-9-]{1,62}$")
SESSION_DATABASE_ID = re.compile(r"^[1-9][0-9]{0,18}$")
SHA256 = re.compile(r"^[0-9a-f]{64}$")
ACTIVE_STATES = {"READY", "RECONCILING"}
TERMINAL_STATES = {"STOPPED", "BLOCKED", "EXPIRED"}


def utc_now() -> datetime:
    return datetime.now(timezone.utc)


def timestamp(value: datetime) -> str:
    return value.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")


def parse_timestamp(value: Any, field: str) -> datetime:
    if not isinstance(value, str):
        raise ProtocolError(HTTPStatus.CONFLICT, "persisted_state_invalid", f"{field} is invalid")
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as error:
        raise ProtocolError(
            HTTPStatus.CONFLICT, "persisted_state_invalid", f"{field} is invalid"
        ) from error
    if parsed.tzinfo is None:
        raise ProtocolError(HTTPStatus.CONFLICT, "persisted_state_invalid", f"{field} is invalid")
    return parsed.astimezone(timezone.utc)


class ProtocolError(Exception):
    def __init__(self, status: int, code: str, message: str):
        super().__init__(message)
        self.status = status
        self.code = code


class PreviewForwardServer(socketserver.ThreadingTCPServer):
    allow_reuse_address = False
    daemon_threads = True

    def __init__(self, address: tuple[str, int], upstream_port: int):
        self.upstream_port = upstream_port
        super().__init__(address, PreviewForwardHandler)


class PreviewForwardHandler(socketserver.BaseRequestHandler):
    server: PreviewForwardServer

    def handle(self) -> None:
        try:
            upstream = socket.create_connection(
                ("127.0.0.1", self.server.upstream_port), timeout=10
            )
        except OSError:
            return
        with upstream:
            self.request.settimeout(120)
            upstream.settimeout(120)
            request_pump = threading.Thread(
                target=self._pump,
                args=(self.request, upstream),
                daemon=True,
            )
            request_pump.start()
            self._pump(upstream, self.request)
            request_pump.join(timeout=2)

    @staticmethod
    def _pump(source: socket.socket, destination: socket.socket) -> None:
        try:
            while chunk := source.recv(64 * 1024):
                destination.sendall(chunk)
        except (OSError, socket.timeout):
            pass
        finally:
            try:
                destination.shutdown(socket.SHUT_WR)
            except OSError:
                pass


class PreviewCoordinator:
    def __init__(
        self,
        root: Path,
        workspace_root: Path,
        worker_id: str,
        bind: str,
        ingress_start: int,
        ingress_end: int,
        test_mode: bool = False,
    ):
        self.root = root.resolve()
        self.workspace_root = workspace_root.resolve()
        self.worker_id = worker_id
        self.bind = bind
        self.ingress_start = ingress_start
        self.ingress_end = ingress_end
        self.test_mode = test_mode
        self.records_root = self.root / "previews"
        self.lock = threading.RLock()
        self.forwarders: dict[str, tuple[PreviewForwardServer, threading.Thread]] = {}
        self.closed = threading.Event()
        self._prepare_directory(self.root)
        self._prepare_directory(self.records_root)
        self._validate_bind()
        self._reconcile_persisted()
        self.sweeper = threading.Thread(target=self._sweep, daemon=True)
        self.sweeper.start()

    def health(self) -> dict[str, Any]:
        with self.lock:
            return {
                "protocolVersion": PROTOCOL,
                "workerId": self.worker_id,
                "healthy": True,
                "bind": self.bind,
                "ingressRange": [self.ingress_start, self.ingress_end],
                "activePreviews": len(self.forwarders),
                "publicSharing": False,
                "arbitraryUpstream": False,
                "serverTime": timestamp(utc_now()),
            }

    def activate(self, preview_id: str, request: dict[str, Any]) -> tuple[dict[str, Any], bool]:
        identity = self._preview_uuid(preview_id)
        ownership = self._activation_request(request)
        with self.lock:
            existing = self._read_optional(identity)
            if existing is not None:
                self._require_ownership(existing, ownership)
                if existing["lifecycleRevision"] == ownership["expectedRevision"] + 1:
                    if existing["state"] == "READY":
                        self._ensure_forwarder(existing)
                    return self._public(existing), False
                raise ProtocolError(
                    HTTPStatus.CONFLICT,
                    "stale_revision",
                    "persisted preview revision does not match activation",
                )

            allocation = self._allocation(ownership)
            ingress_port = self._available_ingress()
            now = utc_now()
            record = {
                "protocolVersion": PROTOCOL,
                "previewId": identity,
                "workSessionId": ownership["workSessionId"],
                "projectId": ownership["projectId"],
                "workerId": self.worker_id,
                "runtimeSessionId": allocation["sessionId"],
                "allocationIdentity": ownership["allocationIdentity"],
                "allocationFingerprint": ownership["allocationFingerprint"],
                "lifecycleRevision": ownership["expectedRevision"] + 1,
                "syntheticFixture": True,
                "state": "READY",
                "ingressPort": ingress_port,
                "upstreamPort": allocation["upstreamPort"],
                "previewPath": allocation["previewPath"],
                "localhostCompatible": allocation["localhostCompatible"],
                "leaseExpiresAt": timestamp(now + timedelta(seconds=LEASE_SECONDS)),
                "hardExpiresAt": timestamp(now + timedelta(seconds=HARD_LIFETIME_SECONDS)),
                "createdAt": timestamp(now),
                "updatedAt": timestamp(now),
            }
            self._start_forwarder(record)
            try:
                self._write(record)
            except Exception:
                self._stop_forwarder(identity)
                raise
            return self._public(record), True

    def inspect(self, preview_id: str, query: dict[str, list[str]]) -> dict[str, Any]:
        identity = self._preview_uuid(preview_id)
        ownership = self._query_ownership(query)
        with self.lock:
            record = self._read(identity)
            self._require_ownership(record, ownership)
            if record["state"] == "READY":
                if self._expired(record):
                    self._expire_record(record)
                else:
                    self._ensure_forwarder(record)
            return self._public(record)

    def renew(self, preview_id: str, request: dict[str, Any]) -> dict[str, Any]:
        identity = self._preview_uuid(preview_id)
        ownership = self._operation_request(request)
        with self.lock:
            record = self._read(identity)
            self._require_ownership(record, ownership)
            self._require_revision(record, ownership["expectedRevision"])
            if record["state"] != "READY":
                raise ProtocolError(
                    HTTPStatus.CONFLICT,
                    "preview_not_ready",
                    "only a ready preview can be renewed",
                )
            hard = parse_timestamp(record["hardExpiresAt"], "hardExpiresAt")
            now = utc_now()
            if now >= hard:
                self._expire_record(record)
                raise ProtocolError(
                    HTTPStatus.CONFLICT,
                    "hard_lifetime_expired",
                    "preview hard lifetime has expired",
                )
            record["leaseExpiresAt"] = timestamp(
                min(now + timedelta(seconds=LEASE_SECONDS), hard)
            )
            record["lifecycleRevision"] = ownership["expectedRevision"] + 1
            record["updatedAt"] = timestamp(now)
            self._ensure_forwarder(record)
            self._write(record)
            return self._public(record)

    def stop(self, preview_id: str, request: dict[str, Any]) -> dict[str, Any]:
        identity = self._preview_uuid(preview_id)
        ownership = self._operation_request(request)
        with self.lock:
            record = self._read(identity)
            self._require_ownership(record, ownership)
            if (
                record["state"] == "STOPPED"
                and record["lifecycleRevision"] == ownership["expectedRevision"] + 1
            ):
                return self._public(record)
            self._require_revision(record, ownership["expectedRevision"])
            self._stop_forwarder(identity)
            record["state"] = "STOPPED"
            record["lifecycleRevision"] = ownership["expectedRevision"] + 1
            record["updatedAt"] = timestamp(utc_now())
            self._write(record)
            return self._public(record)

    def delete_synthetic(self, preview_id: str, request: dict[str, Any]) -> dict[str, Any]:
        identity = self._preview_uuid(preview_id)
        ownership = self._operation_request(request)
        with self.lock:
            record = self._read(identity)
            self._require_ownership(record, ownership)
            self._require_revision(record, ownership["expectedRevision"])
            if not record["syntheticFixture"] or record["state"] not in TERMINAL_STATES:
                raise ProtocolError(
                    HTTPStatus.FORBIDDEN,
                    "synthetic_terminal_identity_required",
                    "exact terminal synthetic preview identity is required",
                )
            self._stop_forwarder(identity)
            record_path = self._record_path(identity)
            record_path.unlink()
            record_path.parent.rmdir()
            return {
                "protocolVersion": PROTOCOL,
                "previewId": identity,
                "workSessionId": record["workSessionId"],
                "deleted": True,
            }

    def close(self) -> None:
        self.closed.set()
        with self.lock:
            for identity in list(self.forwarders):
                self._stop_forwarder(identity)
        if hasattr(self, "sweeper"):
            self.sweeper.join(timeout=2)

    def _activation_request(self, request: dict[str, Any]) -> dict[str, Any]:
        expected = {
            "protocolVersion",
            "workSessionId",
            "projectId",
            "workerId",
            "runtimeSessionId",
            "allocationIdentity",
            "allocationFingerprint",
            "expectedRevision",
            "syntheticFixture",
        }
        if set(request) != expected:
            raise ProtocolError(
                HTTPStatus.BAD_REQUEST,
                "invalid_request",
                "activation fields are missing or unsupported",
            )
        ownership = self._common_request(request)
        ownership["runtimeSessionId"] = self._canonical_uuid(
            request["runtimeSessionId"], "invalid_runtime_session_id"
        )
        return ownership

    def _operation_request(self, request: dict[str, Any]) -> dict[str, Any]:
        expected = {
            "protocolVersion",
            "workSessionId",
            "projectId",
            "workerId",
            "allocationIdentity",
            "allocationFingerprint",
            "expectedRevision",
            "syntheticFixture",
        }
        if set(request) != expected:
            raise ProtocolError(
                HTTPStatus.BAD_REQUEST,
                "invalid_request",
                "operation fields are missing or unsupported",
            )
        return self._common_request(request)

    def _common_request(self, request: dict[str, Any]) -> dict[str, Any]:
        if request.get("protocolVersion") != PROTOCOL:
            raise ProtocolError(
                HTTPStatus.BAD_REQUEST, "protocol_mismatch", "protocol version is invalid"
            )
        session_id = str(request.get("workSessionId", ""))
        if not SESSION_DATABASE_ID.fullmatch(session_id):
            self._canonical_uuid(session_id, "invalid_work_session_id")
        project_id = request.get("projectId")
        if not isinstance(project_id, str) or not PROJECT_ID.fullmatch(project_id):
            raise ProtocolError(
                HTTPStatus.BAD_REQUEST, "invalid_project_id", "project identity is invalid"
            )
        if request.get("workerId") != self.worker_id:
            raise ProtocolError(
                HTTPStatus.CONFLICT, "worker_ownership_conflict", "worker identity is foreign"
            )
        allocation_identity = request.get("allocationIdentity")
        if (
            not isinstance(allocation_identity, str)
            or not re.fullmatch(r"ws-[0-9a-f]{32}", allocation_identity)
        ):
            raise ProtocolError(
                HTTPStatus.BAD_REQUEST,
                "invalid_allocation_identity",
                "allocation identity is invalid",
            )
        fingerprint = request.get("allocationFingerprint")
        if not isinstance(fingerprint, str) or not SHA256.fullmatch(fingerprint):
            raise ProtocolError(
                HTTPStatus.BAD_REQUEST,
                "invalid_allocation_fingerprint",
                "allocation fingerprint is invalid",
            )
        revision = request.get("expectedRevision")
        if not isinstance(revision, int) or isinstance(revision, bool) or revision < 1:
            raise ProtocolError(
                HTTPStatus.BAD_REQUEST, "invalid_revision", "expected revision is invalid"
            )
        if request.get("syntheticFixture") is not True:
            raise ProtocolError(
                HTTPStatus.FORBIDDEN,
                "synthetic_identity_required",
                "Phase 6 accepts exact synthetic fixtures only",
            )
        return {
            "workSessionId": session_id,
            "projectId": project_id,
            "workerId": self.worker_id,
            "allocationIdentity": allocation_identity,
            "allocationFingerprint": fingerprint,
            "expectedRevision": revision,
            "syntheticFixture": True,
        }

    def _query_ownership(self, query: dict[str, list[str]]) -> dict[str, Any]:
        expected = {
            "workSessionId",
            "projectId",
            "workerId",
            "allocationIdentity",
            "allocationFingerprint",
        }
        if set(query) != expected or any(len(values) != 1 for values in query.values()):
            raise ProtocolError(
                HTTPStatus.BAD_REQUEST,
                "invalid_query",
                "exact preview ownership query is required",
            )
        request = {key: values[0] for key, values in query.items()}
        request.update(
            {
                "protocolVersion": PROTOCOL,
                "expectedRevision": 1,
                "syntheticFixture": True,
            }
        )
        return self._common_request(request)

    def _allocation(self, ownership: dict[str, Any]) -> dict[str, Any]:
        runtime_session_id = ownership["runtimeSessionId"]
        session_root = self.workspace_root / "sessions" / runtime_session_id
        record_path = session_root / "runtime-allocation-v1.json"
        record = self._safe_json(record_path, session_root, "runtime allocation")
        digest = hashlib.sha256(record_path.read_bytes()).hexdigest()
        if digest != ownership["allocationFingerprint"]:
            raise ProtocolError(
                HTTPStatus.CONFLICT,
                "allocation_fingerprint_conflict",
                "persisted allocation fingerprint does not match",
            )
        runtime_id = f"ws-{runtime_session_id.replace('-', '')}"
        if (
            record.get("schemaVersion") != 1
            or record.get("state") != "allocated"
            or record.get("sessionId") != runtime_session_id
            or record.get("projectId") != ownership["projectId"]
            or record.get("runtimeId") != runtime_id
            or ownership["allocationIdentity"] != runtime_id
            or record.get("worktreePath") is None
            or record.get("manifestRelativePath") is None
        ):
            raise ProtocolError(
                HTTPStatus.CONFLICT,
                "allocation_ownership_conflict",
                "persisted allocation ownership does not match preview request",
            )
        worktree = Path(record["worktreePath"])
        try:
            worktree.resolve().relative_to(session_root.resolve())
        except (ValueError, OSError) as error:
            raise ProtocolError(
                HTTPStatus.CONFLICT,
                "allocation_ownership_conflict",
                "persisted worktree escapes the WorkSession",
            ) from error
        manifest = self._safe_json(
            worktree / record["manifestRelativePath"], worktree, "runtime manifest"
        )
        preview = manifest.get("preview")
        if not isinstance(preview, dict) or preview.get("publish") != "private":
            raise ProtocolError(
                HTTPStatus.CONFLICT,
                "manifest_preview_invalid",
                "manifest does not declare a private preview",
            )
        port_name = preview.get("internalPort")
        preview_path = preview.get("path")
        localhost_compatible = preview.get("localhostCompatibilityRequired", False)
        if (
            not isinstance(port_name, str)
            or not isinstance(preview_path, str)
            or not preview_path.startswith("/")
            or len(preview_path) > 300
            or not isinstance(localhost_compatible, bool)
        ):
            raise ProtocolError(
                HTTPStatus.CONFLICT,
                "manifest_preview_invalid",
                "manifest preview declaration is invalid",
            )
        ports = record.get("allocatedPorts")
        matches = [
            item
            for item in ports
            if isinstance(ports, list)
            and isinstance(item, dict)
            and item.get("name") == port_name
            and item.get("protocol") == "http"
            and item.get("bindAddress") == "127.0.0.1"
            and isinstance(item.get("loopbackPort"), int)
        ] if isinstance(ports, list) else []
        if len(matches) != 1:
            raise ProtocolError(
                HTTPStatus.CONFLICT,
                "allocation_preview_ambiguous",
                "allocation does not contain one exact HTTP preview port",
            )
        upstream_port = matches[0]["loopbackPort"]
        if not 1024 <= upstream_port <= 65535:
            raise ProtocolError(
                HTTPStatus.CONFLICT,
                "allocation_preview_invalid",
                "allocation preview port is outside policy",
            )
        try:
            with socket.create_connection(("127.0.0.1", upstream_port), timeout=3):
                pass
        except OSError as error:
            raise ProtocolError(
                HTTPStatus.CONFLICT,
                "preview_upstream_unavailable",
                "allocation-derived preview is not listening",
            ) from error
        return {
            "sessionId": runtime_session_id,
            "upstreamPort": upstream_port,
            "previewPath": preview_path,
            "localhostCompatible": localhost_compatible,
        }

    def _safe_json(self, path: Path, root: Path, description: str) -> dict[str, Any]:
        try:
            resolved_root = root.resolve(strict=True)
            resolved = path.resolve(strict=True)
            resolved.relative_to(resolved_root)
            if path.is_symlink() or not path.is_file():
                raise OSError()
            parsed = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, ValueError, json.JSONDecodeError) as error:
            raise ProtocolError(
                HTTPStatus.CONFLICT,
                "persisted_state_invalid",
                f"{description} is missing, unsafe or invalid",
            ) from error
        if not isinstance(parsed, dict):
            raise ProtocolError(
                HTTPStatus.CONFLICT,
                "persisted_state_invalid",
                f"{description} is invalid",
            )
        return parsed

    def _require_ownership(
        self, record: dict[str, Any], ownership: dict[str, Any]
    ) -> None:
        fields = (
            "workSessionId",
            "projectId",
            "workerId",
            "allocationIdentity",
            "allocationFingerprint",
            "syntheticFixture",
        )
        if any(record.get(field) != ownership.get(field) for field in fields):
            raise ProtocolError(
                HTTPStatus.CONFLICT,
                "preview_ownership_conflict",
                "preview ownership does not match persisted projection",
            )

    def _require_revision(self, record: dict[str, Any], expected: int) -> None:
        if record["lifecycleRevision"] != expected:
            raise ProtocolError(
                HTTPStatus.CONFLICT,
                "stale_revision",
                "persisted preview revision does not match expected revision",
            )

    def _available_ingress(self) -> int:
        used = {
            record["ingressPort"]
            for record in self._records()
            if record["state"] in ACTIVE_STATES
        }
        for port in range(self.ingress_start, self.ingress_end + 1):
            if port in used:
                continue
            probe = socket.socket()
            try:
                probe.bind((self.bind, port))
            except OSError:
                continue
            finally:
                probe.close()
            return port
        raise ProtocolError(
            HTTPStatus.CONFLICT,
            "preview_capacity_exhausted",
            "no private preview ingress port is available",
        )

    def _ensure_forwarder(self, record: dict[str, Any]) -> None:
        identity = record["previewId"]
        running = self.forwarders.get(identity)
        if running is not None:
            if (
                running[0].server_address == (self.bind, record["ingressPort"])
                and running[0].upstream_port == record["upstreamPort"]
            ):
                return
            raise ProtocolError(
                HTTPStatus.CONFLICT,
                "listener_ownership_conflict",
                "running preview listener has conflicting ownership",
            )
        self._start_forwarder(record)

    def _start_forwarder(self, record: dict[str, Any]) -> None:
        identity = record["previewId"]
        try:
            server = PreviewForwardServer(
                (self.bind, record["ingressPort"]), record["upstreamPort"]
            )
        except OSError as error:
            raise ProtocolError(
                HTTPStatus.CONFLICT,
                "ingress_listener_conflict",
                "private ingress port cannot be bound",
            ) from error
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        self.forwarders[identity] = (server, thread)
        thread.start()

    def _stop_forwarder(self, identity: str) -> None:
        running = self.forwarders.pop(identity, None)
        if running is None:
            return
        server, thread = running
        server.shutdown()
        server.server_close()
        thread.join(timeout=2)

    def _public(self, record: dict[str, Any]) -> dict[str, Any]:
        url = f"http://{self.bind}:{record['ingressPort']}{record['previewPath']}"
        return {
            "protocolVersion": PROTOCOL,
            "previewId": record["previewId"],
            "workSessionId": record["workSessionId"],
            "projectId": record["projectId"],
            "workerId": record["workerId"],
            "allocationIdentity": record["allocationIdentity"],
            "allocationFingerprint": record["allocationFingerprint"],
            "lifecycleRevision": record["lifecycleRevision"],
            "state": record["state"],
            "privateUrl": url if record["state"] == "READY" else None,
            "leaseExpiresAt": record["leaseExpiresAt"],
            "hardExpiresAt": record["hardExpiresAt"],
            "localhostCompatible": record["localhostCompatible"],
            "tunnel": {
                "sshDestination": "codex-worker",
                "remoteHost": self.bind,
                "remotePort": record["ingressPort"],
                "path": record["previewPath"],
                "credentialIncluded": False,
                "runtimePortExposed": False,
            } if record["localhostCompatible"] and record["state"] == "READY" else None,
            "syntheticFixture": record["syntheticFixture"],
        }

    def _expired(self, record: dict[str, Any]) -> bool:
        now = utc_now()
        return (
            now >= parse_timestamp(record["leaseExpiresAt"], "leaseExpiresAt")
            or now >= parse_timestamp(record["hardExpiresAt"], "hardExpiresAt")
        )

    def _expire_record(self, record: dict[str, Any]) -> None:
        self._stop_forwarder(record["previewId"])
        record["state"] = "EXPIRED"
        record["updatedAt"] = timestamp(utc_now())
        self._write(record)

    def _sweep(self) -> None:
        while not self.closed.wait(1):
            with self.lock:
                for record in self._records():
                    if record["state"] in ACTIVE_STATES and self._expired(record):
                        self._expire_record(record)

    def _reconcile_persisted(self) -> None:
        with self.lock:
            for record in self._records():
                if record["state"] not in ACTIVE_STATES:
                    continue
                if self._expired(record):
                    self._expire_record(record)
                    continue
                ownership = {
                    key: record[key]
                    for key in (
                        "workSessionId",
                        "projectId",
                        "workerId",
                        "allocationIdentity",
                        "allocationFingerprint",
                        "syntheticFixture",
                    )
                }
                ownership["runtimeSessionId"] = record["runtimeSessionId"]
                ownership["expectedRevision"] = record["lifecycleRevision"]
                try:
                    allocation = self._allocation(ownership)
                    if (
                        allocation["upstreamPort"] != record["upstreamPort"]
                        or allocation["previewPath"] != record["previewPath"]
                    ):
                        raise ProtocolError(
                            HTTPStatus.CONFLICT,
                            "allocation_ownership_conflict",
                            "persisted preview projection no longer matches allocation",
                        )
                    self._start_forwarder(record)
                    record["state"] = "READY"
                except ProtocolError:
                    record["state"] = "BLOCKED"
                record["updatedAt"] = timestamp(utc_now())
                self._write(record)

    def _records(self) -> list[dict[str, Any]]:
        records: list[dict[str, Any]] = []
        for path in sorted(self.records_root.glob("*/record.json")):
            records.append(self._read_json(path))
        return records

    def _read_optional(self, identity: str) -> dict[str, Any] | None:
        path = self._record_path(identity)
        return self._read_json(path) if path.exists() else None

    def _read(self, identity: str) -> dict[str, Any]:
        record = self._read_optional(identity)
        if record is None:
            raise ProtocolError(HTTPStatus.NOT_FOUND, "preview_not_found", "preview does not exist")
        return record

    def _read_json(self, path: Path) -> dict[str, Any]:
        try:
            parsed = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as error:
            raise ProtocolError(
                HTTPStatus.CONFLICT,
                "persisted_state_invalid",
                "persisted preview record is unreadable",
            ) from error
        required = {
            "protocolVersion",
            "previewId",
            "workSessionId",
            "projectId",
            "workerId",
            "runtimeSessionId",
            "allocationIdentity",
            "allocationFingerprint",
            "lifecycleRevision",
            "syntheticFixture",
            "state",
            "ingressPort",
            "upstreamPort",
            "previewPath",
            "localhostCompatible",
            "leaseExpiresAt",
            "hardExpiresAt",
            "createdAt",
            "updatedAt",
        }
        if (
            not isinstance(parsed, dict)
            or set(parsed) != required
            or parsed.get("protocolVersion") != PROTOCOL
        ):
            raise ProtocolError(
                HTTPStatus.CONFLICT,
                "persisted_state_invalid",
                "persisted preview record schema is unsupported",
            )
        return parsed

    def _write(self, record: dict[str, Any]) -> None:
        directory = self.records_root / record["previewId"]
        self._prepare_directory(directory)
        target = directory / "record.json"
        descriptor, temporary = tempfile.mkstemp(prefix=".record-", dir=directory)
        try:
            with os.fdopen(descriptor, "w", encoding="utf-8") as handle:
                json.dump(record, handle, sort_keys=True, separators=(",", ":"))
                handle.flush()
                os.fsync(handle.fileno())
            os.chmod(temporary, 0o600)
            os.replace(temporary, target)
        finally:
            if os.path.exists(temporary):
                os.unlink(temporary)

    def _record_path(self, identity: str) -> Path:
        path = self.records_root / identity / "record.json"
        try:
            path.resolve().relative_to(self.root)
        except ValueError as error:
            raise ProtocolError(
                HTTPStatus.BAD_REQUEST, "invalid_preview_id", "preview identity escapes root"
            ) from error
        return path

    def _prepare_directory(self, path: Path) -> None:
        path.mkdir(mode=0o700, parents=True, exist_ok=True)
        if path.is_symlink() or not path.is_dir():
            raise RuntimeError(f"preview state directory is unsafe: {path}")
        os.chmod(path, 0o700)

    def _validate_bind(self) -> None:
        if self.test_mode and self.bind == "127.0.0.1":
            return
        parts = self.bind.split(".")
        if len(parts) != 4:
            raise RuntimeError("preview bind must be an IPv4 tailnet address")
        try:
            octets = [int(part) for part in parts]
        except ValueError as error:
            raise RuntimeError("preview bind must be an IPv4 tailnet address") from error
        if (
            any(value < 0 or value > 255 for value in octets)
            or octets[0] != 100
            or not 64 <= octets[1] <= 127
        ):
            raise RuntimeError("preview bind must be inside 100.64.0.0/10")

    def _preview_uuid(self, value: str) -> str:
        return self._canonical_uuid(value, "invalid_preview_id")

    def _canonical_uuid(self, value: Any, code: str) -> str:
        try:
            parsed = uuid.UUID(value)
        except (ValueError, TypeError, AttributeError) as error:
            raise ProtocolError(HTTPStatus.BAD_REQUEST, code, "identity must be a UUID") from error
        if str(parsed) != value:
            raise ProtocolError(HTTPStatus.BAD_REQUEST, code, "identity must be canonical")
        return value


class PreviewControlServer(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(
        self,
        address: tuple[str, int],
        coordinator: PreviewCoordinator,
        token: str,
    ):
        self.coordinator = coordinator
        self.token = token
        super().__init__(address, PreviewControlHandler)


class PreviewControlHandler(BaseHTTPRequestHandler):
    server: PreviewControlServer

    def log_message(self, message: str, *args: Any) -> None:
        print(
            json.dumps(
                {
                    "at": timestamp(utc_now()),
                    "remote": self.client_address[0],
                    "message": message % args,
                }
            ),
            flush=True,
        )

    def do_GET(self) -> None:
        try:
            self._authenticate()
            parsed = urlparse(self.path)
            if parsed.path == "/v1/health":
                self._json(HTTPStatus.OK, self.server.coordinator.health())
                return
            preview_id, operation = self._route(parsed.path)
            if operation != "inspect":
                raise ProtocolError(HTTPStatus.NOT_FOUND, "not_found", "route does not exist")
            self._json(
                HTTPStatus.OK,
                self.server.coordinator.inspect(preview_id, parse_qs(parsed.query)),
            )
        except ProtocolError as error:
            self._error(error)

    def do_POST(self) -> None:
        try:
            self._authenticate()
            preview_id, operation = self._route(urlparse(self.path).path)
            body = self._body()
            if operation == "activate":
                result, created = self.server.coordinator.activate(preview_id, body)
                self._json(HTTPStatus.CREATED if created else HTTPStatus.OK, result)
                return
            if operation == "renew":
                self._json(HTTPStatus.OK, self.server.coordinator.renew(preview_id, body))
                return
            if operation == "stop":
                self._json(HTTPStatus.OK, self.server.coordinator.stop(preview_id, body))
                return
            raise ProtocolError(HTTPStatus.NOT_FOUND, "not_found", "route does not exist")
        except ProtocolError as error:
            self._error(error)

    def do_DELETE(self) -> None:
        try:
            self._authenticate()
            preview_id, operation = self._route(urlparse(self.path).path)
            if operation != "fixture":
                raise ProtocolError(HTTPStatus.NOT_FOUND, "not_found", "route does not exist")
            self._json(
                HTTPStatus.OK,
                self.server.coordinator.delete_synthetic(preview_id, self._body()),
            )
        except ProtocolError as error:
            self._error(error)

    def _route(self, path: str) -> tuple[str, str]:
        parts = path.strip("/").split("/")
        if len(parts) == 3 and parts[0:2] == ["v1", "previews"]:
            return parts[2], "inspect"
        if len(parts) == 4 and parts[0:2] == ["v1", "previews"]:
            return parts[2], parts[3]
        raise ProtocolError(HTTPStatus.NOT_FOUND, "not_found", "route does not exist")

    def _body(self) -> dict[str, Any]:
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError as error:
            raise ProtocolError(
                HTTPStatus.BAD_REQUEST, "invalid_length", "Content-Length is invalid"
            ) from error
        if length < 2 or length > MAX_REQUEST_BYTES:
            raise ProtocolError(
                HTTPStatus.BAD_REQUEST, "invalid_length", "JSON request size is invalid"
            )
        try:
            parsed = json.loads(self.rfile.read(length))
        except json.JSONDecodeError as error:
            raise ProtocolError(
                HTTPStatus.BAD_REQUEST, "invalid_json", "request is not valid JSON"
            ) from error
        if not isinstance(parsed, dict):
            raise ProtocolError(
                HTTPStatus.BAD_REQUEST, "invalid_json", "request must be a JSON object"
            )
        return parsed

    def _authenticate(self) -> None:
        expected = f"Bearer {self.server.token}"
        if not hmac.compare_digest(self.headers.get("Authorization", ""), expected):
            raise ProtocolError(
                HTTPStatus.UNAUTHORIZED, "unauthorized", "valid worker credential required"
            )

    def _error(self, error: ProtocolError) -> None:
        self._json(error.status, {"error": error.code, "message": str(error)})

    def _json(self, status: int, value: dict[str, Any]) -> None:
        encoded = json.dumps(value, sort_keys=True, separators=(",", ":")).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(encoded)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(encoded)


def read_token(path: Path, test_mode: bool) -> str:
    stat = path.stat()
    if not test_mode and (stat.st_uid != 0 or stat.st_mode & 0o037):
        raise RuntimeError("token file must be root-owned and otherwise private")
    token = path.read_text(encoding="utf-8").strip()
    if len(token) < 32:
        raise RuntimeError("token must contain at least 32 characters")
    return token


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--bind", required=True)
    parser.add_argument("--port", type=int, required=True)
    parser.add_argument("--worker-id", required=True)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--workspace-root", type=Path, required=True)
    parser.add_argument("--token-file", type=Path, required=True)
    parser.add_argument("--ingress-start", type=int, default=19000)
    parser.add_argument("--ingress-end", type=int, default=19031)
    parser.add_argument("--test-mode", action="store_true")
    args = parser.parse_args()
    if (
        not 1024 <= args.port <= 65535
        or not 1024 <= args.ingress_start <= args.ingress_end <= 65535
        or args.port >= args.ingress_start and args.port <= args.ingress_end
    ):
        raise SystemExit("control and ingress ports are outside policy")
    if not re.fullmatch(r"[A-Za-z0-9._-]{1,80}", args.worker_id):
        raise SystemExit("worker id is invalid")

    coordinator = PreviewCoordinator(
        args.root,
        args.workspace_root,
        args.worker_id,
        args.bind,
        args.ingress_start,
        args.ingress_end,
        args.test_mode,
    )
    server = PreviewControlServer(
        (args.bind, args.port), coordinator, read_token(args.token_file, args.test_mode)
    )

    def shutdown(_signum: int, _frame: Any) -> None:
        threading.Thread(target=server.shutdown, daemon=True).start()

    signal.signal(signal.SIGTERM, shutdown)
    signal.signal(signal.SIGINT, shutdown)
    try:
        server.serve_forever(poll_interval=0.25)
    finally:
        server.server_close()
        coordinator.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
