#!/usr/bin/env python3
"""Create and inspect the exact synthetic Beautips WorkSession secret boundary."""

from __future__ import annotations

import argparse
import grp
import importlib.util
import json
import os
import pwd
import secrets
import stat
import sys
import uuid
from pathlib import Path
from typing import Any, Callable

MEDIATOR_PATH = Path(__file__).resolve().with_name("beautips-operation-mediator-v1.py")
SPEC = importlib.util.spec_from_file_location("beautips_operation_mediator_v1", MEDIATOR_PATH)
if SPEC is None or SPEC.loader is None:
    print("BEAUTIPS_SECRET_REJECTED: operation mediator unavailable", file=sys.stderr)
    raise SystemExit(65)
MEDIATOR = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MEDIATOR)

SECRET_GENERATORS: dict[str, Callable[[str], str]] = {
    "BEAUTIPS_SYNTHETIC_POSTGRES_PASSWORD": lambda _session: secrets.token_hex(32),
    "BEAUTIPS_SYNTHETIC_SMOKE_ADMIN_EMAIL": (
        lambda session: f"managed-{session.replace('-', '')[:16]}@beautips.invalid"
    ),
    "BEAUTIPS_SYNTHETIC_SMOKE_ADMIN_PASSWORD": lambda _session: secrets.token_hex(32),
    "BEAUTIPS_SYNTHETIC_SMOKE_SEAL_CODE": (
        lambda _session: f"{secrets.randbelow(100_000_000):08d}"
    ),
}
METADATA_NAME = "metadata-v1.json"


class SecretRejected(RuntimeError):
    pass


def reject(message: str) -> None:
    raise SecretRejected(message)


def canonical_session(value: str) -> str:
    try:
        parsed = str(uuid.UUID(value))
    except (ValueError, AttributeError):
        reject("canonical WorkSession UUID required")
    if parsed != value or value.lower() != value:
        reject("canonical WorkSession UUID required")
    return parsed


def owner() -> tuple[int, int, str, str]:
    if os.environ.get("ATENEA_BEAUTIPS_MEDIATOR_TEST_MODE") == "1":
        return os.getuid(), os.getgid(), pwd.getpwuid(os.getuid()).pw_name, grp.getgrgid(os.getgid()).gr_name
    if os.geteuid() != 0:
        reject("installed secret preparation requires root")
    account = pwd.getpwnam("atenea-worker")
    group = grp.getgrnam("atenea")
    return account.pw_uid, group.gr_gid, account.pw_name, group.gr_name


def context(session: str) -> tuple[Path, dict[str, Any]]:
    root, source = MEDIATOR.roots()
    project, _operations = MEDIATOR.validate_registry(source)
    allocation, _worktree = MEDIATOR.validate_allocation(root, session, project)
    runtime_root = Path(allocation["runtimeRoot"])
    expected = (
        root
        / "workspaces"
        / "sessions"
        / session
        / "runtime"
        / allocation["runtimeId"]
    )
    if runtime_root != expected or not runtime_root.is_dir() or runtime_root.is_symlink():
        reject("exact runtime secret parent rejected")
    return runtime_root / "secrets", allocation


def assert_regular_owned(path: Path, uid: int, gid: int) -> None:
    try:
        details = path.lstat()
    except OSError:
        reject("secret boundary entry unavailable")
    if (
        not stat.S_ISREG(details.st_mode)
        or path.is_symlink()
        or details.st_uid != uid
        or details.st_gid != gid
        or stat.S_IMODE(details.st_mode) != 0o600
    ):
        reject("secret boundary entry ownership rejected")


def assert_directory(path: Path, uid: int, gid: int) -> None:
    try:
        details = path.lstat()
    except OSError:
        reject("secret boundary directory unavailable")
    if (
        not stat.S_ISDIR(details.st_mode)
        or path.is_symlink()
        or details.st_uid != uid
        or details.st_gid != gid
        or stat.S_IMODE(details.st_mode) != 0o700
    ):
        reject("secret boundary directory ownership rejected")


def ensure_directory(path: Path, uid: int, gid: int) -> None:
    if not path.exists():
        path.mkdir(mode=0o700)
        os.chown(path, uid, gid)
    assert_directory(path, uid, gid)


