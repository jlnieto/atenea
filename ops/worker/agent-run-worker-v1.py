#!/usr/bin/env python3
"""Private, durable AgentRun worker protocol with exact project opt-in."""

from __future__ import annotations

import argparse
import hashlib
import hmac
import json
import os
import re
import signal
import stat
import subprocess
import tempfile
import threading
import time
import uuid
from datetime import datetime, timezone
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any
from urllib.parse import urlparse

PROTOCOL = "agent-run-worker/v1"
SYNTHETIC_CAPABILITY = "synthetic-routing-v1"
PROJECT_CAPABILITY = "project-codex-v1"
TERMINAL = {"SUCCEEDED", "FAILED", "CANCELLED"}
NON_TERMINAL = {"QUEUED", "STARTING", "RUNNING", "CANCELLING", "RECONCILING"}
CREATE_KEYS = {
    "dispatchId", "sessionId", "workspaceIdentity", "workloadClass", "leaseGeneration", "workload"
}
SYNTHETIC_WORKLOAD_KEYS = {"kind", "message", "durationMs", "steps"}
PROJECT_WORKLOAD_KEYS = {
    "kind", "projectId", "repository", "branch", "commit",
    "manifestSha256", "message", "threadId", "instructionBundleRevision",
    "instructionBundleSha256", "platformInstructionSha256",
    "projectInstructionPath", "projectInstructionSha256",
}
WORKSPACE_ENSURE_KEYS = {
    "sessionId", "workspaceIdentity", "projectId", "repository", "branch",
    "commit", "manifestSha256", "workspaceBranch",
}
DRAFT_FINGERPRINT_KEYS = {
    "sessionId", "workspaceIdentity", "projectId", "repository", "branch",
    "acceptedCommit", "manifestSha256",
}
SOURCE_TREE_FINGERPRINT_KEYS = {
    "sessionId", "workspaceIdentity", "projectId", "repository", "branch",
    "commit", "manifestSha256",
}
VALIDATION_KEYS = {
    "validationId", "sessionId", "workspaceIdentity", "projectId",
    "repository", "branch", "commit", "manifestSha256", "operation",
    "definitionRevision", "sourceTreeFingerprintSha256",
}
REPOSITORY_ROLE_KEYS = {
    "sessionId", "workspaceIdentity", "changeIdentity", "codeCommit",
}
VALIDATION_DEFINITIONS = {
    "BACKEND_TEST": ("atenea-backend-test-v1", 900),
    "WEB_BUILD": ("atenea-web-build-v1", 600),
    "ANDROID_BUILD": ("atenea-android-build-v1", 1200),
    "PLAYWRIGHT_ACCEPTANCE": ("atenea-playwright-acceptance-v1", 600),
}
PROJECT_ID = "atenea"
PROJECT_REPOSITORY = "https://github.com/jlnieto/atenea.git"
PROJECT_BRANCH = "feature/actualizar-conversacion-en-web"
PROJECT_MANIFEST_SHA256 = "3b26e1899a06993bee69ac596e7cb69b6200a37d063d98203ad308058c91bfa3"
INSTRUCTION_BUNDLE_REVISION = "atenea-reviewed-instruction-bundle-v1"
PLATFORM_INSTRUCTION_SHA256 = "44c578a286eb50b35612be0b6c38d59a503e6fee1ecf6cd0339415af018cdf0d"
PROJECT_INSTRUCTION_PATH = "AGENTS.md"
ATENEA_PROJECT_INSTRUCTION_SHA256 = "a09adc5855ff54490211a0f5c82f413cb84ee7197b2b350e0b0dc40eba7c98dc"
ATENEA_INSTRUCTION_BUNDLE_SHA256 = "ab9f1877c83333945497797e6b8aefd20f67debf8e3bdc6d1b824fc5a3f86c04"
PROJECT_MIRROR = Path("/srv/atenea/repositories/atenea.git")
COMMIT_PATTERN = re.compile(r"^[0-9a-f]{40}$")
BEAUTIPS_PROJECT_ID = "beautips"
BEAUTIPS_PROJECT_REPOSITORY = "https://github.com/jlnieto/beautips.git"
BEAUTIPS_PROJECT_BRANCH = "main"
BEAUTIPS_PROJECT_COMMIT = "e9e0b3c319c518363d4135f5378ebbddced96dfb"
BEAUTIPS_PROJECT_MANIFEST_SHA256 = (
    "365f1c66c51c9018c2c6f48deddbaa619b4588cae2dd463dcd916cde884e2e82"
)
BEAUTIPS_PROJECT_INSTRUCTION_SHA256 = "0e06aa861b11e324610f3a7cd7aef1bff3c2712d7b838a052bb5748542c8e1c7"
BEAUTIPS_INSTRUCTION_BUNDLE_SHA256 = "6e5affe84ca7e300c1c3f0907056013820999699d84fd0e491add924ad685b60"


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


def canonical_hash(value: Any) -> str:
    encoded = json.dumps(value, sort_keys=True, separators=(",", ":")).encode()
    return hashlib.sha256(encoded).hexdigest()


class ProtocolError(Exception):
    def __init__(self, status: int, code: str, message: str):
        super().__init__(message)
        self.status = status
        self.code = code


