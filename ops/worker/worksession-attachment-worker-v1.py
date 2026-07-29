#!/usr/bin/env python3
"""Private WorkSession-scoped attachment storage protocol v1."""

from __future__ import annotations

import argparse
import hashlib
import hmac
import json
import os
import re
import signal
import tempfile
import threading
import uuid
from datetime import datetime, timezone
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any, BinaryIO
from urllib.parse import urlparse

PROTOCOL = "worksession-attachment/v1"
MAX_FILE_BYTES = 16 * 1024 * 1024
MAX_SESSION_BYTES = 256 * 1024 * 1024
SOURCES = {"OPERATOR_UPLOAD", "BROWSER_SCREENSHOT", "BROWSER_TRACE", "REPORT"}
KINDS = {"IMAGE", "TRACE", "REPORT", "FILE"}
RETENTIONS = {"TRANSIENT", "SESSION", "EVIDENCE"}
CONTENT_TYPES = {
    "image/png",
    "image/jpeg",
    "image/webp",
    "text/plain",
    "application/json",
    "application/pdf",
    "application/zip",
}
SHA256 = re.compile(r"^[0-9a-f]{64}$")


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


class ProtocolError(Exception):
    def __init__(self, status: int, code: str, message: str):
        super().__init__(message)
        self.status = status
        self.code = code


