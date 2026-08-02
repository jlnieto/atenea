#!/usr/bin/env python3
"""Root-owned exact Atenea Codex runner; accepts one closed JSON contract."""

from __future__ import annotations

import argparse
import grp
import hashlib
import json
import os
import pwd
import re
import signal
import stat
import subprocess
import sys
import tempfile
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, NamedTuple

CAPABILITY = "project-codex-v1"
PROFILED_CAPABILITY = "project-codex-v2"
IMAGE_CAPABILITY = "project-codex-v3"
CODEX_VERSION = "0.145.0"
CODEX_MODEL = "gpt-5.6-sol"
CODEX_EFFORTS = {"none", "low", "medium", "high", "xhigh", "max"}
PROJECT_ID = "atenea"
REPOSITORY = "https://github.com/jlnieto/atenea.git"
BRANCH = "feature/actualizar-conversacion-en-web"
BASE_COMMIT: str | None = None
MANIFEST_SHA256 = "3b26e1899a06993bee69ac596e7cb69b6200a37d063d98203ad308058c91bfa3"
CODEX = "/home/jose/.codex/packages/standalone/current/bin/codex"
GIT_COMMON_DIR = Path("/srv/atenea/repositories/atenea.git")
INSTRUCTION_BUNDLE_REVISION = "atenea-reviewed-instruction-bundle-v1"
PLATFORM_INSTRUCTION_PATH = Path(
    "/usr/local/share/atenea/codex-platform-instructions-v1.md"
)
PLATFORM_INSTRUCTION_UID = 0
PLATFORM_INSTRUCTION_SHA256 = (
    "44c578a286eb50b35612be0b6c38d59a503e6fee1ecf6cd0339415af018cdf0d"
)
PROJECT_INSTRUCTION_PATH = "AGENTS.md"
PROJECT_INSTRUCTION_SHA256 = (
    "a09adc5855ff54490211a0f5c82f413cb84ee7197b2b350e0b0dc40eba7c98dc"
)
INSTRUCTION_BUNDLE_SHA256 = (
    "ab9f1877c83333945497797e6b8aefd20f67debf8e3bdc6d1b824fc5a3f86c04"
)
COMMIT_PATTERN = re.compile(r"^[0-9a-f]{40}$")
SHA256_PATTERN = re.compile(r"^[0-9a-f]{64}$")
REQUEST_KEYS = {"dispatchId", "executionId", "sessionId", "workspaceIdentity", "workload"}
WORKLOAD_KEYS = {
    "kind", "projectId", "repository", "branch", "commit",
    "manifestSha256", "message", "threadId", "instructionBundleRevision",
    "instructionBundleSha256", "platformInstructionSha256",
    "projectInstructionPath", "projectInstructionSha256",
}
PROFILED_WORKLOAD_KEYS = WORKLOAD_KEYS | {
    "modelId", "reasoningEffort", "catalogRevision", "codexVersion",
}
IMAGE_WORKLOAD_KEYS = PROFILED_WORKLOAD_KEYS | {"attachments"}
ATTACHMENT_REFERENCE_KEYS = {"attachmentId", "contentType", "sizeBytes", "sha256"}
ATTACHMENT_METADATA_KEYS = {
    "protocolVersion", "workerId", "sessionId", "attachmentId",
    "storageIdentity", "source", "kind", "contentType", "sizeBytes",
    "retentionClass", "sha256", "syntheticFixture", "createdAt", "storedAt",
    "projectIdentity", "workspaceIdentity", "storageScope",
}
ATTACHMENT_ROOT = Path("/srv/atenea/attachments-v1")
ATTACHMENT_WORKER_ID = "ax42-01"
ATTACHMENT_OWNER = "atenea-worker"
ATTACHMENT_GROUP = "atenea"
ATTACHMENT_TYPES = {"image/png", "image/jpeg", "image/webp"}
MAX_ATTACHMENT_BYTES = 16 * 1024 * 1024
MAX_ATTACHMENT_TOTAL_BYTES = 32 * 1024 * 1024
CODEX_CATALOG_REVISION = (
    "125b9437e38f83e04cb10996fc70d3ab44c32082009b8e897cb08bb340b13187"
)
SAFE_PROGRESS_MESSAGES = {
    "CODEX_STARTED": "Codex started the accepted turn.",
    "INSPECTING_PROJECT": "Inspecting the accepted project.",
    "RUNNING_COMMAND": "Running a reviewed project operation.",
    "CHECKING": "Checking the accepted project.",
    "WAITING": "Waiting for a bounded operation.",
    "FINALIZING": "Finalizing the Codex turn.",
}


