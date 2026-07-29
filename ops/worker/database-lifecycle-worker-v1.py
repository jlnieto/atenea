#!/usr/bin/env python3

from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import os
import pwd
import secrets
import shutil
import stat
import subprocess
import sys
import tempfile
import time
import uuid
from pathlib import Path
from typing import Any

PROTOCOL = "database-lifecycle/v1"
ENGINE_LABEL = "atenea-database-lifecycle-v1"
MUTATING_ACTIONS = {
    "register",
    "create",
    "migrate",
    "seed",
    "snapshot",
    "prepare-replace",
    "replace",
    "restore",
    "stop",
    "cleanup",
    "retain",
}
DISABLED_ROLLBACK_ACTIONS = {"stop", "cleanup"}


def load_state_module():
    path = Path(__file__).with_name("database-lifecycle-state-v1.py")
    spec = importlib.util.spec_from_file_location("database_lifecycle_state_v1", path)
    if spec is None or spec.loader is None:
        raise RuntimeError("state module is unavailable")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


STATE = load_state_module()
LifecycleError = STATE.LifecycleError


def fail(code: str, message: str) -> None:
    raise LifecycleError(code, message)


def read_json(path: Path, code: str) -> dict[str, Any]:
    if not path.is_file() or path.is_symlink():
        fail(code, "required persisted document is missing or unsafe")
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise LifecycleError(code, "persisted document is invalid") from error
    if not isinstance(value, dict):
        fail(code, "persisted document is invalid")
    return value


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


class Docker:
    def __init__(self, slot: str, timeout_seconds: int = 120):
        self.slot = slot
        self.timeout_seconds = timeout_seconds
        owner = pwd.getpwnam(f"atenea-{slot}")
        socket_path = Path(f"/run/user/{owner.pw_uid}/docker.sock")
        metadata = socket_path.stat()
        if not stat.S_ISSOCK(metadata.st_mode) or metadata.st_uid != owner.pw_uid:
            fail("DATABASE_ENGINE_UNAVAILABLE", "exact rootless slot socket is unsafe")
        self.socket = f"unix://{socket_path}"

    def run(
        self,
        argv: list[str],
        *,
        input_bytes: bytes | None = None,
        capture: bool = True,
        output_file=None,
    ) -> subprocess.CompletedProcess:
        command = [
            "/usr/bin/sudo",
            "-n",
            "-u",
            f"atenea-{self.slot}",
            "/usr/bin/env",
            f"DOCKER_HOST={self.socket}",
            "/usr/bin/docker",
            *argv,
        ]
        try:
            return subprocess.run(
                command,
                input=input_bytes,
                stdout=subprocess.PIPE if capture and output_file is None else output_file,
                stderr=subprocess.PIPE,
                timeout=self.timeout_seconds,
                check=True,
            )
        except subprocess.TimeoutExpired as error:
            raise LifecycleError("DATABASE_TIMEOUT", "bounded Docker operation timed out") from error
        except subprocess.CalledProcessError as error:
            detail = error.stderr.decode("utf-8", "replace").strip()[-600:]
            raise LifecycleError(
                "DATABASE_ENGINE_FAILED",
                f"rootless Docker operation failed: {detail}",
            ) from error

    def inspect(self, kind: str, identity: str) -> dict[str, Any] | None:
        try:
            result = self.run([kind, "inspect", identity])
        except LifecycleError as error:
            if error.code == "DATABASE_ENGINE_FAILED":
                return None
            raise
        parsed = json.loads(result.stdout)
        if not isinstance(parsed, list) or len(parsed) != 1:
            fail("DATABASE_RESOURCE_AMBIGUOUS", "resource inspection was ambiguous")
        return parsed[0]


