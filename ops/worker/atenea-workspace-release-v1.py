#!/usr/bin/env python3
"""Pure, fail-closed Atenea workspace-release preflight contract v1.

This module deliberately exposes no command-line or HTTP entry point.  The
installed finalizer will build the projection from fixed worker-owned roots;
this stage only proves that the whole projection is exact before any journal
or mutation can exist.
"""

from __future__ import annotations

import hashlib
import json
import re
import uuid
from typing import Any, Callable


SCHEMA = "atenea-workspace-release-preflight-v1"
PROJECT_ID = "atenea"
WORKER_ID = "ax42-01"
REPOSITORY = "https://github.com/jlnieto/atenea.git"
BRANCH = "main"
MANIFEST_SHA256 = "327a0c521017109d7c0067a11e7d8c3ad2079de4ea78d28296848f9de39c164b"
SHA256 = re.compile(r"^[0-9a-f]{64}$")
COMMIT = re.compile(r"^[0-9a-f]{40}$")
UUID_PATTERN = re.compile(
    r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$"
)
FORBIDDEN_IDENTITY = re.compile(r"(?:^|[-_.:/])(beautips|prod|production)(?:$|[-_.:/])")

PROJECTION_KEYS = {
    "schemaVersion", "requestFingerprintSha256", "sessionId",
    "workspaceIdentity", "projectId", "workerId", "workspace", "registry",
    "admission", "allocation", "runtimeContainers", "runtimeNetworks",
    "sessionImages", "previewResources", "listeners", "brokerResources",
    "materializations", "browserProcesses", "valuesExposed",
}
OWNERSHIP_KEYS = {
    "workerId", "sessionId", "runtimeId", "projectId",
    "allocationFingerprintSha256", "labels", "productionLike", "ambiguous",
}
CANDIDATE_KEYS = {"resourceId", "ownership", "details"}


class PreflightRejected(Exception):
    """Safe deterministic rejection raised before the first mutation."""

    def __init__(self, code: str = "WORKSPACE_RELEASE_PREFLIGHT_REJECTED"):
        super().__init__(code)
        self.code = code


def canonical_hash(value: Any) -> str:
    encoded = json.dumps(value, sort_keys=True, separators=(",", ":")).encode()
    return hashlib.sha256(encoded).hexdigest()


def _reject() -> None:
    raise PreflightRejected()


def _exact_dict(value: Any, keys: set[str]) -> dict[str, Any]:
    if not isinstance(value, dict) or set(value) != keys:
        _reject()
    return value


def _canonical_uuid(value: Any) -> str:
    try:
        canonical = str(uuid.UUID(value))
    except (ValueError, TypeError, AttributeError):
        _reject()
    if canonical != value or UUID_PATTERN.fullmatch(canonical) is None:
        _reject()
    return canonical


def _safe_identity(value: Any) -> str:
    if (
        not isinstance(value, str)
        or not value
        or len(value) > 512
        or FORBIDDEN_IDENTITY.search(value.lower()) is not None
    ):
        _reject()
    return value


def _request_identity(request: Any) -> dict[str, str]:
    expected_keys = {
        "operationId", "idempotencyKey", "sessionId", "workspaceIdentity",
        "projectId", "repository", "branch", "commit", "manifestSha256",
        "workspaceBranch",
    }
    request = _exact_dict(request, expected_keys)
    session = _canonical_uuid(request.get("sessionId"))
    _canonical_uuid(request.get("operationId"))
    _canonical_uuid(request.get("idempotencyKey"))
    exact = {
        "workspaceIdentity": f"remote:{WORKER_ID}:work-session:{session}",
        "projectId": PROJECT_ID,
        "repository": REPOSITORY,
        "branch": BRANCH,
        "manifestSha256": MANIFEST_SHA256,
        "workspaceBranch": f"atenea/session-{session}",
    }
    if any(request.get(key) != value for key, value in exact.items()):
        _reject()
    if not isinstance(request.get("commit"), str) or COMMIT.fullmatch(request["commit"]) is None:
        _reject()
    return dict(request)


