#!/usr/bin/env python3
"""Closed, source-only Beautips operation planner.

The planner accepts only a WorkSession UUID and one reviewed symbolic
operation. It derives every path, port, resource name and command from
persisted allocation plus the exact source allowlists. It deliberately has no
execute action; installation and activation belong to OpenSpec task 2.6.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import subprocess
import sys
import uuid
from pathlib import Path
from typing import Any

PROJECT = "beautips"
ENGINE = "atenea-runtime-engine-v1"
BASE_RUNNER_SHA256 = "de84b0c96908677e334184b9290691a2116b963dd37483022f97a0fd57ed44d1"
NODE_IMAGE = (
    "node:22.16.0-bookworm-slim@"
    "sha256:048ed02c5fd52e86fda6fbd2f6a76cf0d4492fd6c6fee9e2c463ed5108da0e34"
)
MAVEN_IMAGE = (
    "maven:3.9.9-eclipse-temurin-21@"
    "sha256:3a4ab3276a087bf276f79cae96b1af04f53731bec53fb2e651aca79e4b10211e"
)
ALLOWED_SLOTS = ("slot2", "slot3", "slot4")
EXPECTED_PORTS = {
    "postgres": (5432, "tcp"),
    "redis": (6379, "tcp"),
    "web": (8080, "http"),
}


class Rejected(RuntimeError):
    pass


def reject(message: str) -> None:
    raise Rejected(message)


def read_json(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        reject("closed contract input is missing or invalid")
    if not isinstance(value, dict):
        reject("closed contract input is not an object")
    return value


def sha256(path: Path) -> str:
    try:
        return hashlib.sha256(path.read_bytes()).hexdigest()
    except OSError:
        reject("reviewed source input is unavailable")


def checked(argv: list[str], cwd: Path) -> str:
    if not argv or argv[0] != "git":
        reject("canonical Git command rejected")
    command = [
        "git",
        "-c",
        f"safe.directory={cwd}",
        *argv[1:],
    ]
    try:
        result = subprocess.run(
            command,
            cwd=cwd,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            timeout=15,
            check=True,
        )
    except (OSError, subprocess.SubprocessError):
        reject("canonical Git identity rejected")
    return result.stdout.strip()


def roots() -> tuple[Path, Path]:
    test_mode = os.environ.get("ATENEA_BEAUTIPS_MEDIATOR_TEST_MODE") == "1"
    if test_mode:
        value = os.environ.get("ATENEA_BEAUTIPS_MEDIATOR_TEST_ROOT", "")
        root = Path(value)
        if not root.is_absolute() or not str(root).startswith("/tmp/") or ".." in root.parts:
            reject("test root must be an explicit path beneath /tmp")
        source = Path(__file__).resolve().parent
        return root, source
    if os.geteuid() != 0:
        reject("installed mediation requires the root-owned boundary")
    return Path("/srv/atenea"), Path("/usr/local/libexec/atenea")


def validate_registry(source: Path) -> tuple[dict[str, Any], dict[str, Any]]:
    allowlist = read_json(source / "project-codex-allowlist-v1.json")
    operations = read_json(source / "beautips-runtime-operations-v1.json")
    if set(allowlist) != {"schemaVersion", "projects"}:
        reject("project allowlist is not closed")
    if allowlist.get("schemaVersion") != "project-codex-allowlist-v1":
        reject("project allowlist version rejected")
    if set(allowlist.get("projects", {})) != {PROJECT}:
        reject("project allowlist identity rejected")
    project = allowlist["projects"][PROJECT]
    required_project = {
        "selectionEnabled",
        "executionEnabled",
        "projectId",
        "repository",
        "branch",
        "commit",
        "tree",
        "manifestPath",
        "manifestSha256",
        "composePath",
        "composeSha256",
        "workerId",
        "workloadClass",
        "runnerPath",
        "runnerSha256",
        "baseRunnerSha256",
        "secretBoundaryPath",
        "secretBoundarySha256",
        "allowedSlots",
        "workspaces",
    }
    if (
        not isinstance(project, dict)
        or set(project) != required_project
        or project["selectionEnabled"] is not False
        or project["executionEnabled"] is not False
        or project["projectId"] != PROJECT
        or project["repository"] != "https://github.com/jlnieto/beautips.git"
        or project["branch"] != "main"
        or project["workerId"] != "ax42-01"
        or project["workloadClass"] != "normal"
        or project["runnerPath"]
        != "/usr/local/libexec/atenea/beautips-project-codex-runner-v1.py"
        or project["runnerSha256"]
        != "55e8f585e19f6a19d3c51aaf7532b1cf0f74f6b087ae0d1ef67faaea3029b73b"
        or project["baseRunnerSha256"] != BASE_RUNNER_SHA256
        or project["secretBoundaryPath"]
        != "/usr/local/libexec/atenea/beautips-secret-boundary-v1.py"
        or project["secretBoundarySha256"]
        != "acbbb58f5ead82f47288fa499009c46797655bd277071d57e21b5c6ccfd504f6"
        or project["allowedSlots"] != list(ALLOWED_SLOTS)
        or project["workspaces"] != {}
    ):
        reject("project allowlist exact identity rejected")
    expected_operations = {
        "node-build",
        "maven-test",
        "compose-build",
        "runtime-start",
        "runtime-health",
        "functional-smoke",
        "customer-smoke",
        "runtime-logs",
        "runtime-stop",
        "runtime-cleanup",
    }
    if (
        set(operations) != {"schemaVersion", "projectId", "executionEnabled", "operations"}
        or operations.get("schemaVersion") != "beautips-runtime-operations-v1"
        or operations.get("projectId") != PROJECT
        or operations.get("executionEnabled") is not False
        or set(operations.get("operations", {})) != expected_operations
    ):
        reject("operation allowlist exact identity rejected")
    for name, record in operations["operations"].items():
        if (
            not isinstance(record, dict)
            or set(record) != {"kind", "timeoutSeconds", "secretRefs"}
            or record["kind"] != name
            or not isinstance(record["timeoutSeconds"], int)
            or not 1 <= record["timeoutSeconds"] <= 1800
            or not isinstance(record["secretRefs"], list)
            or len(record["secretRefs"]) != len(set(record["secretRefs"]))
            or any(
                not isinstance(item, str)
                or not item.startswith("BEAUTIPS_SYNTHETIC_")
                for item in record["secretRefs"]
            )
        ):
            reject("operation allowlist entry rejected")
    return project, operations


def validate_allocation(root: Path, session: str, project: dict[str, Any]) -> tuple[dict[str, Any], Path]:
    session_root = root / "workspaces" / "sessions" / session
    worktree = session_root / PROJECT
    allocation_path = session_root / "runtime-allocation-v1.json"
    allocation = read_json(allocation_path)
    runtime_id = "ws-" + session.replace("-", "")
    required_names = {
        "composeProject": runtime_id + "-compose",
        "network": runtime_id + "-network",
        "volumePrefix": runtime_id + "-volume",
        "processUnit": "atenea-" + runtime_id + ".service",
        "tomcatBase": str(session_root / "runtime" / runtime_id / "tomcat"),
    }
    if (
        allocation.get("schemaVersion") != 1
        or allocation.get("state") != "allocated"
        or allocation.get("sessionId") != session
        or allocation.get("projectId") != PROJECT
        or allocation.get("workloadClass") != "normal"
        or allocation.get("slot") not in ALLOWED_SLOTS
        or allocation.get("runtimeId") != runtime_id
        or allocation.get("worktreePath") != str(worktree)
        or allocation.get("manifestRelativePath") != project["manifestPath"]
        or allocation.get("runtimeNames") != required_names
    ):
        reject("persisted allocation identity rejected")
    ports = allocation.get("allocatedPorts")
    if not isinstance(ports, list) or len(ports) != 3:
        reject("persisted port allocation rejected")
    loopbacks: set[int] = set()
    by_name: dict[str, dict[str, Any]] = {}
    for item in ports:
        if not isinstance(item, dict) or set(item) != {
            "name", "internalPort", "protocol", "bindAddress", "loopbackPort"
        }:
            reject("persisted port record rejected")
        expected = EXPECTED_PORTS.get(item["name"])
        if (
            expected != (item["internalPort"], item["protocol"])
            or item["bindAddress"] != "127.0.0.1"
            or not isinstance(item["loopbackPort"], int)
            or not 1024 <= item["loopbackPort"] <= 65535
            or item["loopbackPort"] in loopbacks
        ):
            reject("persisted port record rejected")
        loopbacks.add(item["loopbackPort"])
        by_name[item["name"]] = item
    if set(by_name) != set(EXPECTED_PORTS):
        reject("persisted port names rejected")
    if not worktree.is_dir() or worktree.is_symlink():
        reject("owned worktree rejected")
    if checked(["git", "remote", "get-url", "origin"], worktree) != project["repository"]:
        reject("canonical repository rejected")
    checked(["git", "merge-base", "--is-ancestor", project["commit"], "HEAD"], worktree)
    if checked(["git", "rev-parse", f"{project['commit']}^{{tree}}"], worktree) != project["tree"]:
        reject("canonical tree rejected")
    if sha256(worktree / project["manifestPath"]) != project["manifestSha256"]:
        reject("reviewed manifest rejected")
    if sha256(worktree / project["composePath"]) != project["composeSha256"]:
        reject("reviewed Compose rejected")
    allocation["_path"] = str(allocation_path)
    allocation["_portsByName"] = by_name
    return allocation, worktree


def command_for(name: str, allocation: dict[str, Any], worktree: Path) -> list[str]:
    runtime = allocation["runtimeId"]
    compose = allocation["runtimeNames"]["composeProject"]
    compose_path = str(worktree / "ops/docker-compose.atenea.yml")
    cache_root = Path(allocation["cacheRoot"])
    ports = allocation["_portsByName"]
    docker = ["docker", "--host", f"unix:///run/atenea-runtime/{allocation['slot']}/docker.sock"]
    compose_base = [*docker, "compose", "--project-name", compose, "--file", compose_path]
    commands = {
        "node-build": [
            *docker, "run", "--rm", "--name", runtime + "-node-build",
            "--network", "none",
            "--mount", f"type=bind,source={worktree}/backend,target=/workspace",
            "--mount", f"type=bind,source={cache_root}/node,target=/workspace/.npm",
            "--workdir", "/workspace", NODE_IMAGE,
            "sh", "-lc",
            "npm ci --cache /workspace/.npm --prefer-offline --no-audit "
            "&& npm run build:css",
        ],
        "maven-test": [
            *docker, "run", "--rm", "--name", runtime + "-maven-test",
            "--network", "none",
            "--mount", f"type=bind,source={worktree},target=/workspace",
            "--mount", f"type=bind,source={cache_root}/maven,target=/workspace/.m2",
            "--workdir", "/workspace", MAVEN_IMAGE,
            "mvn", "-B", "-f", "backend/pom.xml",
            "-Dmaven.repo.local=/workspace/.m2/repository",
            "-Dfrontend.build.skip=true",
            "test",
        ],
        "compose-build": [*compose_base, "build", "app"],
        "runtime-start": [*compose_base, "up", "-d", "--no-build"],
        "runtime-health": [
            "curl", "-fsS",
            f"http://127.0.0.1:{ports['web']['loopbackPort']}/actuator/health",
        ],
        "functional-smoke": [str(worktree / "ops/functional-smoke.sh")],
        "customer-smoke": [str(worktree / "ops/customer-web-smoke.sh")],
        "runtime-logs": [*compose_base, "logs", "--no-color"],
        "runtime-stop": [*compose_base, "down", "--remove-orphans"],
        "runtime-cleanup": [
            *compose_base, "down", "--volumes", "--remove-orphans", "--rmi", "local",
        ],
    }
    return commands[name]


def plan(session: str, operation: str) -> dict[str, Any]:
    try:
        canonical = str(uuid.UUID(session))
    except (ValueError, AttributeError):
        reject("canonical WorkSession UUID required")
    if canonical != session or session.lower() != session:
        reject("canonical WorkSession UUID required")
    root, source = roots()
    project, operations = validate_registry(source)
    if operation not in operations["operations"]:
        reject("operation is not allowlisted")
    allocation, worktree = validate_allocation(root, session, project)
    record = operations["operations"][operation]
    env_refs = {
        "ATENEA_ENGINE_LABEL": ENGINE,
        "ATENEA_SESSION_ID": session,
        "ATENEA_RUNTIME_ID": allocation["runtimeId"],
        "ATENEA_NETWORK_NAME": allocation["runtimeNames"]["network"],
        "ATENEA_VOLUME_PREFIX": allocation["runtimeNames"]["volumePrefix"],
        "ATENEA_WEB_PORT": str(allocation["_portsByName"]["web"]["loopbackPort"]),
        "ATENEA_POSTGRES_PORT": str(allocation["_portsByName"]["postgres"]["loopbackPort"]),
        "ATENEA_REDIS_PORT": str(allocation["_portsByName"]["redis"]["loopbackPort"]),
    }
    if operation in {"functional-smoke", "customer-smoke"}:
        env_refs["BEAUTIPS_SMOKE_MANAGED_MODE"] = "true"
        env_refs["APP_URL"] = (
            "http://127.0.0.1:" + str(allocation["_portsByName"]["web"]["loopbackPort"])
        )
    if operation == "customer-smoke":
        env_refs["POSTGRES_DB"] = "beautips_synthetic"
        env_refs["POSTGRES_USER"] = "beautips_synthetic"
        env_refs["POSTGRES_PORT"] = str(
            allocation["_portsByName"]["postgres"]["loopbackPort"]
        )
    return {
        "schemaVersion": "beautips-operation-plan-v1",
        "executionEnabled": False,
        "sessionId": session,
        "projectId": PROJECT,
        "runtimeId": allocation["runtimeId"],
        "slot": allocation["slot"],
        "operation": operation,
        "allocationPath": allocation["_path"],
        "worktreePath": str(worktree),
        "argv": command_for(operation, allocation, worktree),
        "environment": env_refs,
        "secretRefs": record["secretRefs"],
        "timeoutSeconds": record["timeoutSeconds"],
    }


def main() -> int:
    parser = argparse.ArgumentParser(allow_abbrev=False)
    parser.add_argument("action", choices=("plan",))
    parser.add_argument("--session", required=True)
    parser.add_argument("--operation", required=True)
    arguments = parser.parse_args()
    try:
        output = plan(arguments.session, arguments.operation)
    except Rejected as error:
        print(f"BEAUTIPS_OPERATION_REJECTED: {error}", file=sys.stderr)
        return 65
    print(json.dumps(output, sort_keys=True, separators=(",", ":")))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