class VerifiedAttachment(NamedTuple):
    attachment_id: str
    content_type: str
    size_bytes: int
    sha256: str
    content_path: Path


def reject(message: str) -> None:
    print(message, file=sys.stderr)
    raise SystemExit(2)


def codex_failure_reason(stderr: str) -> str:
    lowered = stderr.lower()
    categories = (
        (("permission denied", "read-only file system", "operation not permitted",
          "can't find source path"),
         "Codex execution failed: filesystem boundary"),
        (("not logged in", "authentication", "unauthorized"),
         "Codex execution failed: authentication unavailable"),
        (("unknown argument", "unexpected argument", "invalid value"),
         "Codex execution failed: CLI contract"),
        (("failed to lookup address", "connection", "dns", "request failed"),
         "Codex execution failed: network unavailable"),
        (("thread", "session", "state database", "database is locked"),
         "Codex execution failed: thread persistence unavailable"),
    )
    for needles, reason in categories:
        if any(needle in lowered for needle in needles):
            return reason
    return "Codex execution failed: unclassified"


def validate_codex_version(workload: dict[str, Any]) -> None:
    if workload["kind"] not in {PROFILED_CAPABILITY, IMAGE_CAPABILITY}:
        return
    try:
        observed = subprocess.run(
            [CODEX, "--version"],
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            timeout=15,
            check=True,
        ).stdout.strip()
    except (OSError, subprocess.SubprocessError):
        reject("Codex execution failed: CLI contract")
    if observed != "codex-cli " + workload["codexVersion"]:
        reject("Codex execution failed: CLI contract")


def effective_profile(workload: dict[str, Any]) -> dict[str, str]:
    if workload["kind"] not in {PROFILED_CAPABILITY, IMAGE_CAPABILITY}:
        return {}
    return {
        key: workload[key]
        for key in ("modelId", "reasoningEffort", "catalogRevision", "codexVersion")
    }


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


def normalize_codex_events(stream: str) -> list[dict[str, str]]:
    """Map only recognized structure to fixed text; discard every payload value."""
    normalized: list[dict[str, str]] = []

    def append(category: str) -> None:
        message = SAFE_PROGRESS_MESSAGES[category]
        if normalized and normalized[-1]["category"] == category and normalized[-1]["message"] == message:
            return
        normalized.append({
            "category": category,
            "occurredAt": utc_now(),
            "message": message,
        })
        if len(normalized) > 200:
            del normalized[:-200]

    for line in stream.splitlines():
        try:
            event = json.loads(line)
        except (json.JSONDecodeError, TypeError):
            continue
        if not isinstance(event, dict):
            continue
        event_type = event.get("type")
        if event_type in {"thread.started", "turn.started"}:
            append("CODEX_STARTED")
            continue
        if event_type == "turn.completed":
            append("FINALIZING")
            continue
        if event_type in {"turn.failed", "error"}:
            continue
        if event_type not in {"item.started", "item.completed"}:
            continue
        item = event.get("item")
        if not isinstance(item, dict):
            continue
        item_type = item.get("type")
        if item_type in {"reasoning", "agent_message"}:
            continue
        category = {
            "web_search": "INSPECTING_PROJECT",
            "command_execution": "RUNNING_COMMAND",
            "mcp_tool_call": "CHECKING",
            "file_change": "CHECKING",
            "todo_list": "CHECKING",
        }.get(item_type)
        if category is not None:
            append(category)
    return normalized


