#!/usr/bin/env python3
"""Private, durable AgentRun worker protocol with exact project opt-in."""

from __future__ import annotations

import argparse
import hashlib
import hmac
import json
import os
import signal
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
    "manifestSha256", "message", "threadId",
}
PROJECT_ID = "atenea"
PROJECT_REPOSITORY = "https://github.com/jlnieto/atenea.git"
PROJECT_BRANCH = "feature/actualizar-conversacion-en-web"
PROJECT_COMMIT = "b605c8d5b063e7321edd60fec2265ec7ddb84ea9"
PROJECT_MANIFEST_SHA256 = "3b26e1899a06993bee69ac596e7cb69b6200a37d063d98203ad308058c91bfa3"
BEAUTIPS_PROJECT_ID = "beautips"
BEAUTIPS_PROJECT_REPOSITORY = "https://github.com/jlnieto/beautips.git"
BEAUTIPS_PROJECT_BRANCH = "main"
BEAUTIPS_PROJECT_COMMIT = "e9e0b3c319c518363d4135f5378ebbddced96dfb"
BEAUTIPS_PROJECT_MANIFEST_SHA256 = (
    "365f1c66c51c9018c2c6f48deddbaa619b4588cae2dd463dcd916cde884e2e82"
)


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
        beautips_project_config: Path | None = None,
        beautips_project_runner: Path | None = None,
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
        self.beautips_project_config = beautips_project_config
        self.beautips_project_runner = beautips_project_runner
        self.lock = threading.RLock()
        self.wakeup = threading.Event()
        self.stop_event = threading.Event()
        self.executions: dict[str, dict[str, Any]] = {}
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
        payload = {"protocol": PROTOCOL, "workerId": self.worker_id, "executions": self.executions}
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
        exact = route["identity"]
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
        config = self._read_project_config(route, require_execution=True)
        if request["workspaceIdentity"] not in config["workspaces"]:
            raise ProtocolError(
                HTTPStatus.FORBIDDEN,
                "workspace_ownership_conflict",
                "workspace identity is not persistently registered",
            )
        record = config["workspaces"][request["workspaceIdentity"]]
        if (
            not isinstance(record, dict)
            or set(record) != {"sessionId", "worktree", "allocationSha256"}
            or record["sessionId"] != request["sessionId"]
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
                "identity": {
                    "projectId": PROJECT_ID,
                    "repository": PROJECT_REPOSITORY,
                    "branch": PROJECT_BRANCH,
                    "commit": PROJECT_COMMIT,
                    "manifestSha256": PROJECT_MANIFEST_SHA256,
                },
            }
        if project_id == BEAUTIPS_PROJECT_ID:
            return {
                "config": self.beautips_project_config,
                "runner": self.beautips_project_runner,
                "identity": {
                    "projectId": BEAUTIPS_PROJECT_ID,
                    "repository": BEAUTIPS_PROJECT_REPOSITORY,
                    "branch": BEAUTIPS_PROJECT_BRANCH,
                    "commit": BEAUTIPS_PROJECT_COMMIT,
                    "manifestSha256": BEAUTIPS_PROJECT_MANIFEST_SHA256,
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
            or parsed.get("selectionEnabled") is not True
            or not isinstance(parsed.get("executionEnabled"), bool)
            or (require_execution and parsed.get("executionEnabled") is not True)
            or parsed.get("runner") != str(project_runner)
            or not isinstance(parsed.get("workspaces"), dict)
        ):
            raise ProtocolError(HTTPStatus.FORBIDDEN, "project_disabled", "project configuration is not exact")
        return parsed

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
    parser.add_argument("--beautips-project-config", type=Path)
    parser.add_argument("--beautips-project-runner", type=Path)
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
        beautips_project_config=args.beautips_project_config,
        beautips_project_runner=args.beautips_project_runner,
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
