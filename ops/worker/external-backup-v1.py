#!/usr/bin/env python3
"""Mediated encrypted external backup operations for AX42."""

from __future__ import annotations

import argparse
import fcntl
import hashlib
import json
import os
import re
import stat
import subprocess
import sys
import tempfile
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


WORKER_ID = "ax42-01"
POLICY_TAG = "atenea-authoritative-v1"
DEFAULT_STATE_ROOT = Path("/var/lib/atenea-external-backup-v1")
DEFAULT_STAGING_ROOT = Path("/srv/atenea/backups-staging")
DEFAULT_LOCK = Path("/run/lock/atenea-external-backup-v1.lock")
SOURCE_ROOTS = (
    Path("/srv/atenea/worker"),
    Path("/srv/atenea/attachments-v1"),
    Path("/srv/atenea/artifacts"),
)
CONFIG_ROOT = Path("/etc/atenea-worker")
CONFIG_SUFFIXES = (".json", ".enabled")
EXCLUDED_PREFIXES = (
    Path("/srv/atenea/worker/context-v1"),
    Path("/srv/atenea/worker/toolchain-v1"),
    Path("/srv/atenea/worker/workspace-v1"),
    Path("/srv/atenea/worker/workspace-locks"),
)
EXCLUDED_NAMES = {
    ".git",
    "__pycache__",
    "cache",
    "caches",
    "tmp",
    "temp",
}
PROHIBITED_SUFFIXES = (
    ".token",
    ".env",
    ".key",
    ".pem",
    ".cookie",
    ".cookies",
    ".dump",
)
SNAPSHOT_RE = re.compile(r"^[0-9a-f]{64}$")


class BackupError(RuntimeError):
    pass


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


def fail(message: str, code: int = 65) -> None:
    print(f"BACKUP_POLICY_BLOCKED: {message}", file=sys.stderr)
    raise SystemExit(code)


def mapped(path: Path, prefix: Path | None) -> Path:
    if prefix is None:
        return path
    return prefix / path.relative_to("/")


def canonical(path: Path) -> Path:
    return Path(os.path.abspath(os.fspath(path)))


def is_beneath(path: Path, root: Path) -> bool:
    try:
        path.relative_to(root)
        return True
    except ValueError:
        return False


def relative_identity(path: Path, prefix: Path | None) -> str:
    if prefix is None:
        return os.fspath(path)
    return "/" + os.fspath(path.relative_to(prefix))


def validate_test_prefix(raw: str | None) -> Path | None:
    if raw is None:
        return None
    prefix = canonical(Path(raw))
    if os.environ.get("ATENEA_BACKUP_TEST_MODE") != "1":
        raise BackupError("root prefix is available only in explicit test mode")
    if not is_beneath(prefix, Path("/tmp")) or prefix == Path("/tmp"):
        raise BackupError("test root prefix must be a dedicated path beneath /tmp")
    if prefix.is_symlink():
        raise BackupError("test root prefix must not be a symbolic link")
    return prefix


def excluded(path: Path, prefix: Path | None) -> bool:
    identity = Path(relative_identity(path, prefix))
    if any(is_beneath(identity, candidate) for candidate in EXCLUDED_PREFIXES):
        return True
    if any(part in EXCLUDED_NAMES for part in identity.parts):
        return True
    return False


def prohibited(path: Path, prefix: Path | None) -> bool:
    identity = relative_identity(path, prefix).lower()
    name = path.name.lower()
    if name in {"auth.json", "history.jsonl"}:
        return True
    if any(marker in identity for marker in ("/.codex/", "/manual-sessions/")):
        return True
    return name.endswith(PROHIBITED_SUFFIXES)


def approved_config(path: Path) -> bool:
    return path.name.endswith(CONFIG_SUFFIXES)