def _validate_authoritative_roots(
    request: dict[str, str], projection: dict[str, Any]
) -> tuple[str, str, set[int]]:
    session = request["sessionId"]
    runtime = f"ws-{session.replace('-', '')}"
    session_root = f"/srv/atenea/workspaces/sessions/{session}"
    worktree = f"{session_root}/{PROJECT_ID}"
    allocation_path = f"{session_root}/runtime-allocation-v1.json"
    admission_path = f"/srv/atenea/worker/runtime-admission-v1/records/{session}.json"

    workspace = _exact_dict(projection.get("workspace"), {
        "recordPath", "worktreePath", "sessionId", "projectId", "repository",
        "baseBranch", "workspaceBranch", "canonicalCommit", "manifestSha256",
        "state", "owner", "group", "mode", "symlink",
    })
    if workspace != {
        "recordPath": f"{session_root}/workspace-v1.json",
        "worktreePath": worktree,
        "sessionId": session,
        "projectId": PROJECT_ID,
        "repository": REPOSITORY,
        "baseBranch": BRANCH,
        "workspaceBranch": request["workspaceBranch"],
        "canonicalCommit": request["commit"],
        "manifestSha256": MANIFEST_SHA256,
        "state": "ready",
        "owner": "atenea-worker",
        "group": "atenea",
        "mode": 640,
        "symlink": False,
    }:
        _reject()

    allocation = _exact_dict(projection.get("allocation"), {
        "recordPath", "fingerprintSha256", "sessionId", "projectId",
        "runtimeId", "worktreePath", "manifestRelativePath", "slot",
        "heavyPermit", "state", "owner", "group", "mode", "symlink",
        "allocatedPorts",
    })
    allocation_fingerprint = allocation.get("fingerprintSha256")
    slot = allocation.get("slot")
    heavy = allocation.get("heavyPermit")
    ports = allocation.get("allocatedPorts")
    if (
        allocation.get("recordPath") != allocation_path
        or not isinstance(allocation_fingerprint, str)
        or SHA256.fullmatch(allocation_fingerprint) is None
        or allocation.get("sessionId") != session
        or allocation.get("projectId") != PROJECT_ID
        or allocation.get("runtimeId") != runtime
        or allocation.get("worktreePath") != worktree
        or allocation.get("manifestRelativePath") != "ops/atenea-runtime.json"
        or slot not in {"slot1", "slot2", "slot3", "slot4"}
        or heavy not in {"heavy1", "heavy2"}
        or allocation.get("state") != "allocated"
        or allocation.get("owner") != "atenea-worker"
        or allocation.get("group") != "atenea"
        or allocation.get("mode") not in {600, 640}
        or allocation.get("symlink") is not False
        or not isinstance(ports, list)
        or not ports
    ):
        _reject()
    loopback_ports: set[int] = set()
    port_names: set[str] = set()
    for port in ports:
        port = _exact_dict(port, {
            "name", "internalPort", "protocol", "bindAddress", "loopbackPort"
        })
        if (
            not isinstance(port.get("name"), str)
            or re.fullmatch(r"[a-z][a-z0-9-]{1,62}", port["name"]) is None
            or port["name"] in port_names
            or type(port.get("internalPort")) is not int
            or not 1 <= port["internalPort"] <= 65535
            or port.get("protocol") not in {"http", "tcp"}
            or port.get("bindAddress") != "127.0.0.1"
            or type(port.get("loopbackPort")) is not int
            or not 1024 <= port["loopbackPort"] <= 65535
            or port["loopbackPort"] in loopback_ports
        ):
            _reject()
        port_names.add(port["name"])
        loopback_ports.add(port["loopbackPort"])

    registry = _exact_dict(projection.get("registry"), {
        "configurationPath", "selectionEnabled", "executionEnabled",
        "workspaceIdentity", "sessionId", "worktreePath",
        "allocationFingerprintSha256", "canonicalCommit", "projectId",
        "repository", "owner", "group", "mode", "symlink",
    })
    if registry != {
        "configurationPath": "/etc/atenea-worker/project-codex-v1.json",
        "selectionEnabled": True,
        "executionEnabled": True,
        "workspaceIdentity": request["workspaceIdentity"],
        "sessionId": session,
        "worktreePath": worktree,
        "allocationFingerprintSha256": allocation_fingerprint,
        "canonicalCommit": request["commit"],
        "projectId": PROJECT_ID,
        "repository": REPOSITORY,
        "owner": "root",
        "group": "atenea",
        "mode": 640,
        "symlink": False,
    }:
        _reject()

    admission = _exact_dict(projection.get("admission"), {
        "recordPath", "sessionId", "normal", "heavy", "owner", "group",
        "mode", "symlink",
    })
    if admission != {
        "recordPath": admission_path,
        "sessionId": session,
        "normal": {"slot": slot, "state": "held"},
        "heavy": {"permit": heavy, "state": "held"},
        "owner": "atenea-worker",
        "group": "atenea",
        "mode": 640,
        "symlink": False,
    }:
        _reject()
    return runtime, allocation_fingerprint, loopback_ports


def _expected_labels(session: str, runtime: str, service: str) -> dict[str, str]:
    return {
        "com.atenea.engine": "atenea-runtime-engine-v1",
        "com.atenea.session": session,
        "com.atenea.runtime": runtime,
        "com.atenea.project": PROJECT_ID,
        "com.atenea.service": service,
    }