class AttachmentStore:
    def __init__(
        self,
        root: Path,
        worker_id: str,
        max_file_bytes: int = MAX_FILE_BYTES,
        max_session_bytes: int = MAX_SESSION_BYTES,
    ):
        self.root = root.resolve()
        self.worker_id = worker_id
        self.max_file_bytes = max_file_bytes
        self.max_session_bytes = max_session_bytes
        self.lock = threading.RLock()
        self.incoming = self.root / ".incoming"
        self.sessions = self.root / "work-sessions"
        self._prepare_directory(self.root, 0o700)
        self._prepare_directory(self.incoming, 0o700)
        self._prepare_directory(self.sessions, 0o700)

    def health(self) -> dict[str, Any]:
        return {
            "protocolVersion": PROTOCOL,
            "workerId": self.worker_id,
            "healthy": True,
            "maxFileBytes": self.max_file_bytes,
            "maxSessionBytes": self.max_session_bytes,
            "contentTypes": sorted(CONTENT_TYPES),
            "serverTime": utc_now(),
        }

    def put(
        self,
        session_id: str,
        attachment_id: str,
        metadata: dict[str, Any],
        stream: BinaryIO,
        length: int,
    ) -> tuple[dict[str, Any], bool]:
        session_identity = self._session_identity(session_id)
        attachment_uuid = self._uuid(attachment_id, "invalid_attachment_id")
        self._validate_metadata(metadata, length)
        storage_identity = f"work-sessions/{session_identity}/{attachment_uuid}/content"
        attachment_dir = self.sessions / session_identity / attachment_uuid
        content_path = attachment_dir / "content"
        metadata_path = attachment_dir / "metadata.json"
        self._inside(content_path)

        with self.lock:
            if metadata_path.exists() or content_path.exists():
                return self._existing(
                    session_identity,
                    attachment_uuid,
                    metadata,
                    length,
                    content_path,
                    metadata_path,
                ), False

            retained = self._session_bytes(session_identity)
            if length > self.max_session_bytes - retained:
                raise ProtocolError(
                    HTTPStatus.REQUEST_ENTITY_TOO_LARGE,
                    "session_quota_exceeded",
                    "WorkSession retained attachment quota would be exceeded",
                )

            temporary_path: Path | None = None
            try:
                fd, temporary = tempfile.mkstemp(prefix=f"{attachment_uuid}-", dir=self.incoming)
                temporary_path = Path(temporary)
                digest = hashlib.sha256()
                remaining = length
                first = bytearray()
                with os.fdopen(fd, "wb") as handle:
                    while remaining:
                        chunk = stream.read(min(64 * 1024, remaining))
                        if not chunk:
                            raise ProtocolError(
                                HTTPStatus.BAD_REQUEST,
                                "truncated_content",
                                "request ended before Content-Length bytes were received",
                            )
                        if len(first) < 8192:
                            first.extend(chunk[: 8192 - len(first)])
                        digest.update(chunk)
                        handle.write(chunk)
                        remaining -= len(chunk)
                    handle.flush()
                    os.fsync(handle.fileno())
                os.chmod(temporary_path, 0o600)
                actual_sha256 = digest.hexdigest()
                if actual_sha256 != metadata["sha256"]:
                    raise ProtocolError(
                        HTTPStatus.UNPROCESSABLE_ENTITY,
                        "integrity_mismatch",
                        "content does not match the declared SHA-256 identity",
                    )
                self._validate_content(metadata["contentType"], bytes(first), temporary_path)

                self._prepare_directory(attachment_dir.parent, 0o700)
                self._prepare_directory(attachment_dir, 0o700)
                os.replace(temporary_path, content_path)
                temporary_path = None
                stored = {
                    "protocolVersion": PROTOCOL,
                    "workerId": self.worker_id,
                    "sessionId": session_identity,
                    "attachmentId": attachment_uuid,
                    "storageIdentity": storage_identity,
                    "source": metadata["source"],
                    "kind": metadata["kind"],
                    "contentType": metadata["contentType"],
                    "sizeBytes": length,
                    "retentionClass": metadata["retentionClass"],
                    "sha256": actual_sha256,
                    "syntheticFixture": metadata["syntheticFixture"],
                    "createdAt": metadata["createdAt"],
                    "storedAt": utc_now(),
                }
                self._atomic_json(metadata_path, stored)
                self._fsync_directory(attachment_dir)
                return self._public(stored), True
            except Exception:
                if temporary_path is not None:
                    temporary_path.unlink(missing_ok=True)
                if content_path.exists() and not metadata_path.exists():
                    content_path.unlink()
                self._remove_empty_owned(attachment_dir)
                raise

    def metadata(self, session_id: str, attachment_id: str) -> dict[str, Any]:
        session_identity = self._session_identity(session_id)
        attachment_uuid = self._uuid(attachment_id, "invalid_attachment_id")
        with self.lock:
            stored, _, _ = self._load(session_identity, attachment_uuid)
            return self._public(stored)

    def content(self, session_id: str, attachment_id: str) -> tuple[dict[str, Any], Path]:
        session_identity = self._session_identity(session_id)
        attachment_uuid = self._uuid(attachment_id, "invalid_attachment_id")
        with self.lock:
            stored, content_path, _ = self._load(session_identity, attachment_uuid)
            self._verify_content(stored, content_path)
            return self._public(stored), content_path

    def delete_synthetic(self, session_id: str, attachment_id: str) -> dict[str, Any]:
        session_identity = self._session_identity(session_id)
        attachment_uuid = self._uuid(attachment_id, "invalid_attachment_id")
        with self.lock:
            stored, content_path, metadata_path = self._load(session_identity, attachment_uuid)
            if stored.get("syntheticFixture") is not True:
                raise ProtocolError(
                    HTTPStatus.FORBIDDEN,
                    "retained_attachment",
                    "only exact synthetic fixtures can be deleted by this protocol",
                )
            self._verify_content(stored, content_path)
            content_path.unlink()
            metadata_path.unlink()
            attachment_dir = metadata_path.parent
            session_dir = attachment_dir.parent
            self._remove_empty_owned(attachment_dir)
            self._remove_empty_owned(session_dir)
            return {
                "protocolVersion": PROTOCOL,
                "workerId": self.worker_id,
                "sessionId": session_identity,
                "attachmentId": attachment_uuid,
                "deleted": True,
                "sha256": stored["sha256"],
            }

    def _existing(
        self,
        session_id: str,
        attachment_id: str,
        requested: dict[str, Any],
        length: int,
        content_path: Path,
        metadata_path: Path,
    ) -> dict[str, Any]:
        if not metadata_path.is_file() or not content_path.is_file():
            raise ProtocolError(
                HTTPStatus.CONFLICT,
                "incomplete_existing_attachment",
                "attachment identity has incomplete retained state",
            )
        stored = self._read_json(metadata_path)
        expected = {
            "sessionId": session_id,
            "attachmentId": attachment_id,
            "source": requested["source"],
            "kind": requested["kind"],
            "contentType": requested["contentType"],
            "sizeBytes": length,
            "retentionClass": requested["retentionClass"],
            "sha256": requested["sha256"],
            "syntheticFixture": requested["syntheticFixture"],
        }
        if any(stored.get(key) != value for key, value in expected.items()):
            raise ProtocolError(
                HTTPStatus.CONFLICT,
                "attachment_identity_conflict",
                "attachment identity already owns different content or metadata",
            )
        self._verify_content(stored, content_path)
        return self._public(stored)

    def _load(self, session_id: str, attachment_id: str) -> tuple[dict[str, Any], Path, Path]:
        attachment_dir = self.sessions / session_id / attachment_id
        content_path = attachment_dir / "content"
        metadata_path = attachment_dir / "metadata.json"
        self._inside(content_path)
        if not content_path.is_file() or not metadata_path.is_file():
            raise ProtocolError(
                HTTPStatus.NOT_FOUND,
                "attachment_not_found",
                "attachment does not exist for this WorkSession",
            )
        stored = self._read_json(metadata_path)
        if stored.get("sessionId") != session_id or stored.get("attachmentId") != attachment_id:
            raise ProtocolError(
                HTTPStatus.CONFLICT,
                "ownership_state_conflict",
                "retained attachment ownership metadata does not match its identity",
            )
        return stored, content_path, metadata_path

    def _validate_metadata(self, metadata: dict[str, Any], length: int) -> None:
        if set(metadata) != {
            "source", "kind", "contentType", "retentionClass", "sha256",
            "syntheticFixture", "createdAt",
        }:
            raise ProtocolError(
                HTTPStatus.BAD_REQUEST,
                "invalid_metadata",
                "attachment metadata headers are missing or ambiguous",
            )
        if metadata["source"] not in SOURCES or metadata["kind"] not in KINDS:
            raise ProtocolError(HTTPStatus.BAD_REQUEST, "invalid_classification", "source or kind is invalid")
        if metadata["retentionClass"] not in RETENTIONS:
            raise ProtocolError(HTTPStatus.BAD_REQUEST, "invalid_retention", "retention class is invalid")
        if metadata["contentType"] not in CONTENT_TYPES:
            raise ProtocolError(
                HTTPStatus.UNSUPPORTED_MEDIA_TYPE,
                "unsupported_content_type",
                "content type is not allowed",
            )
        if not SHA256.fullmatch(metadata["sha256"] or ""):
            raise ProtocolError(HTTPStatus.BAD_REQUEST, "invalid_sha256", "SHA-256 identity is invalid")
        if metadata["syntheticFixture"] not in {True, False}:
            raise ProtocolError(
                HTTPStatus.BAD_REQUEST,
                "invalid_synthetic_identity",
                "synthetic fixture identity must be explicit",
            )
        if not isinstance(metadata["createdAt"], str) or not metadata["createdAt"].endswith("Z"):
            raise ProtocolError(HTTPStatus.BAD_REQUEST, "invalid_created_at", "createdAt must be UTC")
        if length <= 0:
            raise ProtocolError(HTTPStatus.BAD_REQUEST, "empty_content", "attachment content is empty")
        if length > self.max_file_bytes:
            raise ProtocolError(
                HTTPStatus.REQUEST_ENTITY_TOO_LARGE,
                "file_too_large",
                "attachment exceeds the configured 16 MiB limit",
            )
        if metadata["source"] == "BROWSER_SCREENSHOT" and metadata["kind"] != "IMAGE":
            raise ProtocolError(HTTPStatus.BAD_REQUEST, "invalid_classification", "screenshot source requires IMAGE")
        if metadata["source"] == "BROWSER_TRACE" and metadata["kind"] != "TRACE":
            raise ProtocolError(HTTPStatus.BAD_REQUEST, "invalid_classification", "trace source requires TRACE")
        if metadata["source"] == "REPORT" and metadata["kind"] != "REPORT":
            raise ProtocolError(HTTPStatus.BAD_REQUEST, "invalid_classification", "report source requires REPORT")

    def _validate_content(self, content_type: str, first: bytes, path: Path) -> None:
        valid = False
        if content_type == "image/png":
            valid = first.startswith(b"\x89PNG\r\n\x1a\n")
        elif content_type == "image/jpeg":
            valid = first.startswith(b"\xff\xd8\xff")
        elif content_type == "image/webp":
            valid = len(first) >= 12 and first[:4] == b"RIFF" and first[8:12] == b"WEBP"
        elif content_type == "application/pdf":
            valid = first.startswith(b"%PDF-")
        elif content_type == "application/zip":
            valid = first.startswith((b"PK\x03\x04", b"PK\x05\x06", b"PK\x07\x08"))
        elif content_type == "application/json":
            try:
                json.loads(path.read_text(encoding="utf-8"))
                valid = True
            except (UnicodeDecodeError, json.JSONDecodeError):
                valid = False
        elif content_type == "text/plain":
            try:
                value = path.read_text(encoding="utf-8")
                valid = "\x00" not in value
            except UnicodeDecodeError:
                valid = False
        if not valid:
            raise ProtocolError(
                HTTPStatus.UNPROCESSABLE_ENTITY,
                "content_type_mismatch",
                "content does not match the declared allowed content type",
            )

    def _verify_content(self, stored: dict[str, Any], content_path: Path) -> None:
        size = content_path.stat().st_size
        digest = hashlib.sha256(content_path.read_bytes()).hexdigest()
        if size != stored.get("sizeBytes") or digest != stored.get("sha256"):
            raise ProtocolError(
                HTTPStatus.CONFLICT,
                "retained_integrity_conflict",
                "retained attachment no longer matches indexed integrity metadata",
            )

    def _session_bytes(self, session_id: str) -> int:
        session_dir = self.sessions / session_id
        if not session_dir.exists():
            return 0
        total = 0
        for metadata_path in session_dir.glob("*/metadata.json"):
            stored = self._read_json(metadata_path)
            if stored.get("sessionId") != session_id:
                raise ProtocolError(
                    HTTPStatus.CONFLICT,
                    "ownership_state_conflict",
                    "retained session contains foreign ownership metadata",
                )
            size = stored.get("sizeBytes")
            if not isinstance(size, int) or size <= 0:
                raise ProtocolError(
                    HTTPStatus.CONFLICT,
                    "retained_integrity_conflict",
                    "retained attachment size metadata is invalid",
                )
            total += size
        return total

    def _public(self, stored: dict[str, Any]) -> dict[str, Any]:
        return {
            key: stored[key]
            for key in (
                "protocolVersion", "workerId", "sessionId", "attachmentId",
                "storageIdentity", "source", "kind", "contentType", "sizeBytes",
                "retentionClass", "sha256", "syntheticFixture", "createdAt", "storedAt",
            )
        }

    def _uuid(self, value: str, code: str) -> str:
        try:
            parsed = uuid.UUID(value)
        except (ValueError, TypeError, AttributeError):
            raise ProtocolError(HTTPStatus.BAD_REQUEST, code, "identity must be a canonical UUID")
        if str(parsed) != value:
            raise ProtocolError(HTTPStatus.BAD_REQUEST, code, "identity must be a canonical UUID")
        return value

    def _session_identity(self, value: str) -> str:
        if isinstance(value, str) and re.fullmatch(r"[1-9][0-9]{0,18}", value):
            return value
        try:
            return self._uuid(value, "invalid_session_id")
        except ProtocolError as error:
            raise ProtocolError(
                HTTPStatus.BAD_REQUEST,
                "invalid_session_id",
                "session identity must be a positive decimal or canonical UUID",
            ) from error

    def _inside(self, path: Path) -> None:
        try:
            path.resolve().relative_to(self.root)
        except ValueError:
            raise ProtocolError(HTTPStatus.BAD_REQUEST, "invalid_storage_identity", "storage path is outside root")

    def _atomic_json(self, path: Path, value: dict[str, Any]) -> None:
        fd, temporary = tempfile.mkstemp(prefix=".metadata-", dir=path.parent)
        try:
            with os.fdopen(fd, "w", encoding="utf-8") as handle:
                json.dump(value, handle, sort_keys=True, separators=(",", ":"))
                handle.flush()
                os.fsync(handle.fileno())
            os.chmod(temporary, 0o600)
            os.replace(temporary, path)
        finally:
            if os.path.exists(temporary):
                os.unlink(temporary)

    def _read_json(self, path: Path) -> dict[str, Any]:
        try:
            parsed = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as error:
            raise ProtocolError(
                HTTPStatus.CONFLICT,
                "retained_metadata_conflict",
                "retained attachment metadata is unreadable",
            ) from error
        if not isinstance(parsed, dict) or parsed.get("protocolVersion") != PROTOCOL:
            raise ProtocolError(
                HTTPStatus.CONFLICT,
                "retained_metadata_conflict",
                "retained attachment metadata schema is unsupported",
            )
        return parsed

    def _prepare_directory(self, path: Path, mode: int) -> None:
        path.mkdir(mode=mode, parents=True, exist_ok=True)
        if path.is_symlink() or not path.is_dir():
            raise RuntimeError(f"attachment directory is not a plain directory: {path}")
        os.chmod(path, mode)

    def _remove_empty_owned(self, path: Path) -> None:
        if path == self.root or path == self.sessions or path == self.incoming:
            return
        self._inside(path)
        try:
            path.rmdir()
        except (FileNotFoundError, OSError):
            pass

    def _fsync_directory(self, path: Path) -> None:
        descriptor = os.open(path, os.O_RDONLY)
        try:
            os.fsync(descriptor)
        finally:
            os.close(descriptor)