def hash_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        while chunk := handle.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def walk_root(root: Path, prefix: Path | None) -> list[Path]:
    if not root.exists() or not root.is_dir() or root.is_symlink():
        raise BackupError(f"required source root is missing or unsafe: {relative_identity(root, prefix)}")
    accepted: list[Path] = []
    for current, dirs, files in os.walk(root, topdown=True, followlinks=False):
        current_path = Path(current)
        kept_dirs: list[str] = []
        for name in sorted(dirs):
            candidate = current_path / name
            if excluded(candidate, prefix):
                continue
            if candidate.is_symlink():
                raise BackupError(
                    f"symbolic link rejected: {relative_identity(candidate, prefix)}"
                )
            kept_dirs.append(name)
        dirs[:] = kept_dirs
        for name in sorted(files):
            candidate = current_path / name
            if excluded(candidate, prefix):
                continue
            if candidate.is_symlink():
                raise BackupError(
                    f"symbolic link rejected: {relative_identity(candidate, prefix)}"
                )
            mode = candidate.lstat().st_mode
            if not stat.S_ISREG(mode):
                raise BackupError(
                    f"special file rejected: {relative_identity(candidate, prefix)}"
                )
            if prohibited(candidate, prefix):
                continue
            accepted.append(candidate)
    return accepted


def collect_files(prefix: Path | None) -> list[Path]:
    accepted: list[Path] = []
    for source in SOURCE_ROOTS:
        accepted.extend(walk_root(mapped(source, prefix), prefix))

    config_root = mapped(CONFIG_ROOT, prefix)
    if not config_root.exists() or not config_root.is_dir() or config_root.is_symlink():
        raise BackupError("non-secret worker configuration root is missing or unsafe")
    for candidate in sorted(config_root.iterdir()):
        if candidate.is_symlink():
            raise BackupError(
                f"symbolic link rejected: {relative_identity(candidate, prefix)}"
            )
        if candidate.is_file() and approved_config(candidate):
            if prohibited(candidate, prefix):
                continue
            accepted.append(candidate)
        elif candidate.is_dir() and candidate.name == "gates":
            accepted.extend(walk_root(candidate, prefix))

    unique = {canonical(path): path for path in accepted}
    if not unique:
        raise BackupError("source policy selected no files")
    return [unique[key] for key in sorted(unique, key=os.fspath)]


def manifest_entries(files: list[Path], prefix: Path | None) -> list[dict[str, Any]]:
    entries = []
    for path in files:
        metadata = path.stat()
        entries.append(
            {
                "path": relative_identity(path, prefix),
                "size": metadata.st_size,
                "mode": stat.S_IMODE(metadata.st_mode),
                "sha256": hash_file(path),
            }
        )
    return entries


def manifest_document(files: list[Path], prefix: Path | None) -> dict[str, Any]:
    entries = manifest_entries(files, prefix)
    normalized = json.dumps(entries, sort_keys=True, separators=(",", ":")).encode()
    return {
        "schemaVersion": 1,
        "workerId": WORKER_ID,
        "policyTag": POLICY_TAG,
        "fileCount": len(entries),
        "totalBytes": sum(entry["size"] for entry in entries),
        "manifestSha256": hashlib.sha256(normalized).hexdigest(),
        "files": entries,
    }


def atomic_json(path: Path, payload: dict[str, Any], mode: int = 0o600) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary_name = tempfile.mkstemp(prefix=f".{path.name}.", dir=path.parent)
    try:
        os.fchmod(descriptor, mode)
        with os.fdopen(descriptor, "w", encoding="utf-8") as handle:
            json.dump(payload, handle, sort_keys=True, indent=2)
            handle.write("\n")
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary_name, path)
    finally:
        if os.path.exists(temporary_name):
            os.unlink(temporary_name)


def require_repository() -> None:
    repository = os.environ.get("RESTIC_REPOSITORY", "")
    password_file = os.environ.get("RESTIC_PASSWORD_FILE", "")
    if not repository or not password_file:
        raise BackupError("repository inputs are not installed")
    password_path = Path(password_file)
    if not password_path.is_file() or password_path.is_symlink():
        raise BackupError("repository password file is missing or unsafe")
    if stat.S_IMODE(password_path.stat().st_mode) != 0o600:
        raise BackupError("repository password file must have mode 0600")
    if repository.startswith("b2:"):
        if not os.environ.get("B2_ACCOUNT_ID") or not os.environ.get("B2_ACCOUNT_KEY"):
            raise BackupError("B2 repository credentials are incomplete")