def write_once(path: Path, value: str, uid: int, gid: int) -> None:
    if path.exists() or path.is_symlink():
        assert_regular_owned(path, uid, gid)
        try:
            size = path.stat().st_size
        except OSError:
            reject("secret boundary entry unavailable")
        if not 4 <= size <= 256:
            reject("secret boundary entry size rejected")
        return
    try:
        descriptor = os.open(
            path,
            os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_NOFOLLOW", 0),
            0o600,
        )
        with os.fdopen(descriptor, "w", encoding="utf-8") as handle:
            handle.write(value + "\n")
            handle.flush()
            os.fsync(handle.fileno())
        os.chown(path, uid, gid)
    except OSError:
        reject("secret boundary creation failed")
    assert_regular_owned(path, uid, gid)


def metadata(session: str, allocation: dict[str, Any], owner_name: str, group_name: str) -> dict[str, Any]:
    return {
        "schemaVersion": "beautips-secret-boundary-v1",
        "sessionId": session,
        "runtimeId": allocation["runtimeId"],
        "projectId": "beautips",
        "slot": allocation["slot"],
        "syntheticOnly": True,
        "manualEnvAccepted": False,
        "whatsAppCredentialsAccepted": False,
        "owner": owner_name,
        "group": group_name,
        "mode": "0600",
        "names": sorted(SECRET_GENERATORS),
    }


def validate_entries(root: Path, uid: int, gid: int) -> None:
    allowed = set(SECRET_GENERATORS) | {METADATA_NAME}
    try:
        entries = list(root.iterdir())
    except OSError:
        reject("secret boundary directory unavailable")
    for entry in entries:
        if entry.name not in allowed:
            reject("unlabelled, manual or external secret entry rejected")
        assert_regular_owned(entry, uid, gid)


def prepare(session: str) -> dict[str, Any]:
    uid, gid, owner_name, group_name = owner()
    secret_root, allocation = context(session)
    ensure_directory(secret_root, uid, gid)
    validate_entries(secret_root, uid, gid)
    for name, generator in SECRET_GENERATORS.items():
        write_once(secret_root / name, generator(session), uid, gid)
    document = metadata(session, allocation, owner_name, group_name)
    metadata_path = secret_root / METADATA_NAME
    if metadata_path.exists():
        assert_regular_owned(metadata_path, uid, gid)
        try:
            existing = json.loads(metadata_path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            reject("secret metadata rejected")
        if existing != document:
            reject("secret metadata identity rejected")
    else:
        write_once(metadata_path, json.dumps(document, sort_keys=True, separators=(",", ":")), uid, gid)
    validate_entries(secret_root, uid, gid)
    return {
        "schemaVersion": "beautips-secret-boundary-result-v1",
        "state": "ready",
        "sessionId": session,
        "runtimeId": allocation["runtimeId"],
        "projectId": "beautips",
        "slot": allocation["slot"],
        "secretRoot": str(secret_root),
        "names": sorted(SECRET_GENERATORS),
        "valuesExposed": False,
    }


def inspect(session: str) -> dict[str, Any]:
    uid, gid, _owner_name, _group_name = owner()
    secret_root, allocation = context(session)
    assert_directory(secret_root, uid, gid)
    validate_entries(secret_root, uid, gid)
    expected = set(SECRET_GENERATORS) | {METADATA_NAME}
    if {entry.name for entry in secret_root.iterdir()} != expected:
        reject("secret boundary is incomplete")
    return {
        "schemaVersion": "beautips-secret-boundary-result-v1",
        "state": "ready",
        "sessionId": session,
        "runtimeId": allocation["runtimeId"],
        "projectId": "beautips",
        "slot": allocation["slot"],
        "secretRoot": str(secret_root),
        "names": sorted(SECRET_GENERATORS),
        "valuesExposed": False,
    }


def main() -> int:
    parser = argparse.ArgumentParser(allow_abbrev=False)
    parser.add_argument("action", choices=("prepare", "inspect"))
    parser.add_argument("--session", required=True)
    arguments = parser.parse_args()
    try:
        session = canonical_session(arguments.session)
        result = prepare(session) if arguments.action == "prepare" else inspect(session)
    except (SecretRejected, MEDIATOR.Rejected) as error:
        print(f"BEAUTIPS_SECRET_REJECTED: {error}", file=sys.stderr)
        return 65
    print(json.dumps(result, sort_keys=True, separators=(",", ":")))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