def internal_failure_reason(exception: Exception) -> str:
    allowed = {
        "AttributeError",
        "FileNotFoundError",
        "OSError",
        "PermissionError",
        "TypeError",
        "UnboundLocalError",
        "ValueError",
    }
    name = type(exception).__name__
    return "Project runner internal exception: " + (name if name in allowed else "Other")


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
    if PROJECT_ID == "atenea":
        required.add("attachmentRoot")
    exact = {
        "schemaVersion": CAPABILITY,
        "selectionEnabled": True,
        "executionEnabled": True,
        "projectId": PROJECT_ID,
        "repository": REPOSITORY,
        "branch": BRANCH,
        "manifestSha256": MANIFEST_SHA256,
        "runner": str(runner),
    }
    if PROJECT_ID == "atenea":
        exact["attachmentRoot"] = str(ATTACHMENT_ROOT)
    if (
        set(config) != required
        or any(config.get(key) != value for key, value in exact.items())
        or not isinstance(config.get("commit"), str)
        or COMMIT_PATTERN.fullmatch(config["commit"]) is None
        or (BASE_COMMIT is not None and config["commit"] != BASE_COMMIT)
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
    capability = workload.get("kind") if isinstance(workload, dict) else None
    allowed_capabilities = {CAPABILITY, PROFILED_CAPABILITY}
    if PROJECT_ID == "atenea":
        allowed_capabilities.add(IMAGE_CAPABILITY)
    workload_keys = (
        IMAGE_WORKLOAD_KEYS
        if capability == IMAGE_CAPABILITY
        else PROFILED_WORKLOAD_KEYS
        if capability == PROFILED_CAPABILITY
        else WORKLOAD_KEYS
    )
    exact = {
        "kind": capability,
        "projectId": PROJECT_ID,
        "repository": REPOSITORY,
        "branch": BRANCH,
        "manifestSha256": MANIFEST_SHA256,
        "instructionBundleRevision": INSTRUCTION_BUNDLE_REVISION,
        "instructionBundleSha256": INSTRUCTION_BUNDLE_SHA256,
        "platformInstructionSha256": PLATFORM_INSTRUCTION_SHA256,
        "projectInstructionPath": PROJECT_INSTRUCTION_PATH,
        "projectInstructionSha256": PROJECT_INSTRUCTION_SHA256,
    }
    if (
        not isinstance(workload, dict)
        or capability not in allowed_capabilities
        or set(workload) != workload_keys
        or any(workload.get(key) != value for key, value in exact.items())
        or workload.get("commit") != config["commit"]
        or not isinstance(workload.get("message"), str)
        or not (1 <= len(workload["message"]) <= 20_000)
    ):
        reject("workspace ownership rejected")
    if capability in {PROFILED_CAPABILITY, IMAGE_CAPABILITY} and (
        workload.get("modelId") != CODEX_MODEL
        or workload.get("reasoningEffort") not in CODEX_EFFORTS
        or workload.get("catalogRevision") != CODEX_CATALOG_REVISION
        or workload.get("codexVersion") != CODEX_VERSION
    ):
        reject("workspace ownership rejected")
    thread_id = workload["threadId"]
    if thread_id is not None:
        try:
            uuid.UUID(thread_id)
        except (ValueError, TypeError, AttributeError):
            reject("workspace ownership rejected")
    record = config["workspaces"].get(request["workspaceIdentity"])
    record_keys = {"sessionId", "worktree", "allocationSha256"}
    if BASE_COMMIT is None:
        record_keys.add("canonicalCommit")
    if (
        not isinstance(record, dict)
        or set(record) != record_keys
        or record["sessionId"] != request["sessionId"]
        or not isinstance(record["allocationSha256"], str)
        or len(record["allocationSha256"]) != 64
        or (BASE_COMMIT is None and record["canonicalCommit"] != config["commit"])
    ):
        reject("workspace ownership rejected")
    expected = Path("/srv/atenea/workspaces/sessions") / request["sessionId"] / PROJECT_ID
    worktree = Path(record["worktree"])
    if worktree != expected or not worktree.is_dir() or worktree.is_symlink():
        reject("workspace ownership rejected")
    return workload, worktree


def attachment_owner_ids() -> tuple[int, int]:
    try:
        return pwd.getpwnam(ATTACHMENT_OWNER).pw_uid, grp.getgrnam(ATTACHMENT_GROUP).gr_gid
    except KeyError:
        reject("attachment ownership rejected")


def validate_owned_path(
    path: Path,
    expected_mode: int,
    expected_uid: int,
    expected_gid: int,
    directory: bool,
) -> os.stat_result:
    try:
        observed = path.lstat()
    except OSError:
        reject("attachment ownership rejected")
    expected_type = stat.S_ISDIR if directory else stat.S_ISREG
    if (
        not expected_type(observed.st_mode)
        or stat.S_IMODE(observed.st_mode) != expected_mode
        or observed.st_uid != expected_uid
        or observed.st_gid != expected_gid
        or (not directory and observed.st_nlink != 1)
    ):
        reject("attachment ownership rejected")
    return observed


def read_owned_file(
    path: Path,
    expected: os.stat_result,
    maximum_bytes: int,
) -> bytes:
    try:
        descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    except OSError:
        reject("attachment ownership rejected")
    try:
        opened = os.fstat(descriptor)
        if (
            opened.st_dev != expected.st_dev
            or opened.st_ino != expected.st_ino
            or opened.st_size <= 0
            or opened.st_size > maximum_bytes
        ):
            reject("attachment ownership rejected")
        chunks = []
        remaining = maximum_bytes + 1
        while remaining > 0:
            chunk = os.read(descriptor, min(64 * 1024, remaining))
            if not chunk:
                break
            chunks.append(chunk)
            remaining -= len(chunk)
        value = b"".join(chunks)
        if len(value) != opened.st_size or len(value) > maximum_bytes:
            reject("attachment ownership rejected")
        return value
    finally:
        os.close(descriptor)


def content_matches_type(content_type: str, prefix: bytes) -> bool:
    if content_type == "image/png":
        return prefix.startswith(b"\x89PNG\r\n\x1a\n")
    if content_type == "image/jpeg":
        return prefix.startswith(b"\xff\xd8\xff")
    if content_type == "image/webp":
        return len(prefix) >= 12 and prefix[:4] == b"RIFF" and prefix[8:12] == b"WEBP"
    return False


def validate_attachment_references(
    request: dict[str, Any],
    workload: dict[str, Any],
    config: dict[str, Any],
) -> list[VerifiedAttachment]:
    if workload["kind"] != IMAGE_CAPABILITY:
        return []
    references = workload.get("attachments")
    if not isinstance(references, list) or not (1 <= len(references) <= 4):
        reject("attachment ownership rejected")
    session_id = request["sessionId"]
    try:
        canonical_session = str(uuid.UUID(session_id))
    except (ValueError, TypeError, AttributeError):
        canonical_session = None
    if canonical_session != session_id or config.get("attachmentRoot") != str(ATTACHMENT_ROOT):
        reject("attachment ownership rejected")
    root = Path(config["attachmentRoot"])
    expected_uid, expected_gid = attachment_owner_ids()
    sessions_root = root / "work-sessions"
    session_root = sessions_root / session_id
    for directory in (root, sessions_root, session_root):
        validate_owned_path(directory, 0o700, expected_uid, expected_gid, True)

    verified = []
    identities: set[str] = set()
    total_bytes = 0
    for reference in references:
        if not isinstance(reference, dict) or set(reference) != ATTACHMENT_REFERENCE_KEYS:
            reject("attachment ownership rejected")
        attachment_id = reference.get("attachmentId")
        try:
            canonical_attachment = str(uuid.UUID(attachment_id))
        except (ValueError, TypeError, AttributeError):
            canonical_attachment = None
        if canonical_attachment != attachment_id or attachment_id in identities:
            reject("attachment ownership rejected")
        content_type = reference.get("contentType")
        size_bytes = reference.get("sizeBytes")
        digest = reference.get("sha256")
        if (
            content_type not in ATTACHMENT_TYPES
            or not isinstance(size_bytes, int)
            or isinstance(size_bytes, bool)
            or not (1 <= size_bytes <= MAX_ATTACHMENT_BYTES)
            or not isinstance(digest, str)
            or SHA256_PATTERN.fullmatch(digest) is None
        ):
            reject("attachment ownership rejected")
        attachment_root = session_root / attachment_id
        validate_owned_path(attachment_root, 0o700, expected_uid, expected_gid, True)
        metadata_path = attachment_root / "metadata.json"
        content_path = attachment_root / "content"
        metadata_stat = validate_owned_path(
            metadata_path, 0o600, expected_uid, expected_gid, False
        )
        content_stat = validate_owned_path(
            content_path, 0o600, expected_uid, expected_gid, False
        )
        try:
            metadata = json.loads(
                read_owned_file(metadata_path, metadata_stat, 16 * 1024).decode("utf-8")
            )
        except (UnicodeDecodeError, json.JSONDecodeError):
            reject("attachment ownership rejected")
        expected_metadata = {
            "protocolVersion": "worksession-attachment/v1",
            "workerId": ATTACHMENT_WORKER_ID,
            "sessionId": session_id,
            "attachmentId": attachment_id,
            "storageIdentity": f"work-sessions/{session_id}/{attachment_id}/content",
            "source": "OPERATOR_UPLOAD",
            "kind": "IMAGE",
            "contentType": content_type,
            "sizeBytes": size_bytes,
            "retentionClass": "SESSION",
            "sha256": digest,
            "syntheticFixture": False,
            "projectIdentity": PROJECT_ID,
            "workspaceIdentity": request["workspaceIdentity"],
            "storageScope": "REAL_SESSION",
        }
        if (
            not isinstance(metadata, dict)
            or set(metadata) != ATTACHMENT_METADATA_KEYS
            or any(metadata.get(key) != value for key, value in expected_metadata.items())
            or not isinstance(metadata.get("createdAt"), str)
            or not metadata["createdAt"].endswith("Z")
            or not isinstance(metadata.get("storedAt"), str)
            or not metadata["storedAt"].endswith("Z")
            or content_stat.st_size != size_bytes
        ):
            reject("attachment ownership rejected")
        content = read_owned_file(content_path, content_stat, MAX_ATTACHMENT_BYTES)
        if hashlib.sha256(content).hexdigest() != digest or not content_matches_type(
            content_type, content[:12]
        ):
            reject("attachment ownership rejected")
        identities.add(attachment_id)
        total_bytes += size_bytes
        verified.append(VerifiedAttachment(
            attachment_id, content_type, size_bytes, digest, content_path
        ))
    if total_bytes > MAX_ATTACHMENT_TOTAL_BYTES:
        reject("attachment ownership rejected")
    return verified


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
    if BASE_COMMIT is None:
        canonical_ref = "refs/remotes/origin/" + BRANCH
        canonical_commit = checked(
            ["git", "--git-dir", str(common_dir), "rev-parse", "--verify", canonical_ref + "^{commit}"],
            worktree,
        )
        if canonical_commit != record["canonicalCommit"]:
            reject("worktree fingerprint rejected")
        if checked(["git", "rev-parse", "--verify", "HEAD^{commit}"], worktree) != canonical_commit:
            reject("worktree fingerprint rejected")
        if checked(["git", "status", "--porcelain=v1", "--untracked-files=all"], worktree):
            reject("worktree fingerprint rejected")
    else:
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


def validate_instruction_bundle(worktree: Path) -> str:
    project_path = worktree / PROJECT_INSTRUCTION_PATH
    forbidden = (
        worktree / "AGENTS.override.md",
        worktree / ".codex",
    )
    try:
        platform_stat = PLATFORM_INSTRUCTION_PATH.stat()
        project_stat = project_path.stat()
        platform = PLATFORM_INSTRUCTION_PATH.read_bytes()
        project = project_path.read_bytes()
    except OSError:
        reject("instruction bundle rejected")
    if (
        not PLATFORM_INSTRUCTION_PATH.is_file()
        or PLATFORM_INSTRUCTION_PATH.is_symlink()
        or platform_stat.st_uid != PLATFORM_INSTRUCTION_UID
        or platform_stat.st_mode & 0o022
        or not project_path.is_file()
        or project_path.is_symlink()
        or project_stat.st_size == 0
        or project_stat.st_size > 32_768
        or any(path.exists() or path.is_symlink() for path in forbidden)
        or hashlib.sha256(platform).hexdigest() != PLATFORM_INSTRUCTION_SHA256
        or hashlib.sha256(project).hexdigest() != PROJECT_INSTRUCTION_SHA256
    ):
        reject("instruction bundle rejected")
    try:
        tracked = subprocess.run(
            ["git", "-c", f"safe.directory={worktree}", "cat-file", "blob",
             "HEAD:" + PROJECT_INSTRUCTION_PATH],
            cwd=worktree,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            timeout=15,
            check=True,
        ).stdout
    except (OSError, subprocess.SubprocessError):
        reject("instruction bundle rejected")
    if tracked != project:
        reject("instruction bundle rejected")
    fingerprint = hashlib.sha256(
        INSTRUCTION_BUNDLE_REVISION.encode("ascii")
        + b"\0" + platform + b"\0" + project
    ).hexdigest()
    if fingerprint != INSTRUCTION_BUNDLE_SHA256:
        reject("instruction bundle rejected")
    try:
        return (
            platform.decode("utf-8")
            + "\n\n# Reviewed repository contract: "
            + PROJECT_INSTRUCTION_PATH
            + "\n\n"
            + project.decode("utf-8")
        )
    except UnicodeDecodeError:
        reject("instruction bundle rejected")


def sandbox_command(
    workload: dict[str, Any],
    worktree: Path,
    common_dir: Path,
    final_path: Path,
    resolv_path: Path,
    instruction_mask_path: Path,
    instruction_bundle: str,
    execution_id: str,
) -> list[str]:
    command = [
        "/usr/bin/systemd-run",
        "--wait", "--pipe", "--collect", "--quiet", "--service-type=exec",
        "--unit", "atenea-project-codex-" + execution_id.replace("-", ""),
        "--property", "User=jose",
        "--property", "Group=atenea",
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
        "--ro-bind", str(instruction_mask_path), "/home/jose/.codex/AGENTS.md",
        "--ro-bind", str(instruction_mask_path), "/home/jose/.codex/AGENTS.override.md",
        "--dir", "/srv", "--dir", "/srv/atenea", "--dir", "/srv/atenea/workspaces",
        "--dir", "/srv/atenea/workspaces/sessions",
        "--dir", str(worktree.parent),
        "--bind", str(worktree), str(worktree),
        "--ro-bind", str(instruction_mask_path), str(worktree / PROJECT_INSTRUCTION_PATH),
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
        "--config", "developer_instructions=" + json.dumps(instruction_bundle),
        # The reviewed Bubblewrap namespace is the workspace-write boundary.
        # A second Codex Bubblewrap namespace is unsupported by this kernel.
        "--sandbox", "danger-full-access",
        "-C", str(worktree),
        "--json", "--output-last-message", str(final_path),
    ]
    if workload["kind"] == PROFILED_CAPABILITY:
        command.extend([
            "--model", workload["modelId"],
            "--config", "model_reasoning_effort=" + json.dumps(workload["reasoningEffort"]),
        ])
    if workload["threadId"] is not None:
        command.extend(["resume", workload["threadId"], "-"])
    else:
        command.append("-")
    return command


def execute(
    workload: dict[str, Any],
    worktree: Path,
    common_dir: Path,
    instruction_bundle: str,
    execution_id: str,
    timeout: int,
) -> dict[str, str]:
    with tempfile.TemporaryDirectory(
        prefix=".atenea-codex-result-",
        dir=worktree.parent,
    ) as temporary:
        jose = pwd.getpwnam("jose")
        os.chmod(temporary, 0o700)
        os.chown(temporary, jose.pw_uid, jose.pw_gid)
        final_path = Path(temporary) / "final.txt"
        resolv_path = Path(temporary) / "resolv.conf"
        instruction_mask_path = Path(temporary) / "empty-instructions"
        resolv_path.write_text("nameserver 1.1.1.1\noptions timeout:2 attempts:2\n", encoding="ascii")
        instruction_mask_path.write_bytes(b"")
        os.chmod(resolv_path, 0o600)
        os.chmod(instruction_mask_path, 0o600)
        os.chown(resolv_path, jose.pw_uid, jose.pw_gid)
        os.chown(instruction_mask_path, jose.pw_uid, jose.pw_gid)
        command = sandbox_command(
            workload,
            worktree,
            common_dir,
            final_path,
            resolv_path,
            instruction_mask_path,
            instruction_bundle,
            execution_id,
        )
        process = subprocess.Popen(
            command,
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
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
            stream, error_stream = process.communicate(workload["message"], timeout=timeout)
        except subprocess.TimeoutExpired:
            terminate(signal.SIGTERM, None)
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
                process.wait(timeout=5)
            reject("Codex execution failed")
        if process.returncode != 0:
            reject(codex_failure_reason(error_stream))
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
        result = {
            "threadId": thread_id,
            "turnId": execution_id,
            "finalAnswer": final_answer,
            "outputSummary": f"{CAPABILITY} completed",
        }
        if workload["kind"] == PROFILED_CAPABILITY:
            result.update({
                "outputSummary": f"{PROFILED_CAPABILITY} completed",
                "progressEvents": normalize_codex_events(stream),
                **effective_profile(workload),
            })
        return result


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
    validate_codex_version(workload)
    record = config["workspaces"][request["workspaceIdentity"]]
    common_dir = validate_worktree(worktree, record)
    instruction_bundle = validate_instruction_bundle(worktree)
    verified_attachments = validate_attachment_references(request, workload, config)
    if verified_attachments:
        reject("Codex execution failed: image delivery unavailable")
    try:
        result = execute(
            workload,
            worktree,
            common_dir,
            instruction_bundle,
            request["executionId"],
            args.timeout,
        )
    except SystemExit:
        raise
    except Exception as exception:
        reject(internal_failure_reason(exception))
    print(json.dumps(result, sort_keys=True, separators=(",", ":")))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except SystemExit:
        raise
    except Exception as exception:
        reject(internal_failure_reason(exception))