def run_restic(
    arguments: list[str], timeout: int, cwd: Path | None = None
) -> subprocess.CompletedProcess[str]:
    require_repository()
    command = ["restic", *arguments]
    try:
        return subprocess.run(
            command,
            check=True,
            capture_output=True,
            text=True,
            timeout=timeout,
            env=os.environ.copy(),
            cwd=cwd,
        )
    except FileNotFoundError as error:
        raise BackupError("restic is not installed") from error
    except subprocess.TimeoutExpired as error:
        raise BackupError(f"restic operation exceeded {timeout} seconds") from error
    except subprocess.CalledProcessError as error:
        safe_error = (error.stderr or "").splitlines()[-1:] or ["restic operation failed"]
        raise BackupError(safe_error[0][:240]) from error


def initialize(args: argparse.Namespace) -> dict[str, Any]:
    require_repository()
    try:
        current = run_restic(["snapshots", "--json"], args.timeout)
        payload = json.loads(current.stdout)
        if not isinstance(payload, list):
            raise BackupError("existing repository inventory is malformed")
        return {
            "schemaVersion": 1,
            "operation": "init",
            "state": "existing",
            "workerId": WORKER_ID,
            "policyTag": POLICY_TAG,
            "completedAt": utc_now(),
        }
    except BackupError as inventory_error:
        if "restic is not installed" in str(inventory_error):
            raise
    started = time.monotonic()
    run_restic(["init"], args.timeout)
    return {
        "schemaVersion": 1,
        "operation": "init",
        "state": "created",
        "workerId": WORKER_ID,
        "policyTag": POLICY_TAG,
        "completedAt": utc_now(),
        "durationMs": round((time.monotonic() - started) * 1000),
    }


def parse_json_stream(output: str) -> list[dict[str, Any]]:
    values = []
    for line in output.splitlines():
        line = line.strip()
        if line:
            values.append(json.loads(line))
    return values


def backup(args: argparse.Namespace, prefix: Path | None, state_root: Path) -> dict[str, Any]:
    files = collect_files(prefix)
    manifest = manifest_document(files, prefix)
    state_root.mkdir(parents=True, exist_ok=True)
    manifest_path = state_root / "latest-source-manifest.json"
    atomic_json(manifest_path, manifest)
    with tempfile.NamedTemporaryFile(
        mode="w", encoding="utf-8", prefix="backup-files.", dir=state_root, delete=False
    ) as handle:
        list_path = Path(handle.name)
        os.chmod(list_path, 0o600)
        for path in files:
            if prefix is None:
                handle.write(os.fspath(path))
            else:
                handle.write(os.fspath(path.relative_to(prefix)))
            handle.write("\n")
    started = time.monotonic()
    try:
        result = run_restic(
            [
                "backup",
                "--json",
                "--host",
                WORKER_ID,
                "--tag",
                POLICY_TAG,
                "--files-from",
                os.fspath(list_path),
            ],
            args.timeout,
            cwd=prefix,
        )
    finally:
        list_path.unlink(missing_ok=True)
    summaries = [
        item for item in parse_json_stream(result.stdout) if item.get("message_type") == "summary"
    ]
    if len(summaries) != 1 or not SNAPSHOT_RE.fullmatch(summaries[0].get("snapshot_id", "")):
        raise BackupError("restic did not return one exact snapshot identity")
    record = {
        "schemaVersion": 1,
        "operation": "backup",
        "workerId": WORKER_ID,
        "policyTag": POLICY_TAG,
        "snapshotId": summaries[0]["snapshot_id"],
        "manifestSha256": manifest["manifestSha256"],
        "fileCount": manifest["fileCount"],
        "totalBytes": manifest["totalBytes"],
        "completedAt": utc_now(),
        "durationMs": round((time.monotonic() - started) * 1000),
    }
    atomic_json(state_root / "last-backup.json", record)
    return record


def snapshots(args: argparse.Namespace) -> list[dict[str, Any]]:
    result = run_restic(
        ["snapshots", "--json", "--host", WORKER_ID, "--tag", POLICY_TAG],
        args.timeout,
    )
    payload = json.loads(result.stdout)
    if not isinstance(payload, list):
        raise BackupError("snapshot inventory is malformed")
    return payload