class Lifecycle:
    def __init__(self):
        test_mode = os.environ.get("ATENEA_DATABASE_TEST_MODE") == "1"
        if test_mode:
            root = Path(os.environ["ATENEA_DATABASE_TEST_ROOT"]).resolve()
            if not str(root).startswith("/tmp/") or ".." in root.parts:
                fail("DATABASE_CONFIGURATION_INVALID", "test root must be beneath /tmp")
            self.workspace_root = root / "workspaces"
            self.state_root = root / "state"
            self.snapshot_root = root / "snapshot-content"
            self.secret_root = root / "secrets"
            self.enabled_marker = root / "enabled"
        else:
            if os.geteuid() != 0:
                fail("DATABASE_OWNERSHIP_CONFLICT", "mediator must run as root")
            self.workspace_root = Path("/srv/atenea/workspaces")
            self.state_root = Path("/srv/atenea/worker/database-lifecycle-v1")
            self.snapshot_root = Path("/srv/atenea/database-snapshots-v1")
            self.secret_root = Path("/run/atenea-database-v1")
            self.enabled_marker = Path("/etc/atenea-worker/database-lifecycle-v1.enabled")
        self.registry = STATE.DatabaseRegistry(self.state_root)
        self.test_mode = test_mode

    def require_enabled(self) -> None:
        if not self.enabled_marker.is_file() or self.enabled_marker.is_symlink():
            fail("DATABASE_LIFECYCLE_DISABLED", "new database lifecycle operations are disabled")

    def paths(self, session_id: str) -> tuple[Path, Path, Path]:
        session = STATE.canonical_uuid(session_id, "WORKSESSION_ID_INVALID")
        session_root = self.workspace_root / "sessions" / session
        allocation_path = session_root / "runtime-allocation-v1.json"
        allocation = read_json(allocation_path, "ALLOCATION_INVALID")
        required = {
            "schemaVersion",
            "sessionId",
            "projectId",
            "worktreePath",
            "manifestRelativePath",
            "slot",
            "state",
            "allocatedPorts",
        }
        if (
            not required.issubset(allocation)
            or allocation["schemaVersion"] != 1
            or allocation["sessionId"] != session
            or allocation["state"] != "allocated"
            or not STATE.PROJECT_PATTERN.fullmatch(str(allocation["projectId"]))
            or not STATE.SLOT_PATTERN.fullmatch(str(allocation["slot"]))
        ):
            fail("ALLOCATION_INVALID", "persisted allocation values are invalid")
        worktree = Path(str(allocation["worktreePath"]))
        expected_worktree = session_root / str(allocation["projectId"])
        if (
            worktree != expected_worktree
            or not worktree.is_dir()
            or worktree.is_symlink()
            or Path(str(allocation["manifestRelativePath"])).is_absolute()
            or ".." in Path(str(allocation["manifestRelativePath"])).parts
        ):
            fail("DATABASE_OWNERSHIP_CONFLICT", "persisted worktree identity is unsafe")
        manifest = worktree / str(allocation["manifestRelativePath"])
        return allocation_path, manifest, worktree

    def allocation(self, session_id: str) -> tuple[dict[str, Any], dict[str, Any], Path]:
        allocation_path, manifest_path, worktree = self.paths(session_id)
        allocation = read_json(allocation_path, "ALLOCATION_INVALID")
        manifest = read_json(manifest_path, "DATABASE_MANIFEST_INVALID")
        database = STATE.validate_database_manifest(manifest)
        if manifest.get("project", {}).get("id") != allocation["projectId"]:
            fail("DATABASE_OWNERSHIP_CONFLICT", "manifest and allocation projects differ")
        matching = [
            item
            for item in allocation["allocatedPorts"]
            if isinstance(item, dict) and item.get("name") == database["portName"]
        ]
        if len(matching) != 1 or matching[0].get("bindAddress") != "127.0.0.1":
            fail("DATABASE_ENDPOINT_CONFLICT", "database endpoint is not exact and loopback-only")
        return allocation, manifest, manifest_path

    @staticmethod
    def allocation_fingerprint(allocation_path: Path) -> str:
        return sha256_file(allocation_path)

    def register(self, database_id: str, session_id: str) -> dict[str, Any]:
        allocation_path, _, _ = self.paths(session_id)
        allocation, manifest, manifest_path = self.allocation(session_id)
        database = STATE.validate_database_manifest(manifest)
        record, created = self.registry.create(
            database_id=database_id,
            work_session_id=session_id,
            project_id=allocation["projectId"],
            worker_id="ax42-01",
            allocation_identity=allocation["runtimeId"],
            allocation_fingerprint=self.allocation_fingerprint(allocation_path),
            slot=allocation["slot"],
            engine=database["engine"],
            database_name=database["databaseName"],
            manifest_sha256=sha256_file(manifest_path),
        )
        return {"record": record, "created": created}

    def context(self, database_id: str) -> tuple[dict[str, Any], dict[str, Any], Path, Docker]:
        record = self.registry.read(database_id)
        allocation_path, _, _ = self.paths(record["workSessionId"])
        allocation, manifest, manifest_path = self.allocation(record["workSessionId"])
        immutable = {
            "projectId": allocation["projectId"],
            "slot": allocation["slot"],
            "allocationIdentity": allocation["runtimeId"],
            "allocationFingerprint": self.allocation_fingerprint(allocation_path),
            "manifestSha256": sha256_file(manifest_path),
        }
        if any(record[key] != value for key, value in immutable.items()):
            fail("DATABASE_OWNERSHIP_CONFLICT", "persisted database ownership is stale")
        return record, manifest, manifest_path.parent, Docker(record["slot"])

    @staticmethod
    def labels(record: dict[str, Any]) -> dict[str, str]:
        return {
            "com.atenea.engine": ENGINE_LABEL,
            "com.atenea.database": record["databaseId"],
            "com.atenea.session": record["workSessionId"],
            "com.atenea.project": record["projectId"],
            "com.atenea.worker": record["workerId"],
            "com.atenea.allocation": record["allocationIdentity"],
            "com.atenea.slot": record["slot"],
            "com.atenea.manifest-sha256": record["manifestSha256"],
            "com.atenea.synthetic": "true",
        }

    def assert_resource(
        self, docker: Docker, kind: str, identity: str, record: dict[str, Any], *, absent_ok: bool
    ) -> dict[str, Any] | None:
        resource = docker.inspect(kind, identity)
        if resource is None:
            if absent_ok:
                return None
            fail("DATABASE_RESOURCE_MISSING", f"owned {kind} is missing")
        labels = (
            resource.get("Config", {}).get("Labels", {})
            if kind == "container"
            else resource.get("Labels", {})
        )
        expected = self.labels(record)
        if not isinstance(labels, dict) or any(labels.get(k) != v for k, v in expected.items()):
            fail("DATABASE_RESOURCE_FOREIGN", f"{kind} labels are partial, foreign or ambiguous")
        return resource

    def assert_projection(self, record: dict[str, Any], docker: Docker, *, absent_ok: bool) -> None:
        self.assert_resource(docker, "container", record["containerIdentity"], record, absent_ok=absent_ok)
        self.assert_resource(docker, "network", record["networkIdentity"], record, absent_ok=absent_ok)
        self.assert_resource(docker, "volume", record["volumeIdentity"], record, absent_ok=absent_ok)

    def secret_paths(self, record: dict[str, Any]) -> tuple[Path, Path]:
        if self.test_mode:
            root = self.secret_root / record["slot"] / record["databaseId"]
        else:
            owner = pwd.getpwnam(f"atenea-{record['slot']}")
            root = (
                Path(f"/run/user/{owner.pw_uid}")
                / "atenea-database-v1"
                / record["databaseId"]
            )
        return root / "password", root

    def ensure_secret(self, record: dict[str, Any]) -> Path:
        path, root = self.secret_paths(record)
        if self.test_mode:
            root.mkdir(parents=True, mode=0o700, exist_ok=True)
            root.chmod(0o700)
            owner = None
        else:
            slot_owner = pwd.getpwnam(f"atenea-{record['slot']}")
            runtime_root = root.parent.parent
            if (
                not runtime_root.is_dir()
                or runtime_root.is_symlink()
                or runtime_root.stat().st_uid != slot_owner.pw_uid
            ):
                fail("DATABASE_SECRET_INVALID", "rootless runtime directory is unsafe")
            slot_root = root.parent
            slot_root.mkdir(mode=0o700, exist_ok=True)
            os.chown(slot_root, slot_owner.pw_uid, slot_owner.pw_gid)
            root.mkdir(mode=0o700, exist_ok=True)
            os.chown(root, slot_owner.pw_uid, slot_owner.pw_gid)
            owner = slot_owner
        if not path.exists():
            descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(descriptor, "w", encoding="utf-8") as handle:
                handle.write(secrets.token_urlsafe(32) + "\n")
            if owner is not None:
                os.chown(path, owner.pw_uid, owner.pw_gid)
        if (
            not path.is_file()
            or path.is_symlink()
            or (path.stat().st_mode & 0o077)
            or (owner is not None and path.stat().st_uid != owner.pw_uid)
        ):
            fail("DATABASE_SECRET_INVALID", "named ephemeral secret file is unsafe")
        return path

    def docker_labels(self, record: dict[str, Any]) -> list[str]:
        result: list[str] = []
        for key, value in self.labels(record).items():
            result.extend(["--label", f"{key}={value}"])
        return result

    def create(self, database_id: str) -> dict[str, Any]:
        record, manifest, _, docker = self.context(database_id)
        if record["state"] not in {"ALLOCATED", "STOPPED", "CREATED"}:
            fail("DATABASE_TRANSITION_INVALID", "create is not valid in the current state")
        existing = docker.inspect("container", record["containerIdentity"])
        if existing is not None:
            self.assert_projection(record, docker, absent_ok=False)
            if record["state"] == "CREATED":
                return record
            if record["state"] == "STOPPED":
                docker.run(["container", "start", record["containerIdentity"]])
                return self.registry.transition(
                    database_id,
                    record["lifecycleRevision"],
                    "CREATED",
                    desired_state="RUNNING",
                )
            fail("DATABASE_RESOURCE_AMBIGUOUS", "container exists outside the expected state")
        for kind, identity in (
            ("network", record["networkIdentity"]),
            ("volume", record["volumeIdentity"]),
        ):
            if docker.inspect(kind, identity) is not None:
                fail("DATABASE_RESOURCE_AMBIGUOUS", f"unexpected pre-existing {kind}")
        secret = self.ensure_secret(record)
        labels = self.docker_labels(record)
        docker.run(["network", "create", *labels, "--internal", record["networkIdentity"]])
        docker.run(["volume", "create", *labels, record["volumeIdentity"]])
        database = manifest["database"]
        internal_port = 5432 if record["engine"] == "postgresql" else 3306
        run = [
            "run", "-d", "--name", record["containerIdentity"], *labels,
            "--network", record["networkIdentity"],
            "--network-alias", "database",
            "--mount", f"type=volume,src={record['volumeIdentity']},dst={'/var/lib/postgresql/data' if record['engine'] == 'postgresql' else '/var/lib/mysql'}",
            "--mount", f"type=bind,src={secret},dst=/run/secrets/database-password,readonly",
        ]
        if record["engine"] == "postgresql":
            run.extend([
                "-e", "POSTGRES_USER=synthetic_user",
                "-e", f"POSTGRES_DB={record['databaseName']}",
                "-e", "POSTGRES_PASSWORD_FILE=/run/secrets/database-password",
            ])
        else:
            run.extend([
                "-e", f"MARIADB_DATABASE={record['databaseName']}",
                "-e", "MARIADB_USER=synthetic_user",
                "-e", "MARIADB_PASSWORD_FILE=/run/secrets/database-password",
                "-e", "MARIADB_ROOT_PASSWORD_FILE=/run/secrets/database-password",
            ])
        run.extend(["--expose", str(internal_port), database["image"]])
        try:
            docker.run(run)
        except Exception:
            for args in (
                ["container", "rm", "-f", record["containerIdentity"]],
                ["network", "rm", record["networkIdentity"]],
                ["volume", "rm", record["volumeIdentity"]],
            ):
                try:
                    docker.run(args)
                except LifecycleError:
                    pass
            raise
        return self.registry.transition(
            database_id, record["lifecycleRevision"], "CREATED", desired_state="RUNNING"
        )

    def wait_health(self, record: dict[str, Any], docker: Docker, seconds: int = 90) -> None:
        deadline = time.monotonic() + seconds
        command = self.client_command(record, "SELECT 1")
        while time.monotonic() < deadline:
            try:
                docker.run(command)
                return
            except LifecycleError:
                time.sleep(1)
        fail("DATABASE_HEALTH_TIMEOUT", "database did not become healthy within 90 seconds")

    @staticmethod
    def client_command(record: dict[str, Any], sql: str | None = None) -> list[str]:
        if record["engine"] == "postgresql":
            command = [
                "exec", "-i",
                record["containerIdentity"], "sh", "-ceu",
                'database="$1"; shift; umask 077; credential="$(mktemp)"; '
                'trap \'rm -f "$credential"\' EXIT; '
                'printf "*:*:%s:synthetic_user:%s\\n" "$database" "$(cat /run/secrets/database-password)" >"$credential"; '
                'export PGPASSFILE="$credential"; psql -v ON_ERROR_STOP=1 -U synthetic_user -d "$database" "$@"',
                "database-client", record["databaseName"],
            ]
            if sql is not None:
                command.extend(["-c", sql])
            return command
        command = [
            "exec", "-i", record["containerIdentity"], "sh", "-ceu",
            'database="$1"; shift; umask 077; credential="$(mktemp)"; '
            'trap \'rm -f "$credential"\' EXIT; '
            'printf "[client]\\nuser=synthetic_user\\npassword=%s\\nprotocol=socket\\n" '
            '"$(cat /run/secrets/database-password)" >"$credential"; '
            'mariadb --defaults-extra-file="$credential" "$database" "$@"',
            "database-client", record["databaseName"],
        ]
        if sql is not None:
            command.extend(["-e", sql])
        return command

    def apply_sql(self, database_id: str, kind: str) -> dict[str, Any]:
        record, manifest, worktree, docker = self.context(database_id)
        required = "CREATED" if kind == "migrationPaths" else "MIGRATED"
        target = "MIGRATED" if kind == "migrationPaths" else "SEEDED"
        completed_states = (
            {"MIGRATED", "SEEDED", "HEALTHY"}
            if kind == "migrationPaths"
            else {"SEEDED", "HEALTHY"}
        )
        if record["state"] in completed_states:
            return record
        if record["state"] != required:
            fail("DATABASE_TRANSITION_INVALID", "SQL operation is not valid in current state")
        self.assert_projection(record, docker, absent_ok=False)
        self.wait_health(record, docker)
        for relative in manifest["database"][kind]:
            path = worktree / relative
            if (
                not path.is_file()
                or path.is_symlink()
                or worktree.resolve() not in path.resolve().parents
                or path.stat().st_size > 1024 * 1024
            ):
                fail("DATABASE_INPUT_INVALID", "migration or seed input is unsafe")
            docker.run(self.client_command(record), input_bytes=path.read_bytes())
        return self.registry.transition(
            database_id, record["lifecycleRevision"], target, desired_state="RUNNING"
        )

    def health(self, database_id: str) -> dict[str, Any]:
        record, _, _, docker = self.context(database_id)
        self.assert_projection(record, docker, absent_ok=False)
        self.wait_health(record, docker, seconds=30)
        if record["state"] == "HEALTHY":
            return record
        if record["state"] != "SEEDED":
            fail("DATABASE_TRANSITION_INVALID", "health acceptance requires seeded state")
        return self.registry.transition(
            database_id, record["lifecycleRevision"], "HEALTHY", desired_state="RUNNING"
        )

    def snapshot(self, database_id: str, snapshot_id: str | None = None) -> dict[str, Any]:
        record, _, _, docker = self.context(database_id)
        if record["state"] not in {"HEALTHY", "REPLACING", "RESTORING"}:
            fail("DATABASE_TRANSITION_INVALID", "snapshot requires a healthy owned database")
        self.assert_projection(record, docker, absent_ok=False)
        self.wait_health(record, docker, seconds=30)
        identity = snapshot_id or str(uuid.uuid4())
        STATE.canonical_uuid(identity, "SNAPSHOT_ID_INVALID")
        root = self.snapshot_root / record["workSessionId"] / record["databaseId"]
        root.mkdir(parents=True, mode=0o700, exist_ok=True)
        target = root / f"{identity}.snapshot"
        if target.exists() or target.is_symlink():
            fail("SNAPSHOT_OWNERSHIP_CONFLICT", "snapshot content identity already exists")
        descriptor, temporary_name = tempfile.mkstemp(prefix=".snapshot.", dir=root)
        os.fchmod(descriptor, 0o600)
        command = (
            ["exec", record["containerIdentity"], "sh", "-ceu",
             'umask 077; credential="$(mktemp)"; trap \'rm -f "$credential"\' EXIT; '
             'printf "*:*:%s:synthetic_user:%s\\n" "$1" "$(cat /run/secrets/database-password)" >"$credential"; '
             'export PGPASSFILE="$credential"; pg_dump -Fc -U synthetic_user "$1"',
             "database-snapshot", record["databaseName"]]
            if record["engine"] == "postgresql"
            else ["exec", record["containerIdentity"], "sh", "-ceu",
                  'umask 077; credential="$(mktemp)"; trap \'rm -f "$credential"\' EXIT; '
                  'printf "[client]\\nuser=synthetic_user\\npassword=%s\\nprotocol=socket\\n" '
                  '"$(cat /run/secrets/database-password)" >"$credential"; '
                  'mariadb-dump --defaults-extra-file="$credential" --single-transaction --skip-comments "$1"',
                  "database-snapshot", record["databaseName"]]
        )
        try:
            with os.fdopen(descriptor, "wb", closefd=True) as output:
                docker.run(command, capture=False, output_file=output)
                output.flush()
                os.fsync(output.fileno())
            temporary = Path(temporary_name)
            if temporary.stat().st_size < 1:
                fail("SNAPSHOT_VERIFY_FAILED", "engine-native snapshot is empty")
            digest = sha256_file(temporary)
            os.replace(temporary, target)
            metadata, _ = self.registry.register_snapshot(
                database_id,
                snapshot_id=identity,
                content_sha256=digest,
                size_bytes=target.stat().st_size,
            )
            return metadata
        finally:
            Path(temporary_name).unlink(missing_ok=True)

    def verify_snapshot(self, database_id: str, snapshot_id: str) -> tuple[dict[str, Any], Path]:
        matches = [
            item for item in self.registry.snapshots(database_id)
            if item["snapshotId"] == snapshot_id
        ]
        if len(matches) != 1:
            fail("SNAPSHOT_NOT_FOUND", "snapshot metadata is absent or ambiguous")
        metadata = matches[0]
        path = self.snapshot_root / metadata["storageIdentity"]
        if (
            not path.is_file()
            or path.is_symlink()
            or path.stat().st_size != metadata["sizeBytes"]
            or sha256_file(path) != metadata["contentSha256"]
        ):
            fail("SNAPSHOT_VERIFY_FAILED", "snapshot content does not match immutable metadata")
        return metadata, path

    def replace(
        self, database_id: str, expected_revision: int, operation_id: str, confirmation: str
    ) -> dict[str, Any]:
        record, _, _, docker = self.context(database_id)
        self.assert_projection(record, docker, absent_ok=False)
        replacing = self.registry.consume_replace(
            database_id, expected_revision, operation_id, confirmation
        )
        pre = self.snapshot(database_id)
        self.verify_snapshot(database_id, pre["snapshotId"])
        self.assert_projection(replacing, docker, absent_ok=False)
        docker.run(["container", "rm", "-f", replacing["containerIdentity"]])
        docker.run(["volume", "rm", replacing["volumeIdentity"]])
        # Recreate from the immutable record; the network must remain exact.
        docker.run(["network", "rm", replacing["networkIdentity"]])
        stopped = self.registry.transition(
            database_id, replacing["lifecycleRevision"], "BLOCKED", desired_state="STOPPED"
        )
        stopped = self.registry.transition(
            database_id, stopped["lifecycleRevision"], "STOPPED", desired_state="STOPPED"
        )
        created = self.create(database_id)
        self.apply_sql(database_id, "migrationPaths")
        self.apply_sql(database_id, "seedPaths")
        healthy = self.health(database_id)
        return {
            "record": healthy,
            "verifiedPreReplacementSnapshotId": pre["snapshotId"],
            "replacementRevision": created["lifecycleRevision"],
        }

    def restore(self, database_id: str, expected_revision: int, snapshot_id: str) -> dict[str, Any]:
        record, _, _, docker = self.context(database_id)
        if record["state"] != "HEALTHY":
            fail("DATABASE_NOT_HEALTHY", "restore requires a healthy exact database")
        self.assert_projection(record, docker, absent_ok=False)
        _, path = self.verify_snapshot(database_id, snapshot_id)
        restoring = self.registry.transition(
            database_id, expected_revision, "RESTORING", desired_state="RUNNING"
        )
        command = (
            ["exec", "-i", restoring["containerIdentity"], "sh", "-ceu",
             'umask 077; credential="$(mktemp)"; trap \'rm -f "$credential"\' EXIT; '
             'printf "*:*:%s:synthetic_user:%s\\n" "$1" "$(cat /run/secrets/database-password)" >"$credential"; '
             'export PGPASSFILE="$credential"; pg_restore --clean --if-exists --single-transaction -U synthetic_user -d "$1"',
             "database-restore", restoring["databaseName"]]
            if restoring["engine"] == "postgresql"
            else ["exec", "-i", restoring["containerIdentity"], "sh", "-ceu",
                  'umask 077; credential="$(mktemp)"; dump="$(mktemp)"; '
                  'trap \'rm -f "$credential" "$dump"\' EXIT; cat >"$dump"; '
                  'printf "[client]\\nuser=root\\npassword=%s\\nprotocol=socket\\n" '
                  '"$(cat /run/secrets/database-password)" >"$credential"; '
                  'staging="${1}_restore_${2}"; backup="${1}_backup_${2}"; '
                  'mariadb --defaults-extra-file="$credential" -e '
                  '"DROP DATABASE IF EXISTS \\`$staging\\`; DROP DATABASE IF EXISTS \\`$backup\\`; '
                  'CREATE DATABASE \\`$staging\\`; CREATE DATABASE \\`$backup\\`;"; '
                  'mariadb --defaults-extra-file="$credential" "$staging" <"$dump"; '
                  'mariadb --defaults-extra-file="$credential" -e '
                  '"RENAME TABLE \\`$1\\`.phase7_items TO \\`$backup\\`.phase7_items, '
                  '\\`$staging\\`.phase7_items TO \\`$1\\`.phase7_items; '
                  'DROP DATABASE \\`$staging\\`; DROP DATABASE \\`$backup\\`;";',
                  "database-restore", restoring["databaseName"],
                  snapshot_id.replace("-", "")[:16]]
        )
        docker.run(command, input_bytes=path.read_bytes())
        self.wait_health(restoring, docker, seconds=30)
        return self.registry.transition(
            database_id, restoring["lifecycleRevision"], "HEALTHY", desired_state="RUNNING"
        )

    def stop(self, database_id: str) -> dict[str, Any]:
        record, _, _, docker = self.context(database_id)
        container = self.assert_resource(
            docker, "container", record["containerIdentity"], record, absent_ok=True
        )
        if container is not None:
            docker.run(["container", "stop", "--time", "20", record["containerIdentity"]])
        if record["state"] == "STOPPED":
            return record
        return self.registry.transition(
            database_id, record["lifecycleRevision"], "STOPPED", desired_state="STOPPED"
        )

    def cleanup(self, database_id: str) -> dict[str, Any]:
        record, _, _, docker = self.context(database_id)
        if record["state"] != "STOPPED" or record["desiredState"] != "STOPPED":
            fail("DATABASE_CLEANUP_DENIED", "cleanup requires persisted stopped ownership")
        for kind, identity, args in (
            ("container", record["containerIdentity"], ["container", "rm", "-f", record["containerIdentity"]]),
            ("network", record["networkIdentity"], ["network", "rm", record["networkIdentity"]]),
            ("volume", record["volumeIdentity"], ["volume", "rm", record["volumeIdentity"]]),
        ):
            resource = self.assert_resource(docker, kind, identity, record, absent_ok=True)
            if resource is not None:
                docker.run(args)
        secret, root = self.secret_paths(record)
        secret.unlink(missing_ok=True)
        try:
            root.rmdir()
        except FileNotFoundError:
            pass
        try:
            root.parent.rmdir()
        except (FileNotFoundError, OSError):
            pass
        return {"databaseId": database_id, "resourcesPresent": False}

    def retain(self, database_id: str) -> dict[str, Any]:
        removed: list[str] = []
        for item in self.registry.retention_candidates(database_id):
            metadata, content = self.verify_snapshot(database_id, item["snapshotId"])
            content.unlink()
            metadata_path = self.registry.snapshots_root / database_id / f"{item['snapshotId']}.json"
            metadata_path.unlink()
            removed.append(metadata["snapshotId"])
        return {"databaseId": database_id, "removedSnapshotIds": removed}

    def status(self, database_id: str) -> dict[str, Any]:
        record, _, _, docker = self.context(database_id)
        resources = {}
        for kind, key in (
            ("container", "containerIdentity"),
            ("network", "networkIdentity"),
            ("volume", "volumeIdentity"),
        ):
            value = docker.inspect(kind, record[key])
            resources[kind] = "absent" if value is None else "present"
            if value is not None:
                self.assert_resource(docker, kind, record[key], record, absent_ok=False)
        return {
            "protocolVersion": PROTOCOL,
            "enabled": self.enabled_marker.is_file(),
            "record": record,
            "resources": resources,
            "hostPortPublished": False,
            "credentialValueExposed": False,
        }


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser(prog="database-lifecycle-worker-v1")
    sub = result.add_subparsers(dest="action", required=True)
    register = sub.add_parser("register")
    register.add_argument("database_id")
    register.add_argument("session_id")
    for action in ("create", "migrate", "seed", "health", "status", "snapshot", "stop", "cleanup", "retain"):
        item = sub.add_parser(action)
        item.add_argument("database_id")
        if action == "snapshot":
            item.add_argument("--snapshot-id")
    prepare = sub.add_parser("prepare-replace")
    prepare.add_argument("database_id")
    prepare.add_argument("expected_revision", type=int)
    replace = sub.add_parser("replace")
    replace.add_argument("database_id")
    replace.add_argument("expected_revision", type=int)
    replace.add_argument("operation_id")
    replace.add_argument("confirmation")
    restore = sub.add_parser("restore")
    restore.add_argument("database_id")
    restore.add_argument("expected_revision", type=int)
    restore.add_argument("snapshot_id")
    sub.add_parser("reconcile")
    sub.add_parser("verify")
    return result