def _candidate(
    value: Any,
    session: str,
    runtime: str,
    allocation_fingerprint: str,
) -> tuple[str, dict[str, Any]]:
    value = _exact_dict(value, CANDIDATE_KEYS)
    resource_id = _safe_identity(value.get("resourceId"))
    ownership = _exact_dict(value.get("ownership"), OWNERSHIP_KEYS)
    details = value.get("details")
    if not isinstance(details, dict):
        _reject()
    service = details.get("service")
    if (
        ownership.get("workerId") != WORKER_ID
        or ownership.get("sessionId") != session
        or ownership.get("runtimeId") != runtime
        or ownership.get("projectId") != PROJECT_ID
        or ownership.get("allocationFingerprintSha256") != allocation_fingerprint
        or ownership.get("productionLike") is not False
        or ownership.get("ambiguous") is not False
        or not isinstance(service, str)
        or ownership.get("labels") != _expected_labels(session, runtime, service)
    ):
        _reject()
    return resource_id, details


def _validate_candidates(
    projection: dict[str, Any],
    session: str,
    runtime: str,
    allocation_fingerprint: str,
    loopback_ports: set[int],
) -> dict[str, int]:
    seen: set[str] = set()
    previews: dict[str, tuple[int, int, str]] = {}
    preview_listener_counts: dict[str, int] = {}
    runtime_listener_ports: set[int] = set()
    rootless_broker_ports: set[int] = set()
    counts: dict[str, int] = {}

    def each(category: str, validator: Callable[[str, dict[str, Any]], None]) -> None:
        values = projection.get(category)
        if not isinstance(values, list):
            _reject()
        for value in values:
            resource_id, details = _candidate(
                value, session, runtime, allocation_fingerprint
            )
            if resource_id in seen:
                _reject()
            validator(resource_id, details)
            seen.add(resource_id)
        counts[category] = len(values)

    container_services = {"db", "codex-app-server", "atenea-dev", "build", "build-db"}

    def runtime_container(resource_id: str, details: dict[str, Any]) -> None:
        if set(details) != {"service", "state"} or details.get("service") not in container_services:
            _reject()
        if resource_id != f"{runtime}-{details['service']}" or details.get("state") not in {"running", "stopped"}:
            _reject()

    each("runtimeContainers", runtime_container)

    def runtime_network(resource_id: str, details: dict[str, Any]) -> None:
        if set(details) != {"service", "internal"}:
            _reject()
        expected = {
            "runtime": f"{runtime}-network",
            "build": f"{runtime}-build-network",
        }
        if expected.get(details.get("service")) != resource_id or details.get("internal") is not True:
            _reject()

    each("runtimeNetworks", runtime_network)

    def session_image(resource_id: str, details: dict[str, Any]) -> None:
        if (
            set(details) != {"service", "imageId"}
            or details.get("service") != "session-image"
            or resource_id != f"{runtime}-image-{details.get('imageId', '')}"
            or not isinstance(details.get("imageId"), str)
            or SHA256.fullmatch(details["imageId"]) is None
        ):
            _reject()

    each("sessionImages", session_image)

    def preview(resource_id: str, details: dict[str, Any]) -> None:
        if set(details) != {"service", "previewId", "ingressPort", "upstreamPort", "state"}:
            _reject()
        preview_id = _canonical_uuid(details.get("previewId"))
        ingress = details.get("ingressPort")
        upstream = details.get("upstreamPort")
        if (
            details.get("service") != "preview"
            or resource_id != f"preview:{preview_id}"
            or type(ingress) is not int
            or not 1024 <= ingress <= 65535
            or upstream not in loopback_ports
            or details.get("state") not in {"READY", "RECONCILING", "STOPPED", "BLOCKED", "EXPIRED"}
            or preview_id in previews
        ):
            _reject()
        previews[preview_id] = (ingress, upstream, details["state"])

    each("previewResources", preview)

    def listener(resource_id: str, details: dict[str, Any]) -> None:
        if set(details) != {"service", "role", "bindAddress", "port", "previewId"}:
            _reject()
        role = details.get("role")
        port = details.get("port")
        preview_id = details.get("previewId")
        if role == "runtime":
            valid = (
                details.get("service") == "runtime-listener"
                and details.get("bindAddress") == "127.0.0.1"
                and port in loopback_ports
                and preview_id is None
            )
            if valid:
                runtime_listener_ports.add(port)
        elif role == "preview":
            valid = (
                details.get("service") == "preview-listener"
                and isinstance(preview_id, str)
                and preview_id in previews
                and previews[preview_id][0] == port
                and previews[preview_id][2] in {"READY", "RECONCILING"}
                and isinstance(details.get("bindAddress"), str)
                and re.fullmatch(r"100\.(?:6[4-9]|[7-9][0-9]|1[01][0-9]|12[0-7])\.[0-9]{1,3}\.[0-9]{1,3}", details["bindAddress"]) is not None
            )
            if valid:
                preview_listener_counts[preview_id] = (
                    preview_listener_counts.get(preview_id, 0) + 1
                )
        else:
            valid = False
        if not valid or resource_id != f"listener:tcp:{details.get('bindAddress')}:{port}":
            _reject()

    each("listeners", listener)
    if any(
        preview_listener_counts.get(preview_id, 0)
        != (1 if state in {"READY", "RECONCILING"} else 0)
        for preview_id, (_ingress, _upstream, state) in previews.items()
    ):
        _reject()

    def broker(resource_id: str, details: dict[str, Any]) -> None:
        if set(details) != {"service", "kind", "port"}:
            _reject()
        if details.get("kind") == "rootless-port":
            valid = (
                details.get("service") == "broker"
                and details.get("port") in loopback_ports
                and resource_id == f"broker:rootless:{details['port']}"
            )
            if valid:
                rootless_broker_ports.add(details["port"])
        elif details.get("kind") == "codex-loopback-proxy":
            valid = (
                details.get("service") == "codex-app-server"
                and details.get("port") in loopback_ports
                and resource_id == f"broker:codex:{runtime}:{details['port']}"
            )
        else:
            valid = False
        if not valid:
            _reject()

    each("brokerResources", broker)
    if rootless_broker_ports != runtime_listener_ports:
        _reject()

    def materialization(resource_id: str, details: dict[str, Any]) -> None:
        if set(details) != {"service", "executionId", "path", "terminal"}:
            _reject()
        execution = _canonical_uuid(details.get("executionId"))
        if (
            details.get("service") != "materialization"
            or resource_id != f"materialization:{execution}"
            or details.get("path") != f"/run/atenea/codex-images/{execution}"
            or details.get("terminal") is not True
        ):
            _reject()

    each("materializations", materialization)

    def browser(resource_id: str, details: dict[str, Any]) -> None:
        if set(details) != {"service", "kind", "operationId", "unit"}:
            _reject()
        operation = _canonical_uuid(details.get("operationId"))
        compact = operation.replace("-", "")
        if details.get("kind") == "codex":
            expected_unit = f"atenea-project-codex-{compact}"
        elif details.get("kind") == "playwright":
            expected_unit = f"atenea-playwright-{compact}"
        else:
            _reject()
        if (
            details.get("service") != "browser"
            or details.get("unit") != expected_unit
            or resource_id != f"browser:{expected_unit}"
        ):
            _reject()

    each("browserProcesses", browser)
    return counts