def exact_latest_snapshot(args: argparse.Namespace) -> str:
    values = snapshots(args)
    if not values:
        raise BackupError("no exact-host policy snapshot exists")
    ordered = sorted(values, key=lambda item: (item.get("time", ""), item.get("id", "")))
    latest = ordered[-1]
    identifier = latest.get("id", "")
    if not SNAPSHOT_RE.fullmatch(identifier):
        raise BackupError("latest snapshot identity is malformed")
    if len(ordered) > 1 and ordered[-2].get("time") == latest.get("time"):
        raise BackupError("latest snapshot selection is ambiguous")
    return identifier


def check_repository(args: argparse.Namespace, state_root: Path) -> dict[str, Any]:
    expected = exact_latest_snapshot(args)
    started = time.monotonic()
    run_restic(["check"], args.timeout)
    record = {
        "schemaVersion": 1,
        "operation": "check",
        "workerId": WORKER_ID,
        "policyTag": POLICY_TAG,
        "snapshotId": expected,
        "completedAt": utc_now(),
        "durationMs": round((time.monotonic() - started) * 1000),
    }
    atomic_json(state_root / "last-check.json", record)
    return record


def load_record(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise BackupError(f"required state record is missing or malformed: {path.name}") from error
    return value


def retain(args: argparse.Namespace, state_root: Path) -> dict[str, Any]:
    last_backup = load_record(state_root / "last-backup.json")
    last_check = load_record(state_root / "last-check.json")
    latest = exact_latest_snapshot(args)
    if (
        last_backup.get("snapshotId") != latest
        or last_check.get("snapshotId") != latest
        or last_backup.get("workerId") != WORKER_ID
        or last_check.get("workerId") != WORKER_ID
        or last_backup.get("policyTag") != POLICY_TAG
        or last_check.get("policyTag") != POLICY_TAG
    ):
        raise BackupError("retention ownership or prerequisite state is ambiguous")
    command = [
        "forget",
        "--json",
        "--host",
        WORKER_ID,
        "--tag",
        POLICY_TAG,
        "--keep-daily",
        "14",
        "--keep-weekly",
        "8",
        "--keep-monthly",
        "12",
    ]
    if args.apply:
        command.append("--prune")
    else:
        command.append("--dry-run")
    started = time.monotonic()
    run_restic(command, args.timeout)
    return {
        "schemaVersion": 1,
        "operation": "retain",
        "mode": "apply" if args.apply else "dry-run",
        "workerId": WORKER_ID,
        "policyTag": POLICY_TAG,
        "snapshotId": latest,
        "completedAt": utc_now(),
        "durationMs": round((time.monotonic() - started) * 1000),
    }


def validate_restore_target(target: Path, staging_root: Path) -> Path:
    target = canonical(target)
    staging_root = canonical(staging_root)
    restore_root = staging_root / "restore-tests"
    if not is_beneath(target, restore_root) or target == restore_root:
        raise BackupError("restore target must be a dedicated path beneath restore-tests")
    if target.exists() or target.is_symlink():
        if target.is_symlink() or not target.is_dir():
            raise BackupError("restore target must not be a symbolic link or special file")
        if any(target.iterdir()):
            raise BackupError("restore target must be empty and newly allocated")
        raise BackupError("restore target must be newly allocated by this operation")
    target.mkdir(parents=True, exist_ok=False)
    return target


def compare_restore(target: Path, source_manifest: dict[str, Any]) -> dict[str, Any]:
    restored_entries = []
    for expected in source_manifest.get("files", []):
        relative = expected["path"].lstrip("/")
        path = target / relative
        if not path.is_file() or path.is_symlink():
            raise BackupError(f"restored file is missing or unsafe: {expected['path']}")
        metadata = path.stat()
        restored_entries.append(
            {
                "path": expected["path"],
                "size": metadata.st_size,
                "mode": stat.S_IMODE(metadata.st_mode),
                "sha256": hash_file(path),
            }
        )
    normalized = json.dumps(
        restored_entries, sort_keys=True, separators=(",", ":")
    ).encode()
    restored_hash = hashlib.sha256(normalized).hexdigest()
    if restored_hash != source_manifest.get("manifestSha256"):
        raise BackupError("restored manifest does not match the accepted source manifest")
    return {
        "fileCount": len(restored_entries),
        "totalBytes": sum(entry["size"] for entry in restored_entries),
        "manifestSha256": restored_hash,
    }


def restore(args: argparse.Namespace, state_root: Path, staging_root: Path) -> dict[str, Any]:
    snapshot = args.snapshot or exact_latest_snapshot(args)
    if not SNAPSHOT_RE.fullmatch(snapshot):
        raise BackupError("restore requires one immutable snapshot identity")
    source_manifest = load_record(state_root / "latest-source-manifest.json")
    target = validate_restore_target(Path(args.target), staging_root)
    started = time.monotonic()
    try:
        run_restic(["restore", snapshot, "--target", os.fspath(target)], args.timeout)
        comparison = compare_restore(target, source_manifest)
    except Exception:
        raise
    return {
        "schemaVersion": 1,
        "operation": "restore",
        "workerId": WORKER_ID,
        "policyTag": POLICY_TAG,
        "snapshotId": snapshot,
        "targetIdentity": target.name,
        **comparison,
        "completedAt": utc_now(),
        "durationMs": round((time.monotonic() - started) * 1000),
    }


def acquire_lock(path: Path, timeout: int):
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor = path.open("a+")
    deadline = time.monotonic() + timeout
    while True:
        try:
            fcntl.flock(descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
            return descriptor
        except BlockingIOError:
            if time.monotonic() >= deadline:
                descriptor.close()
                raise BackupError("timed out waiting for backup serialization")
            time.sleep(0.1)


def parser() -> argparse.ArgumentParser:
    value = argparse.ArgumentParser()
    value.add_argument(
        "operation", choices=("manifest", "init", "backup", "check", "retain", "restore")
    )
    value.add_argument("--root-prefix")
    value.add_argument("--state-root", default=os.fspath(DEFAULT_STATE_ROOT))
    value.add_argument("--staging-root", default=os.fspath(DEFAULT_STAGING_ROOT))
    value.add_argument("--lock", default=os.fspath(DEFAULT_LOCK))
    value.add_argument("--lock-timeout", type=int, default=30)
    value.add_argument("--timeout", type=int, default=3600)
    value.add_argument("--target")
    value.add_argument("--snapshot")
    value.add_argument("--apply", action="store_true")
    return value


def main() -> int:
    args = parser().parse_args()
    if args.timeout < 1 or args.timeout > 14400:
        fail("operation timeout must be between 1 and 14400 seconds")
    if args.lock_timeout < 1 or args.lock_timeout > 300:
        fail("lock timeout must be between 1 and 300 seconds")
    try:
        prefix = validate_test_prefix(args.root_prefix)
        state_root = canonical(Path(args.state_root))
        staging_root = canonical(Path(args.staging_root))
        lock_path = canonical(Path(args.lock))
        if prefix is not None:
            for candidate in (state_root, staging_root, lock_path):
                if not is_beneath(candidate, Path("/tmp")):
                    raise BackupError("test state, staging and lock paths must remain beneath /tmp")
        lock = acquire_lock(lock_path, args.lock_timeout)
        try:
            if args.operation == "manifest":
                result = manifest_document(collect_files(prefix), prefix)
            elif args.operation == "init":
                result = initialize(args)
            elif args.operation == "backup":
                result = backup(args, prefix, state_root)
            elif args.operation == "check":
                result = check_repository(args, state_root)
            elif args.operation == "retain":
                result = retain(args, state_root)
            else:
                if not args.target:
                    raise BackupError("restore requires --target")
                result = restore(args, state_root, staging_root)
        finally:
            lock.close()
        print(json.dumps(result, sort_keys=True, separators=(",", ":")))
        return 0
    except BackupError as error:
        fail(str(error))
    return 65


if __name__ == "__main__":
    raise SystemExit(main())
