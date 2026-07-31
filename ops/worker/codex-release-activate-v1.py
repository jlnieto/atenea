#!/usr/bin/env python3
"""Closed Codex release activation with bounded gates and automatic link restore."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import stat
import subprocess
import sys
import tempfile
import uuid
from pathlib import Path
from typing import Any


REGISTRY_SCHEMA = "codex-release-stage-v1"
RESULT_SCHEMA = "codex-update-activate-v1"
WORKER_ID = "ax42-01"
REQUEST_FIELDS = {
    "operation", "planId", "candidateId", "authorizationId", "idempotencyKey",
}
RESULT_FIELDS = {
    "schemaVersion", "operation", "workerId", "planId", "candidateId",
    "authorizationId", "idempotencyKey", "state", "codexVersion",
    "releaseDigestSha256", "catalogRevision", "schemaComparison",
    "focusedContracts", "workerHealth", "canary", "currentBeforeFingerprint",
    "previousBeforeFingerprint", "currentAfterFingerprint",
    "previousAfterFingerprint", "automaticRestore", "valuesExposed",
}
DIGEST = re.compile(r"^[0-9a-f]{64}$")
VERSION = re.compile(r"^[0-9]+\.[0-9]+\.[0-9]+$")


class ActivationError(RuntimeError):
    pass


def canonical_bytes(value: Any) -> bytes:
    return json.dumps(value, sort_keys=True, separators=(",", ":")).encode("utf-8")


def digest_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def digest_file(path: Path) -> str:
    hasher = hashlib.sha256()
    with path.open("rb") as handle:
        while chunk := handle.read(1024 * 1024):
            hasher.update(chunk)
    return hasher.hexdigest()


def require_uuid(value: Any, field: str) -> str:
    if not isinstance(value, str):
        raise ActivationError(f"{field} must be a UUID")
    try:
        parsed = str(uuid.UUID(value))
    except ValueError as error:
        raise ActivationError(f"{field} must be a UUID") from error
    if parsed != value:
        raise ActivationError(f"{field} must be a canonical UUID")
    return value


def read_request() -> dict[str, str]:
    try:
        request = json.load(sys.stdin)
    except (json.JSONDecodeError, UnicodeDecodeError) as error:
        raise ActivationError("request must be valid JSON") from error
    if not isinstance(request, dict) or set(request) != REQUEST_FIELDS:
        raise ActivationError("exact activation request fields are required")
    if request.get("operation") != "ACTIVATE_CODEX_UPDATE":
        raise ActivationError("activation operation is required")
    return {
        "operation": "ACTIVATE_CODEX_UPDATE",
        **{field: require_uuid(request.get(field), field)
           for field in ("planId", "candidateId", "authorizationId", "idempotencyKey")},
    }


def owned_regular(path: Path, owner_uid: int, description: str) -> os.stat_result:
    try:
        metadata = path.lstat()
    except OSError as error:
        raise ActivationError(f"{description} is unavailable") from error
    if (not stat.S_ISREG(metadata.st_mode) or metadata.st_uid != owner_uid
            or metadata.st_mode & 0o022):
        raise ActivationError(f"{description} ownership is unsafe")
    return metadata


def owned_directory(path: Path, owner_uid: int, description: str) -> None:
    try:
        metadata = path.lstat()
    except OSError as error:
        raise ActivationError(f"{description} is unavailable") from error
    if (not stat.S_ISDIR(metadata.st_mode) or metadata.st_uid != owner_uid
            or metadata.st_mode & 0o022):
        raise ActivationError(f"{description} ownership is unsafe")


def read_registry(path: Path, owner_uid: int) -> dict[str, Any]:
    owned_regular(path, owner_uid, "release registry")
    try:
        registry = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise ActivationError("release registry is invalid") from error
    if not isinstance(registry, dict) or set(registry) != {
        "schemaVersion", "workerId", "candidates"
    }:
        raise ActivationError("release registry fields are invalid")
    if registry["schemaVersion"] != REGISTRY_SCHEMA or registry["workerId"] != WORKER_ID:
        raise ActivationError("release registry identity is invalid")
    return registry


def candidate_for(registry: dict[str, Any], request: dict[str, str]) -> dict[str, str]:
    candidate = registry.get("candidates", {}).get(request["candidateId"])
    fields = {
        "planId", "candidateId", "codexVersion", "releaseDigestSha256",
        "catalogRevision",
    }
    if not isinstance(candidate, dict) or set(candidate) != fields:
        raise ActivationError("candidate is not registered exactly")
    if (candidate["planId"] != request["planId"]
            or candidate["candidateId"] != request["candidateId"]):
        raise ActivationError("candidate ownership does not match the plan")
    if not isinstance(candidate["codexVersion"], str) or not VERSION.fullmatch(candidate["codexVersion"]):
        raise ActivationError("candidate version is invalid")
    if any(not isinstance(candidate[field], str) or not DIGEST.fullmatch(candidate[field])
           for field in ("releaseDigestSha256", "catalogRevision")):
        raise ActivationError("candidate digest identity is invalid")
    return candidate


def link_state(link: Path, releases: Path) -> tuple[str, str, str]:
    if not link.is_symlink():
        raise ActivationError(f"required {link.name} link is unavailable")
    raw = os.readlink(link)
    resolved = link.resolve(strict=True)
    try:
        resolved.relative_to(releases.resolve(strict=True))
    except ValueError as error:
        raise ActivationError(f"{link.name} link escapes the release root") from error
    if not resolved.is_dir() or resolved.is_symlink():
        raise ActivationError(f"{link.name} link target is invalid")
    fingerprint = digest_bytes(canonical_bytes({
        "link": link.name, "target": raw, "release": resolved.name,
    }))
    return raw, resolved.name, fingerprint


def replace_link(link: Path, raw_target: str) -> None:
    temporary = link.parent / ("." + link.name + "." + str(uuid.uuid4()))
    try:
        os.symlink(raw_target, temporary)
        os.replace(temporary, link)
    finally:
        if temporary.is_symlink():
            temporary.unlink()


def find_accepted_stage(operations: Path, request: dict[str, str], candidate: dict[str, str], owner_uid: int) -> None:
    matches = 0
    for operation_path in operations.glob("*.json"):
        owned_regular(operation_path, owner_uid, "stage operation record")
        try:
            operation = json.loads(operation_path.read_text(encoding="utf-8"))
            result = operation["result"]
        except (OSError, json.JSONDecodeError, KeyError, TypeError) as error:
            raise ActivationError("stage operation record is invalid") from error
        if (isinstance(result, dict) and result.get("state") == "STAGED"
                and result.get("planId") == request["planId"]
                and result.get("candidateId") == request["candidateId"]
                and result.get("codexVersion") == candidate["codexVersion"]
                and result.get("releaseDigestSha256") == candidate["releaseDigestSha256"]
                and result.get("catalogRevision") == candidate["catalogRevision"]
                and result.get("releaseVerification") == "PASS"
                and result.get("schemaGeneration") == "PASS"
                and result.get("retention") == "PASS"
                and result.get("linksChanged") is False
                and result.get("valuesExposed") is False):
            matches += 1
    if matches != 1:
        raise ActivationError("exactly one accepted stage operation is required")


def validate_schemas(release: Path, version: str, owner_uid: int) -> None:
    for name in ("app-server.schema.json", "cli.schema.json"):
        schema = release / "generated-schemas" / name
        owned_regular(schema, owner_uid, f"generated {name}")
        try:
            value = json.loads(schema.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as error:
            raise ActivationError(f"generated {name} is invalid") from error
        if not isinstance(value, dict) or value.get("x-codex-version") != version:
            raise ActivationError(f"generated {name} does not match candidate")


def run_gate(release: Path, name: str, owner_uid: int, timeout: int) -> None:
    executable = release / "bin" / name
    metadata = owned_regular(executable, owner_uid, f"{name} gate")
    if not metadata.st_mode & stat.S_IXUSR:
        raise ActivationError(f"{name} gate is not executable")
    try:
        completed = subprocess.run(
            [str(executable)], stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL, env={"PATH": "/usr/bin:/bin", "LANG": "C.UTF-8"},
            timeout=timeout, check=False,
        )
    except (OSError, subprocess.TimeoutExpired) as error:
        raise ActivationError(f"{name} gate failed closed") from error
    if completed.returncode != 0:
        raise ActivationError(f"{name} gate failed closed")


def persist(path: Path, value: dict[str, Any]) -> None:
    descriptor, temporary_name = tempfile.mkstemp(prefix=".activation-", dir=path.parent)
    try:
        with os.fdopen(descriptor, "wb") as handle:
            handle.write(canonical_bytes(value))
            handle.flush()
            os.fsync(handle.fileno())
        os.chmod(temporary_name, 0o600)
        os.replace(temporary_name, path)
    finally:
        if os.path.exists(temporary_name):
            os.unlink(temporary_name)


def activate(args: argparse.Namespace, request: dict[str, str]) -> dict[str, Any]:
    registry = read_registry(args.registry, args.registry_owner_uid)
    candidate = candidate_for(registry, request)
    owned_directory(args.release_root, args.registry_owner_uid, "release root")
    releases = args.release_root / "releases"
    operations = args.release_root / "operations"
    activations = args.release_root / "activations"
    owned_directory(releases, args.release_owner_uid, "release directory")
    owned_directory(operations, args.release_owner_uid, "stage operation directory")
    owned_directory(activations, args.registry_owner_uid, "activation operation directory")

    request_fingerprint = digest_bytes(canonical_bytes(request))
    operation_path = activations / (request["idempotencyKey"] + ".json")
    if operation_path.exists():
        owned_regular(operation_path, args.registry_owner_uid, "activation operation record")
        persisted = json.loads(operation_path.read_text(encoding="utf-8"))
        if (not isinstance(persisted, dict)
                or set(persisted) != {"requestFingerprint", "result"}
                or persisted["requestFingerprint"] != request_fingerprint
                or not isinstance(persisted["result"], dict)
                or set(persisted["result"]) != RESULT_FIELDS):
            raise ActivationError("persisted activation operation is invalid or conflicting")
        return persisted["result"]

    find_accepted_stage(operations, request, candidate, args.release_owner_uid)
    release_name = candidate["codexVersion"] + "-" + candidate["releaseDigestSha256"][:16]
    release = releases / release_name
    owned_directory(release, args.release_owner_uid, "staged candidate release")
    validate_schemas(release, candidate["codexVersion"], args.release_owner_uid)
    current = args.release_root / "current"
    previous = args.release_root / "previous"
    current_raw, current_name, current_before = link_state(current, releases)
    previous_raw, _previous_name, previous_before = link_state(previous, releases)
    if current_name == release_name:
        raise ActivationError("candidate is already current without this activation identity")

    run_gate(release, "run-focused-contracts", args.release_owner_uid, 120)
    replace_link(previous, current_raw)
    replace_link(current, "releases/" + release_name)
    try:
        run_gate(release, "health-check", args.release_owner_uid, 30)
        run_gate(release, "run-canary", args.release_owner_uid, 120)
    except ActivationError:
        replace_link(current, current_raw)
        replace_link(previous, previous_raw)
        if (link_state(current, releases)[2] != current_before
                or link_state(previous, releases)[2] != previous_before):
            raise ActivationError("automatic restoration failed closed")
        raise

    current_after = link_state(current, releases)[2]
    previous_after = link_state(previous, releases)[2]
    result: dict[str, Any] = {
        "schemaVersion": RESULT_SCHEMA,
        "operation": request["operation"],
        "workerId": WORKER_ID,
        "planId": request["planId"],
        "candidateId": request["candidateId"],
        "authorizationId": request["authorizationId"],
        "idempotencyKey": request["idempotencyKey"],
        "state": "ACTIVATED",
        "codexVersion": candidate["codexVersion"],
        "releaseDigestSha256": candidate["releaseDigestSha256"],
        "catalogRevision": candidate["catalogRevision"],
        "schemaComparison": "PASS",
        "focusedContracts": "PASS",
        "workerHealth": "PASS",
        "canary": "PASS",
        "currentBeforeFingerprint": current_before,
        "previousBeforeFingerprint": previous_before,
        "currentAfterFingerprint": current_after,
        "previousAfterFingerprint": previous_after,
        "automaticRestore": "NOT_REQUIRED",
        "valuesExposed": False,
    }
    persist(operation_path, {"requestFingerprint": request_fingerprint, "result": result})
    return result


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--registry", required=True, type=Path)
    parser.add_argument("--release-root", required=True, type=Path)
    parser.add_argument("--registry-owner-uid", type=int, default=0)
    parser.add_argument("--release-owner-uid", type=int, required=True)
    args = parser.parse_args()
    try:
        result = activate(args, read_request())
    except (ActivationError, OSError, json.JSONDecodeError) as error:
        print(json.dumps({"error": "activation_rejected", "message": str(error)},
                         sort_keys=True, separators=(",", ":")), file=sys.stderr)
        return 2
    print(json.dumps(result, sort_keys=True, separators=(",", ":")))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