def validate_release_preflight(
    request: Any,
    projection: Any,
) -> dict[str, Any]:
    """Validate one complete internally-observed projection without writing.

    The caller must hold the shared lifecycle lock.  This function performs no
    filesystem, process, socket, registry or runtime operation and returns only
    a sealed safe summary for the later journal stage.
    """

    exact_request = _request_identity(request)
    projection = _exact_dict(projection, PROJECTION_KEYS)
    if (
        projection.get("schemaVersion") != SCHEMA
        or projection.get("requestFingerprintSha256") != canonical_hash(exact_request)
        or projection.get("sessionId") != exact_request["sessionId"]
        or projection.get("workspaceIdentity") != exact_request["workspaceIdentity"]
        or projection.get("projectId") != PROJECT_ID
        or projection.get("workerId") != WORKER_ID
        or projection.get("valuesExposed") is not False
    ):
        _reject()
    runtime, allocation_fingerprint, loopback_ports = _validate_authoritative_roots(
        exact_request, projection
    )
    counts = _validate_candidates(
        projection,
        exact_request["sessionId"],
        runtime,
        allocation_fingerprint,
        loopback_ports,
    )
    return {
        "schemaVersion": SCHEMA,
        "state": "PREFLIGHT_ACCEPTED",
        "sessionId": exact_request["sessionId"],
        "workspaceIdentity": exact_request["workspaceIdentity"],
        "projectId": PROJECT_ID,
        "workerId": WORKER_ID,
        "runtimeId": runtime,
        "allocationFingerprintSha256": allocation_fingerprint,
        "requestFingerprintSha256": canonical_hash(exact_request),
        "ownershipFingerprintSha256": canonical_hash(projection),
        "candidateCounts": counts,
        "valuesExposed": False,
    }