def main() -> int:
    arguments = parser().parse_args()
    lifecycle = Lifecycle()
    if (
        arguments.action in MUTATING_ACTIONS
        and arguments.action not in DISABLED_ROLLBACK_ACTIONS
    ):
        lifecycle.require_enabled()
    if arguments.action == "register":
        output = lifecycle.register(arguments.database_id, arguments.session_id)
    elif arguments.action == "create":
        output = lifecycle.create(arguments.database_id)
    elif arguments.action == "migrate":
        output = lifecycle.apply_sql(arguments.database_id, "migrationPaths")
    elif arguments.action == "seed":
        output = lifecycle.apply_sql(arguments.database_id, "seedPaths")
    elif arguments.action == "health":
        output = lifecycle.health(arguments.database_id)
    elif arguments.action == "status":
        output = lifecycle.status(arguments.database_id)
    elif arguments.action == "snapshot":
        output = lifecycle.snapshot(arguments.database_id, arguments.snapshot_id)
    elif arguments.action == "prepare-replace":
        output = lifecycle.registry.prepare_replace(
            arguments.database_id, arguments.expected_revision
        )
    elif arguments.action == "replace":
        output = lifecycle.replace(
            arguments.database_id,
            arguments.expected_revision,
            arguments.operation_id,
            arguments.confirmation,
        )
    elif arguments.action == "restore":
        output = lifecycle.restore(
            arguments.database_id, arguments.expected_revision, arguments.snapshot_id
        )
    elif arguments.action == "stop":
        output = lifecycle.stop(arguments.database_id)
    elif arguments.action == "cleanup":
        output = lifecycle.cleanup(arguments.database_id)
    elif arguments.action == "retain":
        output = lifecycle.retain(arguments.database_id)
    elif arguments.action == "reconcile":
        output = {
            "protocolVersion": PROTOCOL,
            "enabled": lifecycle.enabled_marker.is_file(),
            "records": lifecycle.registry.reconcile(),
            "implicitCreation": False,
        }
    else:
        output = {
            "protocolVersion": PROTOCOL,
            "enabled": lifecycle.enabled_marker.is_file(),
            "stateRoot": str(lifecycle.state_root),
            "snapshotRoot": str(lifecycle.snapshot_root),
            "hostListenerRequired": False,
            "rootfulDockerAllowed": False,
        }
    print(json.dumps(output, sort_keys=True, separators=(",", ":")))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except LifecycleError as error:
        print(
            json.dumps(
                {"protocolVersion": PROTOCOL, "error": error.code, "message": str(error)},
                sort_keys=True,
                separators=(",", ":"),
            ),
            file=sys.stderr,
        )
        raise SystemExit(65)