class WorkerState:
    def __init__(
        self,
        state_dir: Path,
        worker_id: str,
        normal_capacity: int = 4,
        heavy_capacity: int = 2,
        project_config: Path | None = None,
        project_runner: Path | None = None,
        project_timeout: int = 1800,
        project_config_uid: int = 0,
        privilege_command: tuple[str, ...] = ("sudo",),
        project_workspace_activator: Path | None = None,
        beautips_project_config: Path | None = None,
        beautips_project_runner: Path | None = None,
        beautips_workspace_activator: Path | None = None,
        project_validation_mediator: Path | None = None,
        repository_role_mediator: Path | None = None,
    ):
        self.state_dir = state_dir
        self.state_file = state_dir / "executions.json"
        self.worker_id = worker_id
        self.normal_capacity = normal_capacity
        self.heavy_capacity = heavy_capacity
        self.project_config = project_config
        self.project_runner = project_runner
        self.project_timeout = project_timeout
        self.project_config_uid = project_config_uid
        self.privilege_command = privilege_command
        self.project_workspace_activator = project_workspace_activator
        self.beautips_project_config = beautips_project_config
        self.beautips_project_runner = beautips_project_runner
        self.beautips_workspace_activator = beautips_workspace_activator
        self.project_validation_mediator = project_validation_mediator
        self.repository_role_mediator = repository_role_mediator
        self.lock = threading.RLock()
        self.wakeup = threading.Event()
        self.stop_event = threading.Event()
        self.executions: dict[str, dict[str, Any]] = {}
        self.validations: dict[str, dict[str, Any]] = {}
        self.threads: dict[str, threading.Thread] = {}
        self.processes: dict[str, subprocess.Popen[str]] = {}
        self.scheduler: threading.Thread | None = None
        self._load()

    def _load(self) -> None:
        self.state_dir.mkdir(mode=0o700, parents=True, exist_ok=True)
        os.chmod(self.state_dir, 0o700)
        if not self.state_file.exists():
            return
        parsed = json.loads(self.state_file.read_text(encoding="utf-8"))
        if parsed.get("protocol") != PROTOCOL or not isinstance(parsed.get("executions"), dict):
            raise RuntimeError("durable worker state has an unsupported schema")
        self.executions = parsed["executions"]
        if not isinstance(parsed.get("validations", {}), dict):
            raise RuntimeError("durable worker validation state has an unsupported schema")
        self.validations = parsed.get("validations", {})
        for validation in self.validations.values():
            if validation.get("status") == "RUNNING":
                validation["status"] = "BLOCKED"
                validation["exitCode"] = None
                validation["durationMillis"] = max(0, int(validation.get("durationMillis") or 0))
                validation["artifactManifestSha256"] = hashlib.sha256(
                    f"{validation.get('validationId')}:worker-restart".encode()
                ).hexdigest()
                validation["summary"] = (
                    "Worker restarted; the persisted validation requires an exact retry"
                )
                validation["finishedAt"] = utc_now()
        for execution in self.executions.values():
            if execution["status"] in {"STARTING", "RUNNING", "CANCELLING"}:
                execution["status"] = "RECONCILING"
                execution["statusReason"] = "Worker service restarted; persisted ownership requires reconciliation"
                execution["reconcileRequired"] = True
                execution["revision"] += 1
                execution["updatedAt"] = utc_now()
            else:
                execution.setdefault("reconcileRequired", False)
        self._persist()

    def _persist(self) -> None:
        payload = {
            "protocol": PROTOCOL,
            "workerId": self.worker_id,
            "executions": self.executions,
            "validations": self.validations,
        }
        fd, temporary = tempfile.mkstemp(prefix=".executions-", dir=self.state_dir)
        try:
            with os.fdopen(fd, "w", encoding="utf-8") as handle:
                json.dump(payload, handle, sort_keys=True, separators=(",", ":"))
                handle.flush()
                os.fsync(handle.fileno())
            os.chmod(temporary, 0o600)
            os.replace(temporary, self.state_file)
            directory_fd = os.open(self.state_dir, os.O_RDONLY)
            try:
                os.fsync(directory_fd)
            finally:
                os.close(directory_fd)
        finally:
            if os.path.exists(temporary):
                os.unlink(temporary)

    def start(self) -> None:
        self.scheduler = threading.Thread(target=self._schedule_loop, name="agent-run-scheduler", daemon=True)
        self.scheduler.start()
        self.wakeup.set()

    def stop(self) -> None:
        self.stop_event.set()
        self.wakeup.set()
        if self.scheduler:
            self.scheduler.join(timeout=5)
        with self.lock:
            threads = list(self.threads.values())
            processes = list(self.processes.values())
        for process in processes:
            if process.poll() is None:
                process.terminate()
        for thread in threads:
            thread.join(timeout=5)

    def health(self) -> dict[str, Any]:
        with self.lock:
            normal = sum(1 for item in self.executions.values() if item["status"] in {"STARTING", "RUNNING"} )
            heavy = sum(
                1 for item in self.executions.values()
                if item["status"] in {"STARTING", "RUNNING"} and item["workloadClass"] == "HEAVY"
            )
            queued = sum(1 for item in self.executions.values() if item["status"] in {"QUEUED", "RECONCILING"})
            capabilities = [SYNTHETIC_CAPABILITY]
            if self._project_selection_enabled():
                capabilities.append(PROJECT_CAPABILITY)
            return {
                "protocolVersion": PROTOCOL,
                "workerId": self.worker_id,
                "healthy": True,
                "capabilities": capabilities,
                "normalCapacity": self.normal_capacity,
                "heavyCapacity": self.heavy_capacity,
                "normalInUse": normal,
                "heavyInUse": heavy,
                "queued": queued,
                "serverTime": utc_now(),
            }

    def create(self, request: dict[str, Any]) -> tuple[dict[str, Any], bool]:
        self._validate_create(request)
        dispatch_id = request["dispatchId"]
        fingerprint = canonical_hash(request)
        with self.lock:
            existing = self.executions.get(dispatch_id)
            if existing:
                if existing["requestFingerprint"] != fingerprint:
                    raise ProtocolError(
                        HTTPStatus.CONFLICT,
                        "dispatch_identity_conflict",
                        "dispatchId already owns a different immutable request",
                    )
                return self._public(existing), False

            now = utc_now()
            execution = {
                "dispatchId": dispatch_id,
                "executionId": str(uuid.uuid4()),
                "sessionId": request["sessionId"],
                "workspaceIdentity": request["workspaceIdentity"],
                "workloadClass": request["workloadClass"],
                "leaseGeneration": request["leaseGeneration"],
                "workload": request["workload"],
                "requestFingerprint": fingerprint,
                "status": "QUEUED",
                "statusReason": "Awaiting worker admission",
                "revision": 1,
                "progress": 0,
                "createdAt": now,
                "updatedAt": now,
                "startedAt": None,
                "finishedAt": None,
                "cancelRequested": False,
                "reconcileRequired": False,
                "result": None,
            }
            self.executions[dispatch_id] = execution
            self._persist()
            self.wakeup.set()
            return self._public(execution), True

    def ensure_workspace(self, request: dict[str, Any]) -> dict[str, Any]:
        if set(request) != WORKSPACE_ENSURE_KEYS:
            raise ProtocolError(
                HTTPStatus.BAD_REQUEST,
                "invalid_workspace_request",
                "workspace request fields are invalid",
            )
        session_id = request["sessionId"]
        try:
            parsed_session = str(uuid.UUID(session_id))
        except (ValueError, TypeError, AttributeError):
            raise ProtocolError(
                HTTPStatus.BAD_REQUEST,
                "invalid_session",
                "sessionId must be a canonical UUID",
            )
        if parsed_session != session_id:
            raise ProtocolError(
                HTTPStatus.BAD_REQUEST,
                "invalid_session",
                "sessionId must be a canonical UUID",
            )
        project_id = request.get("projectId")
        if project_id == PROJECT_ID:
            route = self._project_route(PROJECT_ID)
            canonical_commit = self._observe_project_commit(route)
            route_identity = {
                "projectId": PROJECT_ID,
                "repository": PROJECT_REPOSITORY,
                "branch": PROJECT_BRANCH,
                "commit": canonical_commit,
                "manifestSha256": PROJECT_MANIFEST_SHA256,
            }
            activator = self.project_workspace_activator
            allowed_slots = {"slot2", "slot3", "slot4"}
        elif project_id == BEAUTIPS_PROJECT_ID:
            canonical_commit = BEAUTIPS_PROJECT_COMMIT
            route_identity = {
                "projectId": BEAUTIPS_PROJECT_ID,
                "repository": BEAUTIPS_PROJECT_REPOSITORY,
                "branch": BEAUTIPS_PROJECT_BRANCH,
                "commit": BEAUTIPS_PROJECT_COMMIT,
                "manifestSha256": BEAUTIPS_PROJECT_MANIFEST_SHA256,
            }
            activator = self.beautips_workspace_activator
            allowed_slots = {"slot2", "slot3", "slot4"}
        else:
            raise ProtocolError(
                HTTPStatus.FORBIDDEN,
                "workspace_ownership_conflict",
                "workspace activation identity is not exact",
            )
        exact = {
            "workspaceIdentity": f"remote:ax42-01:work-session:{session_id}",
            **route_identity,
        }
        if any(request.get(key) != value for key, value in exact.items()):
            raise ProtocolError(
                HTTPStatus.FORBIDDEN,
                "workspace_ownership_conflict",
                "workspace activation identity is not exact",
            )
        workspace_branch = request["workspaceBranch"]
        if (
            not isinstance(workspace_branch, str)
            or workspace_branch != f"atenea/session-{session_id}"
        ):
            raise ProtocolError(
                HTTPStatus.BAD_REQUEST,
                "invalid_workspace_branch",
                "workspace branch is not a persisted WorkSession branch",
            )
        if activator is None or not activator.is_file():
            raise ProtocolError(
                HTTPStatus.SERVICE_UNAVAILABLE,
                "workspace_activation_unavailable",
                "workspace activation is unavailable",
            )
        try:
            completed = subprocess.run(
                [*self.privilege_command, str(activator), "ensure", session_id, workspace_branch],
                stdin=subprocess.DEVNULL,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=True,
                timeout=300,
                check=False,
            )
        except subprocess.TimeoutExpired:
            raise ProtocolError(
                HTTPStatus.GATEWAY_TIMEOUT,
                "workspace_activation_timeout",
                "workspace activation exceeded its finite timeout",
            )
        if completed.returncode != 0:
            detail = completed.stderr.strip().splitlines()
            message = detail[-1][:300] if detail else "workspace activation failed closed"
            raise ProtocolError(
                HTTPStatus.CONFLICT,
                "workspace_activation_failed",
                message,
            )
        try:
            result = json.loads(completed.stdout)
        except json.JSONDecodeError:
            raise ProtocolError(
                HTTPStatus.BAD_GATEWAY,
                "workspace_activation_invalid",
                "workspace activation returned an invalid response",
            )
        if (
            not isinstance(result, dict)
            or result.get("state") != "ready"
            or result.get("sessionId") != session_id
            or result.get("workspaceIdentity") != exact["workspaceIdentity"]
            or result.get("projectId") != project_id
            or result.get("workspaceBranch") != workspace_branch
            or result.get("slot") not in allowed_slots
            or result.get("canonicalCommit") != canonical_commit
            or result.get("valuesExposed") is not False
        ):
            raise ProtocolError(
                HTTPStatus.BAD_GATEWAY,
                "workspace_activation_invalid",
                "workspace activation response ownership is incomplete",
            )
        return result

    def fingerprint_retained_draft(self, request: dict[str, Any]) -> dict[str, Any]:
        if set(request) != DRAFT_FINGERPRINT_KEYS:
            raise ProtocolError(
                HTTPStatus.BAD_REQUEST,
                "invalid_draft_request",
                "draft fingerprint request fields are invalid",
            )
        try:
            session_id = str(uuid.UUID(request.get("sessionId")))
        except (ValueError, TypeError, AttributeError):
            raise ProtocolError(
                HTTPStatus.BAD_REQUEST,
                "invalid_session",
                "sessionId must be a canonical UUID",
            )
        if session_id != request.get("sessionId"):
            raise ProtocolError(
                HTTPStatus.BAD_REQUEST,
                "invalid_session",
                "sessionId must be a canonical UUID",
            )
        route = self._project_route(request.get("projectId"))
        exact = {
            "workspaceIdentity": f"remote:{self.worker_id}:work-session:{session_id}",
            "projectId": PROJECT_ID,
            "repository": PROJECT_REPOSITORY,
            "branch": PROJECT_BRANCH,
            "manifestSha256": PROJECT_MANIFEST_SHA256,
        }
        if route is None or request.get("projectId") != PROJECT_ID or any(
            request.get(key) != value for key, value in exact.items()
        ):
            raise ProtocolError(
                HTTPStatus.FORBIDDEN,
                "draft_ownership_conflict",
                "retained draft identity is not exact",
            )
        accepted_commit = request.get("acceptedCommit")
        if (
            not isinstance(accepted_commit, str)
            or COMMIT_PATTERN.fullmatch(accepted_commit) is None
            or accepted_commit != self._observe_project_commit(route)
        ):
            raise ProtocolError(
                HTTPStatus.CONFLICT,
                "canonical_source_moved",
                "accepted canonical source is not current on the worker mirror",
            )
        config = self._read_project_config(route)
        record = config["workspaces"].get(request["workspaceIdentity"])
        if (
            not isinstance(record, dict)
            or set(record) != {"sessionId", "worktree", "allocationSha256", "canonicalCommit"}
            or record.get("sessionId") != session_id
            or not isinstance(record.get("worktree"), str)
            or COMMIT_PATTERN.fullmatch(str(record.get("canonicalCommit"))) is None
        ):
            raise ProtocolError(
                HTTPStatus.FORBIDDEN,
                "draft_ownership_conflict",
                "persisted retained draft ownership is incomplete or conflicting",
            )
        with self.lock:
            if any(
                execution["sessionId"] == session_id and execution["status"] in NON_TERMINAL
                for execution in self.executions.values()
            ):
                raise ProtocolError(
                    HTTPStatus.CONFLICT,
                    "draft_execution_active",
                    "retained draft still owns a non-terminal execution",
                )

        worktree = Path(record["worktree"])
        if not worktree.is_dir() or worktree.is_symlink():
            raise ProtocolError(
                HTTPStatus.CONFLICT,
                "draft_workspace_unavailable",
                "retained draft workspace is unavailable or unsafe",
            )
        head = self._draft_git(worktree, "rev-parse", "--verify", "HEAD^{commit}").decode().strip()
        if (
            COMMIT_PATTERN.fullmatch(head) is None
            or head != record["canonicalCommit"]
            or head == accepted_commit
        ):
            raise ProtocolError(
                HTTPStatus.CONFLICT,
                "draft_not_stale",
                "retained draft is not an exactly identified stale workspace",
            )

        source = self._source_tree_fingerprint(worktree, head)
        if (
            source["stagedChangeCount"]
            + source["unstagedChangeCount"]
            + source["untrackedChangeCount"]
        ) == 0:
            raise ProtocolError(
                HTTPStatus.CONFLICT,
                "draft_not_dirty",
                "retained draft has no changes to preserve",
            )
        fingerprint = canonical_hash({
            "acceptedCommit": accepted_commit,
            "sourceTreeFingerprintSha256": source["fingerprintSha256"],
        })
        return {
            "state": "draft_blocked_ready",
            "sessionId": session_id,
            "workspaceIdentity": request["workspaceIdentity"],
            "projectId": PROJECT_ID,
            "retainedHead": head,
            "acceptedCommit": accepted_commit,
            "fingerprintSha256": fingerprint,
            "stagedChangeCount": source["stagedChangeCount"],
            "unstagedChangeCount": source["unstagedChangeCount"],
            "untrackedChangeCount": source["untrackedChangeCount"],
            "valuesExposed": False,
        }

    def fingerprint_source_tree(self, request: dict[str, Any]) -> dict[str, Any]:
        if set(request) != SOURCE_TREE_FINGERPRINT_KEYS:
            raise ProtocolError(
                HTTPStatus.BAD_REQUEST,
                "invalid_source_tree_request",
                "source tree fingerprint request fields are invalid",
            )
        try:
            session_id = str(uuid.UUID(request.get("sessionId")))
        except (ValueError, TypeError, AttributeError):
            raise ProtocolError(
                HTTPStatus.BAD_REQUEST,
                "invalid_session",
                "sessionId must be a canonical UUID",
            )
        route = self._project_route(request.get("projectId"))
        exact = {
            "sessionId": session_id,
            "workspaceIdentity": f"remote:{self.worker_id}:work-session:{session_id}",
            "projectId": PROJECT_ID,
            "repository": PROJECT_REPOSITORY,
            "branch": PROJECT_BRANCH,
            "manifestSha256": PROJECT_MANIFEST_SHA256,
        }
        if (
            request.get("sessionId") != session_id
            or route is None
            or request.get("projectId") != PROJECT_ID
            or any(request.get(key) != value for key, value in exact.items())
        ):
            raise ProtocolError(
                HTTPStatus.FORBIDDEN,
                "source_tree_ownership_conflict",
                "source tree identity is not exact",
            )
        commit = request.get("commit")
        config = self._read_project_config(route)
        if (
            not isinstance(commit, str)
            or COMMIT_PATTERN.fullmatch(commit) is None
            or commit != self._observe_project_commit(route)
            or config["commit"] != commit
        ):
            raise ProtocolError(
                HTTPStatus.CONFLICT,
                "canonical_source_moved",
                "source tree canonical ownership is not current",
            )
        record = config["workspaces"].get(request["workspaceIdentity"])
        if (
            not isinstance(record, dict)
            or set(record) != {"sessionId", "worktree", "allocationSha256", "canonicalCommit"}
            or record.get("sessionId") != session_id
            or record.get("canonicalCommit") != commit
            or not isinstance(record.get("worktree"), str)
        ):
            raise ProtocolError(
                HTTPStatus.FORBIDDEN,
                "source_tree_ownership_conflict",
                "persisted source tree ownership is incomplete or conflicting",
            )
        worktree = Path(record["worktree"])
        if not worktree.is_dir() or worktree.is_symlink():
            raise ProtocolError(
                HTTPStatus.CONFLICT,
                "source_tree_unavailable",
                "source tree workspace is unavailable or unsafe",
            )
        head = self._draft_git(worktree, "rev-parse", "--verify", "HEAD^{commit}").decode().strip()
        if head != commit:
            raise ProtocolError(
                HTTPStatus.CONFLICT,
                "source_tree_head_moved",
                "source tree HEAD no longer equals its accepted base commit",
            )
        source = self._source_tree_fingerprint(worktree, head)
        return {
            "state": "observed",
            "sessionId": session_id,
            "workspaceIdentity": request["workspaceIdentity"],
            "projectId": PROJECT_ID,
            "headCommit": head,
            **source,
            "valuesExposed": False,
        }

    def run_validation(self, request: dict[str, Any]) -> dict[str, Any]:
        if set(request) != VALIDATION_KEYS:
            raise ProtocolError(
                HTTPStatus.BAD_REQUEST,
                "invalid_validation_request",
                "validation request fields are invalid",
            )
        try:
            validation_id = str(uuid.UUID(request.get("validationId")))
        except (ValueError, TypeError, AttributeError):
            raise ProtocolError(
                HTTPStatus.BAD_REQUEST,
                "invalid_validation",
                "validationId must be a canonical UUID",
            )
        operation = request.get("operation")
        definition = VALIDATION_DEFINITIONS.get(operation)
        if (
            validation_id != request.get("validationId")
            or definition is None
            or request.get("definitionRevision") != definition[0]
            or not isinstance(request.get("sourceTreeFingerprintSha256"), str)
            or re.fullmatch(r"[0-9a-f]{64}", request["sourceTreeFingerprintSha256"]) is None
        ):
            raise ProtocolError(
                HTTPStatus.FORBIDDEN,
                "validation_authority_conflict",
                "validation definition is not exact",
            )
        source_request = {
            key: request[key]
            for key in SOURCE_TREE_FINGERPRINT_KEYS
        }
        source = self.fingerprint_source_tree(source_request)
        if source["fingerprintSha256"] != request["sourceTreeFingerprintSha256"]:
            raise ProtocolError(
                HTTPStatus.CONFLICT,
                "source_tree_changed",
                "source tree changed before validation admission",
            )

        request_fingerprint = canonical_hash(request)
        with self.lock:
            existing = self.validations.get(validation_id)
            if existing is not None:
                if existing["requestFingerprint"] != request_fingerprint:
                    raise ProtocolError(
                        HTTPStatus.CONFLICT,
                        "validation_identity_conflict",
                        "validationId already owns a different immutable request",
                    )
                return self._public_validation(existing)
            now = utc_now()
            validation = {
                "validationId": validation_id,
                "sessionId": request["sessionId"],
                "workspaceIdentity": request["workspaceIdentity"],
                "operation": operation,
                "definitionRevision": definition[0],
                "sourceTreeFingerprintSha256": request["sourceTreeFingerprintSha256"],
                "requestFingerprint": request_fingerprint,
                "status": "RUNNING",
                "exitCode": None,
                "durationMillis": 0,
                "artifactManifestSha256": None,
                "summary": "Bounded validation is running",
                "valuesExposed": False,
                "createdAt": now,
                "finishedAt": None,
            }
            self.validations[validation_id] = validation
            self._persist()

        started = time.monotonic()
        mediator = self.project_validation_mediator
        if mediator is None or not mediator.is_file():
            return self._finish_validation(
                validation_id,
                "BLOCKED",
                None,
                started,
                hashlib.sha256(b"validation mediator unavailable").hexdigest(),
                "Validation mediator is unavailable",
            )
        try:
            completed = subprocess.run(
                [
                    *self.privilege_command,
                    str(mediator),
                    operation,
                    request["sessionId"],
                    request["sourceTreeFingerprintSha256"],
                    validation_id,
                ],
                stdin=subprocess.DEVNULL,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=True,
                timeout=definition[1],
                check=False,
            )
        except subprocess.TimeoutExpired:
            return self._finish_validation(
                validation_id,
                "BLOCKED",
                None,
                started,
                hashlib.sha256(f"{operation}:timeout".encode()).hexdigest(),
                "Validation exceeded its finite timeout",
            )
        except OSError:
            return self._finish_validation(
                validation_id,
                "BLOCKED",
                None,
                started,
                hashlib.sha256(f"{operation}:unavailable".encode()).hexdigest(),
                "Validation mediator could not be started",
            )
        if completed.returncode != 0:
            return self._finish_validation(
                validation_id,
                "BLOCKED",
                None,
                started,
                hashlib.sha256(f"{operation}:mediator-failed".encode()).hexdigest(),
                "Validation mediator failed closed",
            )
        try:
            result = json.loads(completed.stdout)
        except json.JSONDecodeError:
            result = None
        required = {
            "validationId", "sessionId", "operation", "definitionRevision",
            "sourceTreeFingerprintSha256", "status", "exitCode",
            "durationMillis", "artifactManifestSha256", "summary",
            "valuesExposed",
        }
        if (
            not isinstance(result, dict)
            or set(result) != required
            or result.get("validationId") != validation_id
            or result.get("sessionId") != request["sessionId"]
            or result.get("operation") != operation
            or result.get("definitionRevision") != definition[0]
            or result.get("sourceTreeFingerprintSha256")
                != request["sourceTreeFingerprintSha256"]
            or result.get("status") not in {"SUCCEEDED", "FAILED", "BLOCKED"}
            or not isinstance(result.get("durationMillis"), int)
            or result["durationMillis"] < 0
            or not isinstance(result.get("artifactManifestSha256"), str)
            or re.fullmatch(r"[0-9a-f]{64}", result["artifactManifestSha256"]) is None
            or not isinstance(result.get("summary"), str)
            or not (1 <= len(result["summary"]) <= 500)
            or result.get("valuesExposed") is not False
            or (
                result["status"] == "SUCCEEDED"
                and result.get("exitCode") != 0
            )
            or (
                result["status"] == "FAILED"
                and (
                    not isinstance(result.get("exitCode"), int)
                    or result["exitCode"] == 0
                )
            )
        ):
            return self._finish_validation(
                validation_id,
                "BLOCKED",
                None,
                started,
                hashlib.sha256(f"{operation}:invalid-result".encode()).hexdigest(),
                "Validation mediator returned an invalid closed result",
            )
        return self._finish_validation(
            validation_id,
            result["status"],
            result["exitCode"],
            started,
            result["artifactManifestSha256"],
            result["summary"],
            duration_millis=result["durationMillis"],
        )

    def ensure_repository_roles(self, request: dict[str, Any]) -> dict[str, Any]:
        if set(request) != REPOSITORY_ROLE_KEYS:
            raise ProtocolError(
                HTTPStatus.BAD_REQUEST, "invalid_repository_roles",
                "repository role request fields are invalid")
        try:
            session_id = str(uuid.UUID(request.get("sessionId")))
            change_id = str(uuid.UUID(request.get("changeIdentity")))
        except (ValueError, TypeError, AttributeError):
            raise ProtocolError(
                HTTPStatus.BAD_REQUEST, "invalid_repository_roles",
                "repository role identities must be canonical UUIDs")
        exact_identity = f"remote:{self.worker_id}:work-session:{session_id}"
        if (
            session_id != request.get("sessionId")
            or change_id != request.get("changeIdentity")
            or request.get("workspaceIdentity") != exact_identity
            or not isinstance(request.get("codeCommit"), str)
            or re.fullmatch(r"[0-9a-f]{40}", request["codeCommit"]) is None
        ):
            raise ProtocolError(
                HTTPStatus.FORBIDDEN, "repository_role_authority_conflict",
                "repository role ownership is not exact")
        route = self._project_route(PROJECT_ID)
        config = self._read_project_config(route)
        record = config["workspaces"].get(exact_identity)
        if (
            record is None
            or record.get("sessionId") != session_id
            or record.get("canonicalCommit") != request["codeCommit"]
            or config.get("commit") != request["codeCommit"]
        ):
            raise ProtocolError(
                HTTPStatus.FORBIDDEN, "repository_role_ownership_conflict",
                "persisted code role ownership is conflicting")
        mediator = self.repository_role_mediator
        if mediator is None or not mediator.is_file():
            raise ProtocolError(
                HTTPStatus.SERVICE_UNAVAILABLE, "repository_role_mediator_unavailable",
                "repository role mediator is unavailable")
        try:
            completed = subprocess.run(
                [*self.privilege_command, str(mediator), "ensure",
                 session_id, change_id, request["codeCommit"]],
                stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                stderr=subprocess.PIPE, text=True, timeout=300, check=False)
        except (OSError, subprocess.TimeoutExpired):
            raise ProtocolError(
                HTTPStatus.SERVICE_UNAVAILABLE, "repository_role_mediator_failed",
                "repository role mediator failed closed")
        if completed.returncode != 0:
            raise ProtocolError(
                HTTPStatus.CONFLICT, "repository_role_rejected",
                "repository role mediator rejected ownership")
        try:
            result = json.loads(completed.stdout)
        except json.JSONDecodeError:
            result = None
        if (
            not isinstance(result, dict)
            or set(result) != {
                "sessionId", "workspaceIdentity", "changeIdentity", "roles",
                "valuesExposed"}
            or result.get("sessionId") != session_id
            or result.get("workspaceIdentity") != exact_identity
            or result.get("changeIdentity") != change_id
            or result.get("valuesExposed") is not False
            or not isinstance(result.get("roles"), list)
            or len(result["roles"]) != 3
        ):
            raise ProtocolError(
                HTTPStatus.CONFLICT, "repository_role_result_conflict",
                "repository role result is incomplete or conflicting")
        role_fields = {
            "role", "authority", "repository", "branch", "commit",
            "mirrorIdentitySha256", "worktreeIdentitySha256",
            "validationProfile", "readiness",
        }
        expected_roles = {
            "ATENEA_CODE": (PROJECT_BRANCH, request["codeCommit"], "atenea-code-v1"),
            "PROGRAMME_OPENSPEC": (
                "program/remote-codex-worker-platform", None, "openspec-strict-v1"),
            "WORKER_SOURCE": (
                "program/remote-codex-worker-platform", None, "worker-contract-v1"),
        }
        seen: set[str] = set()
        program_commits: set[str] = set()
        for role in result["roles"]:
            expected = expected_roles.get(role.get("role")) if isinstance(role, dict) else None
            if (
                not isinstance(role, dict)
                or set(role) != role_fields
                or expected is None
                or role["role"] in seen
                or role.get("authority") != "READ_WRITE"
                or role.get("repository") != PROJECT_REPOSITORY
                or role.get("branch") != expected[0]
                or role.get("validationProfile") != expected[2]
                or role.get("readiness") != "DRAFT"
                or COMMIT_PATTERN.fullmatch(str(role.get("commit"))) is None
                or re.fullmatch(r"[0-9a-f]{64}", str(role.get("mirrorIdentitySha256"))) is None
                or re.fullmatch(r"[0-9a-f]{64}", str(role.get("worktreeIdentitySha256"))) is None
                or (expected[1] is not None and role.get("commit") != expected[1])
            ):
                raise ProtocolError(
                    HTTPStatus.CONFLICT, "repository_role_result_conflict",
                    "repository role result is incomplete or conflicting")
            seen.add(role["role"])
            if role["role"] != "ATENEA_CODE":
                program_commits.add(role["commit"])
        if seen != set(expected_roles) or len(program_commits) != 1:
            raise ProtocolError(
                HTTPStatus.CONFLICT, "repository_role_result_conflict",
                "repository role result is incomplete or conflicting")
        return result

    def _finish_validation(
        self,
        validation_id: str,
        status: str,
        exit_code: int | None,
        started: float,
        artifact_manifest_sha256: str,
        summary: str,
        duration_millis: int | None = None,
    ) -> dict[str, Any]:
        with self.lock:
            validation = self.validations[validation_id]
            validation["status"] = status
            validation["exitCode"] = exit_code
            validation["durationMillis"] = (
                duration_millis
                if duration_millis is not None
                else int((time.monotonic() - started) * 1000)
            )
            validation["artifactManifestSha256"] = artifact_manifest_sha256
            validation["summary"] = summary
            validation["finishedAt"] = utc_now()
            self._persist()
            return self._public_validation(validation)

    def _public_validation(self, validation: dict[str, Any]) -> dict[str, Any]:
        return {
            key: validation.get(key)
            for key in (
                "validationId", "sessionId", "workspaceIdentity", "operation",
                "definitionRevision", "sourceTreeFingerprintSha256", "status",
                "exitCode", "durationMillis", "artifactManifestSha256",
                "summary", "valuesExposed",
            )
        }

    def _source_tree_fingerprint(self, worktree: Path, head: str) -> dict[str, Any]:
        staged = self._z_entries(self._draft_git(worktree, "diff", "--cached", "--name-only", "-z"))
        unstaged = self._z_entries(self._draft_git(worktree, "diff", "--name-only", "-z"))
        untracked = self._z_entries(
            self._draft_git(worktree, "ls-files", "--others", "--exclude-standard", "-z")
        )
        if len(staged) + len(unstaged) + len(untracked) > 10_000:
            raise ProtocolError(
                HTTPStatus.CONFLICT,
                "source_tree_fingerprint_limit",
                "source tree exceeds the bounded fingerprint entry limit",
            )

        tracked_diff = self._draft_git(worktree, "diff", "--binary", "HEAD")
        if len(tracked_diff) > 256 * 1024 * 1024:
            raise ProtocolError(
                HTTPStatus.CONFLICT,
                "source_tree_fingerprint_limit",
                "tracked source tree exceeds the bounded fingerprint size limit",
            )
        untracked_digest = hashlib.sha256()
        untracked_size = 0
        for relative_bytes in untracked:
            relative = relative_bytes.decode("utf-8", errors="surrogateescape")
            candidate = worktree / relative
            try:
                metadata = candidate.lstat()
            except OSError:
                metadata = None
            if metadata is None or not stat.S_ISREG(metadata.st_mode):
                raise ProtocolError(
                    HTTPStatus.CONFLICT,
                    "source_tree_unsafe",
                    "source tree contains an unsafe untracked entry",
                )
            untracked_size += metadata.st_size
            if untracked_size > 256 * 1024 * 1024:
                raise ProtocolError(
                    HTTPStatus.CONFLICT,
                    "source_tree_fingerprint_limit",
                    "untracked source tree exceeds the bounded fingerprint size limit",
                )
            untracked_digest.update(relative_bytes)
            untracked_digest.update(b"\0")
            file_digest = hashlib.sha256()
            try:
                with candidate.open("rb") as handle:
                    while chunk := handle.read(1024 * 1024):
                        file_digest.update(chunk)
            except OSError:
                raise ProtocolError(
                    HTTPStatus.CONFLICT,
                    "source_tree_unavailable",
                    "source tree changed during fingerprinting",
                )
            untracked_digest.update(file_digest.digest())
        fingerprint = canonical_hash({
            "headCommit": head,
            "trackedDiffSha256": hashlib.sha256(tracked_diff).hexdigest(),
            "untrackedManifestSha256": untracked_digest.hexdigest(),
            "stagedChangeCount": len(staged),
            "unstagedChangeCount": len(unstaged),
            "untrackedChangeCount": len(untracked),
        })
        return {
            "fingerprintSha256": fingerprint,
            "stagedChangeCount": len(staged),
            "unstagedChangeCount": len(unstaged),
            "untrackedChangeCount": len(untracked),
        }

    def _draft_git(self, worktree: Path, *arguments: str) -> bytes:
        try:
            completed = subprocess.run(
                ["git", "-c", f"safe.directory={worktree}", "-C", str(worktree), *arguments],
                stdin=subprocess.DEVNULL,
                stdout=subprocess.PIPE,
                stderr=subprocess.DEVNULL,
                timeout=15,
                check=True,
            )
            return completed.stdout
        except (OSError, subprocess.SubprocessError):
            raise ProtocolError(
                HTTPStatus.CONFLICT,
                "draft_workspace_unavailable",
                "retained draft Git state is unavailable",
            )

    def _z_entries(self, value: bytes) -> list[bytes]:
        return [entry for entry in value.split(b"\0") if entry]

    def get(self, dispatch_id: str) -> dict[str, Any]:
        with self.lock:
            execution = self.executions.get(dispatch_id)
            if not execution:
                raise ProtocolError(HTTPStatus.NOT_FOUND, "execution_not_found", "execution does not exist")
            return self._public(execution)

    def renew(self, dispatch_id: str, request: dict[str, Any]) -> dict[str, Any]:
        if set(request) != {"executionId", "leaseGeneration"}:
            raise ProtocolError(HTTPStatus.BAD_REQUEST, "invalid_lease", "lease request fields are invalid")
        with self.lock:
            execution = self._owned(dispatch_id, request["executionId"])
            generation = request["leaseGeneration"]
            if not isinstance(generation, int) or generation < execution["leaseGeneration"]:
                raise ProtocolError(HTTPStatus.CONFLICT, "stale_lease", "lease generation is stale")
            execution["leaseGeneration"] = generation
            execution["updatedAt"] = utc_now()
            self._persist()
            return self._public(execution)

    def cancel(self, dispatch_id: str, request: dict[str, Any]) -> dict[str, Any]:
        if set(request) != {"executionId"}:
            raise ProtocolError(HTTPStatus.BAD_REQUEST, "invalid_cancel", "cancel request fields are invalid")
        with self.lock:
            execution = self._owned(dispatch_id, request["executionId"])
            if execution["status"] in TERMINAL:
                return self._public(execution)
            execution["cancelRequested"] = True
            execution["status"] = "CANCELLING"
            execution["statusReason"] = "Cancellation requested for exact execution"
            execution["revision"] += 1
            execution["updatedAt"] = utc_now()
            self._persist()
            process = self.processes.get(dispatch_id)
            if process and process.poll() is None:
                process.terminate()
            self.wakeup.set()
            return self._public(execution)

    def _owned(self, dispatch_id: str, execution_id: Any) -> dict[str, Any]:
        execution = self.executions.get(dispatch_id)
        if not execution:
            raise ProtocolError(HTTPStatus.NOT_FOUND, "execution_not_found", "execution does not exist")
        if not isinstance(execution_id, str) or execution["executionId"] != execution_id:
            raise ProtocolError(HTTPStatus.CONFLICT, "execution_ownership_conflict", "execution identity does not match")
        return execution

    def _validate_create(self, request: Any) -> None:
        if not isinstance(request, dict) or set(request) != CREATE_KEYS:
            raise ProtocolError(HTTPStatus.BAD_REQUEST, "invalid_dispatch", "dispatch fields are invalid")
        try:
            uuid.UUID(request["dispatchId"])
        except (ValueError, TypeError, AttributeError):
            raise ProtocolError(HTTPStatus.BAD_REQUEST, "invalid_dispatch_id", "dispatchId must be a UUID")
        if not isinstance(request["sessionId"], str) or not request["sessionId"].strip():
            raise ProtocolError(HTTPStatus.BAD_REQUEST, "invalid_session", "sessionId is required")
        if not isinstance(request["workspaceIdentity"], str) or not request["workspaceIdentity"].startswith("remote:"):
            raise ProtocolError(HTTPStatus.BAD_REQUEST, "invalid_workspace", "remote workspace identity is required")
        if request["workloadClass"] not in {"NORMAL", "HEAVY"}:
            raise ProtocolError(HTTPStatus.BAD_REQUEST, "invalid_workload_class", "workloadClass is invalid")
        if not isinstance(request["leaseGeneration"], int) or request["leaseGeneration"] < 1:
            raise ProtocolError(HTTPStatus.BAD_REQUEST, "invalid_lease", "leaseGeneration must be positive")
        workload = request["workload"]
        if not isinstance(workload, dict) or "kind" not in workload:
            raise ProtocolError(HTTPStatus.BAD_REQUEST, "invalid_workload", "workload fields are invalid")
        if workload["kind"] == SYNTHETIC_CAPABILITY:
            self._validate_synthetic(workload)
        elif workload["kind"] == PROJECT_CAPABILITY:
            self._validate_project(request, workload)
        else:
            raise ProtocolError(HTTPStatus.BAD_REQUEST, "unsupported_workload", "workload kind is unsupported")

    def _validate_synthetic(self, workload: dict[str, Any]) -> None:
        if set(workload) != SYNTHETIC_WORKLOAD_KEYS:
            raise ProtocolError(HTTPStatus.BAD_REQUEST, "invalid_workload", "synthetic workload fields are invalid")
        if not isinstance(workload["message"], str) or not (1 <= len(workload["message"]) <= 2000):
            raise ProtocolError(HTTPStatus.BAD_REQUEST, "invalid_message", "message length is invalid")
        if not isinstance(workload["durationMs"], int) or not (100 <= workload["durationMs"] <= 300_000):
            raise ProtocolError(HTTPStatus.BAD_REQUEST, "invalid_duration", "durationMs is outside the bounded policy")
        if not isinstance(workload["steps"], int) or not (1 <= workload["steps"] <= 100):
            raise ProtocolError(HTTPStatus.BAD_REQUEST, "invalid_steps", "steps is outside the bounded policy")

    def _validate_project(self, request: dict[str, Any], workload: dict[str, Any]) -> None:
        if set(workload) != PROJECT_WORKLOAD_KEYS:
            raise ProtocolError(HTTPStatus.BAD_REQUEST, "invalid_workload", "project workload fields are invalid")
        route = self._project_route(workload.get("projectId"))
        if route is None:
            raise ProtocolError(
                HTTPStatus.FORBIDDEN,
                "project_ownership_conflict",
                "project identity is not allowlisted",
            )
        if not self._project_execution_enabled(route):
            raise ProtocolError(HTTPStatus.FORBIDDEN, "project_disabled", "project workload is disabled")
        config = self._read_project_config(route, require_execution=True)
        if config["commit"] != self._observe_project_commit(route):
            raise ProtocolError(
                HTTPStatus.CONFLICT,
                "canonical_source_moved",
                "worker mirror canonical source moved before admission",
            )
        exact = {
            **route["identity"],
            **route["instructions"],
            "commit": config["commit"],
        }
        if any(workload.get(key) != value for key, value in exact.items()):
            raise ProtocolError(HTTPStatus.FORBIDDEN, "project_ownership_conflict", "project identity is not allowlisted")
        if not isinstance(workload["message"], str) or not (1 <= len(workload["message"]) <= 20_000):
            raise ProtocolError(HTTPStatus.BAD_REQUEST, "invalid_message", "message length is invalid")
        thread_id = workload["threadId"]
        if thread_id is not None:
            try:
                uuid.UUID(thread_id)
            except (ValueError, TypeError, AttributeError):
                raise ProtocolError(HTTPStatus.BAD_REQUEST, "invalid_thread", "threadId must be null or a UUID")
        if request["workspaceIdentity"] not in config["workspaces"]:
            raise ProtocolError(
                HTTPStatus.FORBIDDEN,
                "workspace_ownership_conflict",
                "workspace identity is not persistently registered",
            )
        record = config["workspaces"][request["workspaceIdentity"]]
        record_keys = {"sessionId", "worktree", "allocationSha256"}
        if workload["projectId"] == PROJECT_ID:
            record_keys.add("canonicalCommit")
        if (
            not isinstance(record, dict)
            or set(record) != record_keys
            or record["sessionId"] != request["sessionId"]
            or (
                workload["projectId"] == PROJECT_ID
                and record["canonicalCommit"] != workload["commit"]
            )
        ):
            raise ProtocolError(
                HTTPStatus.FORBIDDEN,
                "workspace_ownership_conflict",
                "persisted workspace ownership is incomplete or conflicting",
            )

    def _project_route(self, project_id: Any) -> dict[str, Any] | None:
        if project_id == PROJECT_ID:
            return {
                "config": self.project_config,
                "runner": self.project_runner,
                "mirror": PROJECT_MIRROR,
                "identity": {
                    "projectId": PROJECT_ID,
                    "repository": PROJECT_REPOSITORY,
                    "branch": PROJECT_BRANCH,
                    "manifestSha256": PROJECT_MANIFEST_SHA256,
                },
                "instructions": {
                    "instructionBundleRevision": INSTRUCTION_BUNDLE_REVISION,
                    "instructionBundleSha256": ATENEA_INSTRUCTION_BUNDLE_SHA256,
                    "platformInstructionSha256": PLATFORM_INSTRUCTION_SHA256,
                    "projectInstructionPath": PROJECT_INSTRUCTION_PATH,
                    "projectInstructionSha256": ATENEA_PROJECT_INSTRUCTION_SHA256,
                },
            }
        if project_id == BEAUTIPS_PROJECT_ID:
            return {
                "config": self.beautips_project_config,
                "runner": self.beautips_project_runner,
                "mirror": None,
                "identity": {
                    "projectId": BEAUTIPS_PROJECT_ID,
                    "repository": BEAUTIPS_PROJECT_REPOSITORY,
                    "branch": BEAUTIPS_PROJECT_BRANCH,
                    "commit": BEAUTIPS_PROJECT_COMMIT,
                    "manifestSha256": BEAUTIPS_PROJECT_MANIFEST_SHA256,
                },
                "instructions": {
                    "instructionBundleRevision": INSTRUCTION_BUNDLE_REVISION,
                    "instructionBundleSha256": BEAUTIPS_INSTRUCTION_BUNDLE_SHA256,
                    "platformInstructionSha256": PLATFORM_INSTRUCTION_SHA256,
                    "projectInstructionPath": PROJECT_INSTRUCTION_PATH,
                    "projectInstructionSha256": BEAUTIPS_PROJECT_INSTRUCTION_SHA256,
                },
            }
        return None

    def _read_project_config(
        self, route: dict[str, Any], require_execution: bool = False
    ) -> dict[str, Any]:
        project_config = route["config"]
        project_runner = route["runner"]
        if project_config is None:
            raise ProtocolError(HTTPStatus.FORBIDDEN, "project_disabled", "project workload is disabled")
        try:
            stat = project_config.stat()
            parsed = json.loads(project_config.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            raise ProtocolError(HTTPStatus.FORBIDDEN, "project_disabled", "project configuration is unavailable")
        if stat.st_uid != self.project_config_uid or stat.st_mode & 0o022:
            raise ProtocolError(HTTPStatus.FORBIDDEN, "project_disabled", "project configuration ownership is unsafe")
        required = {
            "schemaVersion", "selectionEnabled", "executionEnabled",
            "projectId", "repository", "branch",
            "commit", "manifestSha256", "runner", "workspaces",
        }
        exact = {"schemaVersion": PROJECT_CAPABILITY, **route["identity"]}
        if (
            not isinstance(parsed, dict)
            or set(parsed) != required
            or any(parsed.get(key) != value for key, value in exact.items())
            or not isinstance(parsed.get("commit"), str)
            or COMMIT_PATTERN.fullmatch(parsed["commit"]) is None
            or parsed.get("selectionEnabled") is not True
            or not isinstance(parsed.get("executionEnabled"), bool)
            or (require_execution and parsed.get("executionEnabled") is not True)
            or parsed.get("runner") != str(project_runner)
            or not isinstance(parsed.get("workspaces"), dict)
        ):
            raise ProtocolError(HTTPStatus.FORBIDDEN, "project_disabled", "project configuration is not exact")
        return parsed

    def _observe_project_commit(self, route: dict[str, Any]) -> str:
        if route.get("mirror") is None:
            return self._read_project_config(route)["commit"]
        reference = "refs/remotes/origin/" + route["identity"]["branch"]
        try:
            completed = subprocess.run(
                [
                    "git", "--git-dir", str(route["mirror"]),
                    "rev-parse", "--verify", reference + "^{commit}",
                ],
                stdin=subprocess.DEVNULL,
                stdout=subprocess.PIPE,
                stderr=subprocess.DEVNULL,
                text=True,
                timeout=15,
                check=True,
            )
        except (OSError, subprocess.SubprocessError):
            raise ProtocolError(
                HTTPStatus.CONFLICT,
                "canonical_source_unavailable",
                "worker mirror canonical source is unavailable",
            )
        commit = completed.stdout.strip()
        if COMMIT_PATTERN.fullmatch(commit) is None:
            raise ProtocolError(
                HTTPStatus.CONFLICT,
                "canonical_source_ambiguous",
                "worker mirror canonical source is ambiguous",
            )
        return commit

    def _project_selection_enabled(self) -> bool:
        for project_id in (PROJECT_ID, BEAUTIPS_PROJECT_ID):
            route = self._project_route(project_id)
            try:
                self._read_project_config(route)
                if route["runner"] is not None and route["runner"].is_file():
                    return True
            except ProtocolError:
                continue
        return False

    def _project_execution_enabled(self, route: dict[str, Any]) -> bool:
        try:
            self._read_project_config(route, require_execution=True)
            return route["runner"] is not None and route["runner"].is_file()
        except ProtocolError:
            return False

    def _schedule_loop(self) -> None:
        while not self.stop_event.is_set():
            self.wakeup.wait(timeout=0.25)
            self.wakeup.clear()
            with self.lock:
                active_normal = sum(
                    1 for item in self.executions.values()
                    if item["status"] in {"STARTING", "RUNNING"} and item["dispatchId"] in self.threads
                )
                active_heavy = sum(
                    1 for item in self.executions.values()
                    if item["status"] in {"STARTING", "RUNNING"}
                    and item["dispatchId"] in self.threads
                    and item["workloadClass"] == "HEAVY"
                )
                candidates = sorted(
                    (
                        item for item in self.executions.values()
                        if item["status"] in {"QUEUED", "RECONCILING", "CANCELLING"}
                        and item["dispatchId"] not in self.threads
                    ),
                    key=lambda item: (item["createdAt"], item["dispatchId"]),
                )
                for execution in candidates:
                    if execution["cancelRequested"]:
                        self._finish_cancelled(execution)
                        continue
                    if execution["reconcileRequired"] and execution["workload"]["kind"] == PROJECT_CAPABILITY:
                        execution["status"] = "FAILED"
                        execution["statusReason"] = (
                            "Restart reconciliation refused to duplicate an uncertain Codex turn"
                        )
                        execution["finishedAt"] = utc_now()
                        execution["revision"] += 1
                        execution["updatedAt"] = execution["finishedAt"]
                        self._persist()
                        continue
                    if active_normal >= self.normal_capacity:
                        break
                    if execution["workloadClass"] == "HEAVY" and active_heavy >= self.heavy_capacity:
                        continue
                    execution["status"] = "STARTING"
                    execution["statusReason"] = "Worker permit admitted"
                    execution["revision"] += 1
                    execution["updatedAt"] = utc_now()
                    self._persist()
                    thread = threading.Thread(
                        target=self._execute,
                        args=(execution["dispatchId"],),
                        name=f"agent-run-{execution['executionId']}",
                        daemon=True,
                    )
                    self.threads[execution["dispatchId"]] = thread
                    active_normal += 1
                    if execution["workloadClass"] == "HEAVY":
                        active_heavy += 1
                    thread.start()

    def _execute(self, dispatch_id: str) -> None:
        try:
            with self.lock:
                execution = self.executions[dispatch_id]
                if execution["cancelRequested"]:
                    self._finish_cancelled(execution)
                    return
                execution["status"] = "RUNNING"
                execution["statusReason"] = (
                    "Exact project Codex execution running"
                    if execution["workload"]["kind"] == PROJECT_CAPABILITY
                    else "Synthetic execution running"
                )
                execution["startedAt"] = execution["startedAt"] or utc_now()
                execution["revision"] += 1
                execution["updatedAt"] = utc_now()
                self._persist()
                if execution["workload"]["kind"] == PROJECT_CAPABILITY:
                    request = {
                        "dispatchId": execution["dispatchId"],
                        "executionId": execution["executionId"],
                        "sessionId": execution["sessionId"],
                        "workspaceIdentity": execution["workspaceIdentity"],
                        "workload": execution["workload"],
                    }
                else:
                    request = None
                    duration = execution["workload"]["durationMs"] / 1000
                    steps = execution["workload"]["steps"]
                    completed_steps = min(steps, int(execution["progress"] * steps / 100))

            if request is not None:
                self._execute_project(dispatch_id, request)
                return
            delay = duration / steps
            for step in range(completed_steps + 1, steps + 1):
                if self.stop_event.wait(delay):
                    return
                with self.lock:
                    execution = self.executions[dispatch_id]
                    if execution["cancelRequested"]:
                        self._finish_cancelled(execution)
                        return
                    execution["progress"] = int(step * 100 / steps)
                    execution["revision"] += 1
                    execution["updatedAt"] = utc_now()
                    self._persist()

            with self.lock:
                execution = self.executions[dispatch_id]
                workspace_digest = hashlib.sha256(execution["workspaceIdentity"].encode()).hexdigest()[:16]
                execution["status"] = "SUCCEEDED"
                execution["statusReason"] = "Synthetic execution completed"
                execution["result"] = {
                    "threadId": f"synthetic-thread-{workspace_digest}",
                    "turnId": execution["executionId"],
                    "finalAnswer": f"Synthetic remote response: {execution['workload']['message']}",
                    "outputSummary": "synthetic-routing-v1 completed",
                }
                execution["finishedAt"] = utc_now()
                execution["revision"] += 1
                execution["updatedAt"] = execution["finishedAt"]
                self._persist()
        finally:
            with self.lock:
                self.threads.pop(dispatch_id, None)
                self.wakeup.set()

    def _execute_project(self, dispatch_id: str, request: dict[str, Any]) -> None:
        route = self._project_route(request["workload"]["projectId"])
        if route is None or route["runner"] is None or route["config"] is None:
            self._finish_project(
                dispatch_id, "FAILED", "Project runner rejected or failed the exact execution", None
            )
            return
        command = [
            *self.privilege_command,
            str(route["runner"]),
            "--config",
            str(route["config"]),
        ]
        process = subprocess.Popen(
            command,
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            start_new_session=True,
        )
        with self.lock:
            self.processes[dispatch_id] = process
            execution = self.executions[dispatch_id]
            execution["progress"] = 10
            execution["revision"] += 1
            execution["updatedAt"] = utc_now()
            self._persist()
        try:
            stdout, stderr = process.communicate(
                json.dumps(request, sort_keys=True, separators=(",", ":")),
                timeout=self.project_timeout,
            )
        except subprocess.TimeoutExpired:
            process.terminate()
            try:
                stdout, stderr = process.communicate(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill()
                stdout, stderr = process.communicate(timeout=5)
            self._finish_project(dispatch_id, "FAILED", "Bounded project execution timed out", None)
            return
        finally:
            with self.lock:
                self.processes.pop(dispatch_id, None)
        with self.lock:
            cancelled = self.executions[dispatch_id]["cancelRequested"]
        if cancelled:
            with self.lock:
                self._finish_cancelled(self.executions[dispatch_id])
            return
        if process.returncode != 0:
            reason = "Project runner rejected or failed the exact execution"
            allowed_runner_reasons = (
                "project configuration rejected",
                "workspace ownership rejected",
                "worktree fingerprint rejected",
                "Codex execution failed: filesystem boundary",
                "Codex execution failed: authentication unavailable",
                "Codex execution failed: CLI contract",
                "Codex execution failed: network unavailable",
                "Codex execution failed: thread persistence unavailable",
                "Codex execution failed: unclassified",
                "Project runner internal exception: AttributeError",
                "Project runner internal exception: FileNotFoundError",
                "Project runner internal exception: OSError",
                "Project runner internal exception: PermissionError",
                "Project runner internal exception: TypeError",
                "Project runner internal exception: UnboundLocalError",
                "Project runner internal exception: ValueError",
                "Project runner internal exception: Other",
            )
            sanitized_runner_reason = next(
                (candidate for candidate in allowed_runner_reasons if candidate in stderr),
                None,
            )
            if sanitized_runner_reason is None:
                if "Traceback (most recent call last)" in stderr:
                    sanitized_runner_reason = "Project runner internal exception"
                elif "sudo:" in stderr.lower():
                    sanitized_runner_reason = "Project runner privilege boundary failed"
                elif not stderr.strip():
                    sanitized_runner_reason = "Project runner failed without diagnostic output"
            if sanitized_runner_reason is not None:
                reason = sanitized_runner_reason
            self._finish_project(dispatch_id, "FAILED", reason, None)
            return
        try:
            result = json.loads(stdout)
        except json.JSONDecodeError:
            self._finish_project(dispatch_id, "FAILED", "Project runner returned invalid output", None)
            return
        if (
            not isinstance(result, dict)
            or set(result) != {"threadId", "turnId", "finalAnswer", "outputSummary"}
            or not all(isinstance(result[key], str) and result[key] for key in result)
        ):
            self._finish_project(dispatch_id, "FAILED", "Project runner returned invalid output", None)
            return
        self._finish_project(dispatch_id, "SUCCEEDED", "Exact project Codex execution completed", result)

    def _finish_project(
        self, dispatch_id: str, status: str, reason: str, result: dict[str, str] | None
    ) -> None:
        with self.lock:
            execution = self.executions[dispatch_id]
            execution["status"] = status
            execution["statusReason"] = reason
            execution["result"] = result
            execution["progress"] = 100 if status == "SUCCEEDED" else execution["progress"]
            execution["finishedAt"] = utc_now()
            execution["revision"] += 1
            execution["updatedAt"] = execution["finishedAt"]
            self._persist()

    def _finish_cancelled(self, execution: dict[str, Any]) -> None:
        execution["status"] = "CANCELLED"
        execution["statusReason"] = "Exact execution cancelled"
        execution["finishedAt"] = utc_now()
        execution["revision"] += 1
        execution["updatedAt"] = execution["finishedAt"]
        self._persist()

    def _public(self, execution: dict[str, Any]) -> dict[str, Any]:
        return {
            key: execution.get(key)
            for key in (
                "dispatchId", "executionId", "sessionId", "workspaceIdentity",
                "workloadClass", "leaseGeneration", "status", "statusReason",
                "revision", "progress", "createdAt", "updatedAt", "startedAt",
                "finishedAt", "result",
            )
        }


class AgentRunServer(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self, address: tuple[str, int], state: WorkerState, token: str):
        self.state = state
        self.token = token
        super().__init__(address, AgentRunHandler)


class AgentRunHandler(BaseHTTPRequestHandler):
    server: AgentRunServer

    def log_message(self, message: str, *args: Any) -> None:
        print(json.dumps({"at": utc_now(), "remote": self.client_address[0], "message": message % args}), flush=True)

    def do_GET(self) -> None:
        try:
            self._authenticate()
            path = urlparse(self.path).path
            if path == "/v1/health":
                self._write(HTTPStatus.OK, self.server.state.health())
                return
            parts = path.strip("/").split("/")
            if len(parts) == 3 and parts[:2] == ["v1", "executions"]:
                self._write(HTTPStatus.OK, self.server.state.get(parts[2]))
                return
            raise ProtocolError(HTTPStatus.NOT_FOUND, "not_found", "route does not exist")
        except ProtocolError as error:
            self._write_error(error)

    def do_POST(self) -> None:
        try:
            self._authenticate()
            body = self._body()
            path = urlparse(self.path).path
            if path == "/v1/executions":
                execution, created = self.server.state.create(body)
                self._write(HTTPStatus.CREATED if created else HTTPStatus.OK, execution)
                return
            if path == "/v1/project-workspaces/ensure":
                self._write(HTTPStatus.OK, self.server.state.ensure_workspace(body))
                return
            if path == "/v1/project-workspaces/draft-fingerprint":
                self._write(HTTPStatus.OK, self.server.state.fingerprint_retained_draft(body))
                return
            if path == "/v1/project-workspaces/source-tree-fingerprint":
                self._write(HTTPStatus.OK, self.server.state.fingerprint_source_tree(body))
                return
            if path == "/v1/project-workspaces/validations":
                self._write(HTTPStatus.OK, self.server.state.run_validation(body))
                return
            if path == "/v1/project-workspaces/repository-roles/ensure":
                self._write(HTTPStatus.OK, self.server.state.ensure_repository_roles(body))
                return
            parts = path.strip("/").split("/")
            if len(parts) == 4 and parts[:2] == ["v1", "executions"]:
                if parts[3] == "lease":
                    self._write(HTTPStatus.OK, self.server.state.renew(parts[2], body))
                    return
                if parts[3] == "cancel":
                    self._write(HTTPStatus.OK, self.server.state.cancel(parts[2], body))
                    return
            raise ProtocolError(HTTPStatus.NOT_FOUND, "not_found", "route does not exist")
        except ProtocolError as error:
            self._write_error(error)

    def _authenticate(self) -> None:
        supplied = self.headers.get("Authorization", "")
        expected = f"Bearer {self.server.token}"
        if not hmac.compare_digest(supplied, expected):
            raise ProtocolError(HTTPStatus.UNAUTHORIZED, "unauthorized", "valid worker credential required")

    def _body(self) -> dict[str, Any]:
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            raise ProtocolError(HTTPStatus.BAD_REQUEST, "invalid_length", "Content-Length is invalid")
        if length < 2 or length > 65_536:
            raise ProtocolError(HTTPStatus.BAD_REQUEST, "invalid_length", "request body size is invalid")
        try:
            parsed = json.loads(self.rfile.read(length))
        except (json.JSONDecodeError, UnicodeDecodeError):
            raise ProtocolError(HTTPStatus.BAD_REQUEST, "invalid_json", "request body is not valid JSON")
        if not isinstance(parsed, dict):
            raise ProtocolError(HTTPStatus.BAD_REQUEST, "invalid_json", "request body must be an object")
        return parsed

    def _write_error(self, error: ProtocolError) -> None:
        self._write(error.status, {"error": error.code, "message": str(error)})

    def _write(self, status: int, payload: dict[str, Any]) -> None:
        encoded = json.dumps(payload, sort_keys=True, separators=(",", ":")).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(encoded)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(encoded)


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
    parser.add_argument("--port", required=True, type=int)
    parser.add_argument("--worker-id", required=True)
    parser.add_argument("--state-dir", required=True, type=Path)
    parser.add_argument("--token-file", required=True, type=Path)
    parser.add_argument("--normal-capacity", type=int, default=4)
    parser.add_argument("--heavy-capacity", type=int, default=2)
    parser.add_argument("--project-config", type=Path)
    parser.add_argument("--project-runner", type=Path)
    parser.add_argument(
        "--project-workspace-activator",
        type=Path,
        default=Path("/usr/local/libexec/atenea/atenea-workspace-activation-v1.sh"),
    )
    parser.add_argument("--beautips-project-config", type=Path)
    parser.add_argument("--beautips-project-runner", type=Path)
    parser.add_argument(
        "--beautips-workspace-activator",
        type=Path,
        default=Path("/usr/local/libexec/atenea/beautips-workspace-activation-v1.sh"),
    )
    parser.add_argument(
        "--project-validation-mediator",
        type=Path,
        default=Path("/usr/local/libexec/atenea/atenea-validation-v1.sh"),
    )
    parser.add_argument(
        "--repository-role-mediator",
        type=Path,
        default=Path("/usr/local/libexec/atenea/atenea-multi-repository-v1.sh"),
    )
    parser.add_argument("--project-timeout", type=int, default=1800)
    args = parser.parse_args()
    if not (1 <= args.port <= 65535):
        raise SystemExit("port is outside valid range")
    if not (1 <= args.heavy_capacity <= args.normal_capacity <= 64):
        raise SystemExit("capacity is outside policy")

    if not (30 <= args.project_timeout <= 3600):
        raise SystemExit("project timeout is outside policy")
    state = WorkerState(
        args.state_dir,
        args.worker_id,
        args.normal_capacity,
        args.heavy_capacity,
        args.project_config,
        args.project_runner,
        args.project_timeout,
        project_workspace_activator=args.project_workspace_activator,
        beautips_project_config=args.beautips_project_config,
        beautips_project_runner=args.beautips_project_runner,
        beautips_workspace_activator=args.beautips_workspace_activator,
        project_validation_mediator=args.project_validation_mediator,
        repository_role_mediator=args.repository_role_mediator,
    )
    server = AgentRunServer((args.bind, args.port), state, read_token(args.token_file))
    state.start()

    def shutdown(_signum: int, _frame: Any) -> None:
        threading.Thread(target=server.shutdown, daemon=True).start()

    signal.signal(signal.SIGTERM, shutdown)
    signal.signal(signal.SIGINT, shutdown)
    try:
        server.serve_forever(poll_interval=0.25)
    finally:
        state.stop()
        server.server_close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