class AttachmentServer(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self, address: tuple[str, int], store: AttachmentStore, token: str):
        self.store = store
        self.token = token
        super().__init__(address, AttachmentHandler)


class AttachmentHandler(BaseHTTPRequestHandler):
    server: AttachmentServer

    def log_message(self, message: str, *args: Any) -> None:
        print(json.dumps({
            "at": utc_now(),
            "remote": self.client_address[0],
            "message": message % args,
        }), flush=True)

    def do_GET(self) -> None:
        try:
            self._authenticate()
            path = urlparse(self.path).path
            if path == "/v1/health":
                self._json(HTTPStatus.OK, self.server.store.health())
                return
            session_id, attachment_id, operation = self._route(path)
            if operation == "metadata":
                self._json(
                    HTTPStatus.OK,
                    self.server.store.metadata(session_id, attachment_id),
                )
                return
            if operation == "content":
                metadata, content_path = self.server.store.content(session_id, attachment_id)
                self._content(metadata, content_path)
                return
            raise ProtocolError(HTTPStatus.NOT_FOUND, "not_found", "route does not exist")
        except ProtocolError as error:
            self._error(error)

    def do_PUT(self) -> None:
        try:
            self._authenticate()
            session_id, attachment_id, operation = self._route(urlparse(self.path).path)
            if operation != "content":
                raise ProtocolError(HTTPStatus.NOT_FOUND, "not_found", "route does not exist")
            length = self._length()
            metadata = self._headers()
            stored, created = self.server.store.put(
                session_id,
                attachment_id,
                metadata,
                self.rfile,
                length,
            )
            self._json(HTTPStatus.CREATED if created else HTTPStatus.OK, stored)
        except ProtocolError as error:
            self._error(error)

    def do_DELETE(self) -> None:
        try:
            self._authenticate()
            session_id, attachment_id, operation = self._route(urlparse(self.path).path)
            if operation != "content":
                raise ProtocolError(HTTPStatus.NOT_FOUND, "not_found", "route does not exist")
            if self.headers.get("X-Atenea-Synthetic-Fixture") != "true":
                raise ProtocolError(
                    HTTPStatus.FORBIDDEN,
                    "synthetic_identity_required",
                    "exact synthetic-fixture confirmation is required",
                )
            self._json(
                HTTPStatus.OK,
                self.server.store.delete_synthetic(session_id, attachment_id),
            )
        except ProtocolError as error:
            self._error(error)

    def _route(self, path: str) -> tuple[str, str, str]:
        parts = path.strip("/").split("/")
        if len(parts) == 6 and parts[0:2] == ["v1", "work-sessions"] and parts[3] == "attachments":
            return parts[2], parts[4], parts[5]
        raise ProtocolError(HTTPStatus.NOT_FOUND, "not_found", "route does not exist")

    def _headers(self) -> dict[str, Any]:
        synthetic = self.headers.get("X-Atenea-Synthetic-Fixture")
        return {
            "source": self.headers.get("X-Atenea-Source"),
            "kind": self.headers.get("X-Atenea-Kind"),
            "contentType": self.headers.get_content_type(),
            "retentionClass": self.headers.get("X-Atenea-Retention-Class"),
            "sha256": self.headers.get("X-Atenea-Sha256"),
            "syntheticFixture": True if synthetic == "true" else False if synthetic == "false" else None,
            "createdAt": self.headers.get("X-Atenea-Created-At"),
        }

    def _length(self) -> int:
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            raise ProtocolError(HTTPStatus.BAD_REQUEST, "invalid_length", "Content-Length is invalid")
        if length > self.server.store.max_file_bytes:
            raise ProtocolError(
                HTTPStatus.REQUEST_ENTITY_TOO_LARGE,
                "file_too_large",
                "attachment exceeds the configured 16 MiB limit",
            )
        return length

    def _authenticate(self) -> None:
        expected = f"Bearer {self.server.token}"
        if not hmac.compare_digest(self.headers.get("Authorization", ""), expected):
            raise ProtocolError(
                HTTPStatus.UNAUTHORIZED,
                "unauthorized",
                "valid worker credential required",
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

    def _content(self, metadata: dict[str, Any], path: Path) -> None:
        self.send_response(HTTPStatus.OK)
        self.send_header("Content-Type", metadata["contentType"])
        self.send_header("Content-Length", str(metadata["sizeBytes"]))
        self.send_header("Digest", "sha-256=" + metadata["sha256"])
        self.send_header("Cache-Control", "private, no-store")
        self.end_headers()
        with path.open("rb") as handle:
            while chunk := handle.read(64 * 1024):
                self.wfile.write(chunk)


def read_token(path: Path) -> str:
    stat = path.stat()
    if stat.st_uid != 0 or stat.st_mode & 0o037:
        raise RuntimeError("token file must be root-owned, group-readable and otherwise private")
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
    parser.add_argument("--token-file", type=Path, required=True)
    parser.add_argument("--max-file-bytes", type=int, default=MAX_FILE_BYTES)
    parser.add_argument("--max-session-bytes", type=int, default=MAX_SESSION_BYTES)
    args = parser.parse_args()
    if not (1024 <= args.port <= 65535):
        raise SystemExit("port is outside policy")
    if not re.fullmatch(r"[A-Za-z0-9._-]{1,80}", args.worker_id):
        raise SystemExit("worker id is invalid")
    if not (1 <= args.max_file_bytes <= args.max_session_bytes):
        raise SystemExit("attachment limits are invalid")

    store = AttachmentStore(
        args.root,
        args.worker_id,
        args.max_file_bytes,
        args.max_session_bytes,
    )
    server = AttachmentServer((args.bind, args.port), store, read_token(args.token_file))

    def shutdown(_signum: int, _frame: Any) -> None:
        threading.Thread(target=server.shutdown, daemon=True).start()

    signal.signal(signal.SIGTERM, shutdown)
    signal.signal(signal.SIGINT, shutdown)
    try:
        server.serve_forever(poll_interval=0.25)
    finally:
        server.server_close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
