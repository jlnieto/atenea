#!/usr/bin/env python3
"""Root-owned exact Atenea Codex runner; accepts one closed JSON contract."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import pwd
import signal
import subprocess
import sys
import tempfile
import uuid
from pathlib import Path
from typing import Any

CAPABILITY = "project-codex-v1"
PROJECT_ID = "atenea"
REPOSITORY = "https://github.com/jlnieto/atenea.git"
BRANCH = "feature/actualizar-conversacion-en-web"
BASE_COMMIT = "b605c8d5b063e7321edd60fec2265ec7ddb84ea9"
MANIFEST_SHA256 = "3b26e1899a06993bee69ac596e7cb69b6200a37d063d98203ad308058c91bfa3"
CODEX = "/home/jose/.codex/packages/standalone/current/bin/codex"
GIT_COMMON_DIR = Path("/srv/atenea/repositories/atenea.git")
REQUEST_KEYS = {"dispatchId", "executionId", "sessionId", "workspaceIdentity", "workload"}
WORKLOAD_KEYS = {
    "kind", "projectId", "repository", "branch", "commit",
    "manifestSha256", "message", "threadId",
}


def reject(message: str) -> None:
    print(message, file=sys.stderr)
    raise SystemExit(2)


def load_json(path: Path) -> dict[str, Any]:
    try:
        stat = path.stat()
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        reject("project configuration rejected")
    if stat.st_uid != 0 or stat.st_mode & 0o022 or not isinstance(value, dict):
        reject("project configuration rejected")
    return value


def validate_config(config: dict[str, Any], runner: Path) -> None:
    required = {
        "schemaVersion", "selectionEnabled", "executionEnabled",
        "projectId", "repository", "branch",
        "commit", "manifestSha256", "runner", "workspaces",
    }
    exact = {
        "schemaVersion": CAPABILITY,
        "selectionEnabled": True,
        "executionEnabled": True,
        "projectId": PROJECT_ID,
        "repository": REPOSITORY,
        "branch": BRANCH,
        "commit": BASE_COMMIT,
        "manifestSha256": MANIFEST_SHA256,
        "runner": str(runner),
    }
    if (
        set(config) != required
        or any(config.get(key) != value for key, value in exact.items())
        or not isinstance(config.get("workspaces"), dict)
    ):
        reject("project configuration rejected")


def validate_request(request: Any, config: dict[str, Any]) -> tuple[dict[str, Any], Path]:
    if not isinstance(request, dict) or set(request) != REQUEST_KEYS:
        reject("workspace ownership rejected")
    for key in ("dispatchId", "executionId", "sessionId"):
        try:
            uuid.UUID(request[key])
        except (ValueError, TypeError, AttributeError):
            reject("workspace ownership rejected")
    if not isinstance(request["workspaceIdentity"], str):
        reject("workspace ownership rejected")
    workload = request["workload"]
    exact = {
        "kind": CAPABILITY,
        "projectId": PROJECT_ID,
        "repository": REPOSITORY,
        "branch": BRANCH,
        "commit": BASE_COMMIT,
        "manifestSha256": MANIFEST_SHA256,
    }
    if (
        not isinstance(workload, dict)
        or set(workload) != WORKLOAD_KEYS
        or any(workload.get(key) != value for key, value in exact.items())
        or not isinstance(workload.get("message"), str)
        or not (1 <= len(workload["message"]) <= 20_000)
    ):
        reject("workspace ownership rejected")
    thread_id = workload["threadId"]
    if thread_id is not None:
        try:
            uuid.UUID(thread_id)
        except (ValueError, TypeError, AttributeError):
            reject("workspace ownership rejected")
    record = config["workspaces"].get(request["workspaceIdentity"])
    if (
        not isinstance(record, dict)
        or set(record) != {"sessionId", "worktree", "allocationSha256"}
        or record["sessionId"] != request["sessionId"]
        or not isinstance(record["allocationSha256"], str)
        or len(record["allocationSha256"]) != 64
    ):
        reject("workspace ownership rejected")
    expected = Path("/srv/atenea/workspaces/sessions") / request["sessionId"] / PROJECT_ID
    worktree = Path(record["worktree"])
    if worktree != expected or not worktree.is_dir() or worktree.is_symlink():
        reject("workspace ownership rejected")
    return workload, worktree


def checked(command: list[str], cwd: Path) -> str:
    if command and command[0] == "git":
        command = ["git", "-c", f"safe.directory={cwd}", *command[1:]]
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
        reject("worktree fingerprint rejected")
    return result.stdout.strip()


def validate_worktree(worktree: Path, record: dict[str, Any]) -> Path:
    root = checked(["git", "rev-parse", "--show-toplevel"], worktree)
    if Path(root) != worktree:
        reject("worktree fingerprint rejected")
    if checked(["git", "remote", "get-url", "origin"], worktree) != REPOSITORY:
        reject("worktree fingerprint rejected")
    common_dir = Path(checked(["git", "rev-parse", "--git-common-dir"], worktree)).resolve()
    if common_dir != GIT_COMMON_DIR or common_dir.is_symlink():
        reject("worktree fingerprint rejected")
    checked(["git", "merge-base", "--is-ancestor", BASE_COMMIT, "HEAD"], worktree)
    manifest = worktree / "ops" / "atenea-runtime.json"
    try:
        digest = hashlib.sha256(manifest.read_bytes()).hexdigest()
    except OSError:
        reject("worktree fingerprint rejected")
    if digest != MANIFEST_SHA256:
        reject("worktree fingerprint rejected")
    allocation = worktree.parent / "runtime-allocation-v1.json"
    try:
        allocation_digest = hashlib.sha256(allocation.read_bytes()).hexdigest()
    except OSError:
        reject("worktree fingerprint rejected")
    if allocation_digest != record["allocationSha256"]:
        reject("worktree fingerprint rejected")
    return common_dir


def sandbox_command(
    workload: dict[str, Any],
    worktree: Path,
    common_dir: Path,
    final_path: Path,
    resolv_path: Path,
    execution_id: str,
) -> list[str]:
    command = [
        "/usr/bin/systemd-run",
        "--wait", "--pipe", "--collect", "--quiet", "--service-type=exec",
        "--unit", "atenea-project-codex-" + execution_id.replace("-", ""),
        "--property", "User=jose",
        "--property", "Group=jose",
        "--property", "NoNewPrivileges=yes",
        "--property", "PrivateDevices=yes",
        # A private Bubblewrap proc mount supplies the user-namespace boundary.
        # systemd's proc overlays for tunables/logs would prevent that mount.
        "--property", "ProtectKernelModules=yes",
        "--property", "ProtectControlGroups=yes",
        "--property", "RestrictSUIDSGID=yes",
        "--property", "LockPersonality=yes",
        "--property", "RestrictAddressFamilies=AF_INET AF_INET6 AF_UNIX",
        "--property", "IPAddressDeny=127.0.0.0/8",
        "--property", "IPAddressDeny=10.0.0.0/8",
        "--property", "IPAddressDeny=100.64.0.0/10",
        "--property", "IPAddressDeny=169.254.0.0/16",
        "--property", "IPAddressDeny=172.16.0.0/12",
        "--property", "IPAddressDeny=192.168.0.0/16",
        "--property", "IPAddressDeny=::1/128",
        "--property", "IPAddressDeny=fc00::/7",
        "--property", "IPAddressDeny=fe80::/10",
        "--",
        "/usr/bin/bwrap",
        "--die-with-parent", "--new-session", "--unshare-all", "--share-net",
        "--proc", "/proc", "--dev", "/dev", "--tmpfs", "/tmp",
        "--dir", str(final_path.parent),
        "--bind", str(final_path.parent), str(final_path.parent),
        "--ro-bind", "/usr", "/usr",
        "--symlink", "usr/bin", "/bin",
        "--symlink", "usr/sbin", "/sbin",
        "--symlink", "usr/lib", "/lib",
        "--symlink", "usr/lib64", "/lib64",
        "--dir", "/etc",
        "--ro-bind", "/etc/ssl", "/etc/ssl",
        "--ro-bind", str(resolv_path), "/etc/resolv.conf",
        "--ro-bind", "/etc/hosts", "/etc/hosts",
        "--ro-bind", "/etc/nsswitch.conf", "/etc/nsswitch.conf",
        "--ro-bind", "/etc/passwd", "/etc/passwd",
        "--ro-bind", "/etc/group", "/etc/group",
        "--dir", "/home", "--dir", "/home/jose",
        "--bind", "/home/jose/.codex", "/home/jose/.codex",
        "--dir", "/srv", "--dir", "/srv/atenea", "--dir", "/srv/atenea/workspaces",
        "--dir", "/srv/atenea/workspaces/sessions",
        "--dir", str(worktree.parent),
        "--bind", str(worktree), str(worktree),
        "--dir", "/srv/atenea/repositories",
        "--bind", str(common_dir), str(common_dir),
        "--setenv", "HOME", "/home/jose",
        "--setenv", "USER", "jose",
        "--setenv", "LOGNAME", "jose",
        "--setenv", "PATH", "/usr/bin:/bin",
        "--setenv", "GIT_CONFIG_COUNT", "1",
        "--setenv", "GIT_CONFIG_KEY_0", "safe.directory",
        "--setenv", "GIT_CONFIG_VALUE_0", str(worktree),
        "--chdir", str(worktree),
        CODEX, "exec",
        "--ignore-user-config", "--ignore-rules",
        "--sandbox", "workspace-write",
        "-C", str(worktree),
        "--json", "--output-last-message", str(final_path),
    ]
    if workload["threadId"] is not None:
        command.extend(["resume", workload["threadId"], "-"])
    else:
        command.append("-")
    return command


def execute(
    workload: dict[str, Any],
    worktree: Path,
    common_dir: Path,
    execution_id: str,
    timeout: int,
) -> dict[str, str]:
    with tempfile.TemporaryDirectory(prefix="atenea-codex-result-") as temporary:
        jose = pwd.getpwnam("jose")
        os.chown(temporary, jose.pw_uid, jose.pw_gid)
        os.chmod(temporary, 0o700)
        final_path = Path(temporary) / "final.txt"
        resolv_path = Path(temporary) / "resolv.conf"
        resolv_path.write_text("nameserver 1.1.1.1\noptions timeout:2 attempts:2\n", encoding="ascii")
        os.chown(resolv_path, jose.pw_uid, jose.pw_gid)
        os.chmod(resolv_path, 0o600)
        command = sandbox_command(
            workload,
            worktree,
            common_dir,
            final_path,
            resolv_path,
            execution_id,
        )
        process = subprocess.Popen(
            command,
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            text=True,
            start_new_session=True,
        )

        def terminate(_signum: int, _frame: Any) -> None:
            try:
                os.killpg(process.pid, signal.SIGTERM)
            except ProcessLookupError:
                pass

        signal.signal(signal.SIGTERM, terminate)
        try:
            stream, _ = process.communicate(workload["message"], timeout=timeout)
        except subprocess.TimeoutExpired:
            terminate(signal.SIGTERM, None)
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
                process.wait(timeout=5)
            reject("Codex execution failed")
        if process.returncode != 0:
            reject("Codex execution failed")
        thread_id = workload["threadId"]
        for line in stream.splitlines():
            try:
                event = json.loads(line)
            except json.JSONDecodeError:
                continue
            if event.get("type") == "thread.started" and isinstance(event.get("thread_id"), str):
                thread_id = event["thread_id"]
        if thread_id is None:
            reject("Codex execution failed")
        try:
            final_answer = final_path.read_text(encoding="utf-8").strip()
        except OSError:
            reject("Codex execution failed")
        if not final_answer or len(final_answer.encode()) > 262_144:
            reject("Codex execution failed")
        return {
            "threadId": thread_id,
            "turnId": execution_id,
            "finalAnswer": final_answer,
            "outputSummary": f"{CAPABILITY} completed",
        }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", required=True, type=Path)
    parser.add_argument("--timeout", type=int, default=1800)
    args = parser.parse_args()
    if os.geteuid() != 0 or not (30 <= args.timeout <= 3600):
        reject("project configuration rejected")
    runner = Path(__file__).resolve()
    config = load_json(args.config)
    validate_config(config, runner)
    try:
        request = json.load(sys.stdin)
    except (json.JSONDecodeError, UnicodeDecodeError):
        reject("workspace ownership rejected")
    workload, worktree = validate_request(request, config)
    record = config["workspaces"][request["workspaceIdentity"]]
    common_dir = validate_worktree(worktree, record)
    result = execute(workload, worktree, common_dir, request["executionId"], args.timeout)
    print(json.dumps(result, sort_keys=True, separators=(",", ":")))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
