#!/usr/bin/env python3

from __future__ import annotations

import hashlib
import json
import os
import re
import secrets
import tempfile
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any

PROTOCOL = "database-lifecycle/v1"
RECORD_SCHEMA = 1
SNAPSHOT_SCHEMA = 1
CHALLENGE_SECONDS = 300
SYNTHETIC_MAX_COPIES = 3
SYNTHETIC_MAX_AGE_DAYS = 7
SUPPORTED_ENGINES = {"postgresql", "mariadb"}
STATES = {
    "ALLOCATED",
    "CREATED",
    "MIGRATED",
    "SEEDED",
    "HEALTHY",
    "REPLACING",
    "RESTORING",
    "STOPPED",
    "BLOCKED",
}
TRANSITIONS = {
    "ALLOCATED": {"CREATED", "STOPPED", "BLOCKED"},
    "CREATED": {"MIGRATED", "STOPPED", "BLOCKED"},
    "MIGRATED": {"SEEDED", "STOPPED", "BLOCKED"},
    "SEEDED": {"HEALTHY", "STOPPED", "BLOCKED"},
    "HEALTHY": {"REPLACING", "RESTORING", "STOPPED", "BLOCKED"},
    "REPLACING": {"MIGRATED", "BLOCKED"},
    "RESTORING": {"HEALTHY", "BLOCKED"},
    "STOPPED": {"CREATED", "BLOCKED"},
    "BLOCKED": {"STOPPED"},
}
UUID_PATTERN = re.compile(
    r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$"
)
PROJECT_PATTERN = re.compile(r"^[a-z][a-z0-9-]{1,62}$")
WORKER_PATTERN = re.compile(r"^[A-Za-z0-9._-]{1,80}$")
ALLOCATION_PATTERN = re.compile(r"^ws-[0-9a-f]{32}$")
SHA256_PATTERN = re.compile(r"^[a-f0-9]{64}$")
SLOT_PATTERN = re.compile(r"^slot[1-4]$")
DATABASE_NAME_PATTERN = re.compile(r"^synthetic_[a-z][a-z0-9_]{2,47}$")
IMAGE_PATTERN = re.compile(
    r"^(postgres|mariadb):[0-9]+(?:\.[0-9]+){0,2}@sha256:[a-f0-9]{64}$"
)
SECRET_PATTERN = re.compile(r"^[A-Z][A-Z0-9_]{2,63}$")
RELATIVE_PATH_PATTERN = re.compile(
    r"^(?!/)(?!~)(?!.*(?:^|/)\.\.(?:/|$))(?!.*//)[A-Za-z0-9._/-]+$"
)
PRODUCTION_MARKERS = re.compile(r"(^|[_-])(prod|production|live)([_-]|$)", re.I)
IMMUTABLE_FIELDS = (
    "databaseId",
    "workSessionId",
    "projectId",
    "workerId",
    "allocationIdentity",
    "allocationFingerprint",
    "slot",
    "engine",
    "databaseName",
    "manifestSha256",
    "containerIdentity",
    "networkIdentity",
    "volumeIdentity",
    "syntheticDevelopmentFixture",
)


class LifecycleError(RuntimeError):
    def __init__(self, code: str, message: str):
        super().__init__(message)
        self.code = code


def utc_now() -> datetime:
    return datetime.now(timezone.utc)


def timestamp(value: datetime) -> str:
    return value.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")


def parse_timestamp(value: Any, field: str) -> datetime:
    if not isinstance(value, str):
        raise LifecycleError("PERSISTED_STATE_INVALID", f"{field} is invalid")
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as error:
        raise LifecycleError("PERSISTED_STATE_INVALID", f"{field} is invalid") from error
    if parsed.tzinfo is None:
        raise LifecycleError("PERSISTED_STATE_INVALID", f"{field} is invalid")
    return parsed.astimezone(timezone.utc)


def canonical_uuid(value: Any, code: str) -> str:
    text = str(value)
    if not UUID_PATTERN.fullmatch(text):
        raise LifecycleError(code, "identity must be a canonical lowercase UUID")
    return text


def validate_database_manifest(document: dict[str, Any]) -> dict[str, Any]:
    database = document.get("database")
    if not isinstance(database, dict):
        raise LifecycleError("DATABASE_MANIFEST_REQUIRED", "database manifest is required")
    expected = {
        "schemaVersion",
        "classification",
        "syntheticDevelopmentFixture",
        "engine",
        "image",
        "databaseName",
        "portName",
        "migrationPaths",
        "seedPaths",
        "secretRef",
        "healthCheck",
        "snapshotFormat",
        "retention",
        "replacementMode",
    }
    if set(database) != expected:
        raise LifecycleError(
            "DATABASE_MANIFEST_INVALID",
            "database manifest fields are missing or unsupported",
        )
    if (
        database["schemaVersion"] != 1
        or database["classification"] != "synthetic-development"
        or database["syntheticDevelopmentFixture"] is not True
    ):
        raise LifecycleError(
            "PRODUCTION_TARGET_DENIED",
            "only an exact synthetic development fixture is accepted",
        )
    engine = database["engine"]
    image = database["image"]
    if engine not in SUPPORTED_ENGINES or not isinstance(image, str):
        raise LifecycleError("DATABASE_MANIFEST_INVALID", "database engine is invalid")
    image_match = IMAGE_PATTERN.fullmatch(image)
    if image_match is None or (
        engine == "postgresql" and image_match.group(1) != "postgres"
    ) or (engine == "mariadb" and image_match.group(1) != "mariadb"):
        raise LifecycleError(
            "DATABASE_MANIFEST_INVALID", "database image must be engine-pinned"
        )
    database_name = database["databaseName"]
    if (
        not isinstance(database_name, str)
        or not DATABASE_NAME_PATTERN.fullmatch(database_name)
        or PRODUCTION_MARKERS.search(database_name)
    ):
        raise LifecycleError(
            "PRODUCTION_TARGET_DENIED", "database identity is not synthetic"
        )
    project = document.get("project")
    if (
        not isinstance(project, dict)
        or not PROJECT_PATTERN.fullmatch(str(project.get("id", "")))
    ):
        raise LifecycleError("DATABASE_MANIFEST_INVALID", "project identity is invalid")
    secret_ref = database["secretRef"]
    if not isinstance(secret_ref, str) or not SECRET_PATTERN.fullmatch(secret_ref):
        raise LifecycleError("DATABASE_MANIFEST_INVALID", "secret reference is invalid")
    matching_secrets = [
        secret
        for secret in document.get("secrets", [])
        if isinstance(secret, dict)
        and secret.get("name") == secret_ref
        and secret.get("exposure") == "database"
        and secret.get("required") is True
        and "value" not in secret
    ]
    if len(matching_secrets) != 1:
        raise LifecycleError(
            "DATABASE_MANIFEST_INVALID",
            "one required named database secret is required",
        )
    port_names = {
        port.get("name")
        for port in document.get("runtime", {}).get("internalPorts", [])
        if isinstance(port, dict)
    }
    if database["portName"] not in port_names:
        raise LifecycleError(
            "DATABASE_MANIFEST_INVALID",
            "database port must reference one manifest internal port",
        )
    for field in ("migrationPaths", "seedPaths"):
        paths = database[field]
        if (
            not isinstance(paths, list)
            or not 1 <= len(paths) <= 32
            or len(paths) != len(set(paths))
            or any(
                not isinstance(path, str) or not RELATIVE_PATH_PATTERN.fullmatch(path)
                for path in paths
            )
        ):
            raise LifecycleError(
                "DATABASE_MANIFEST_INVALID", f"{field} must contain safe paths"
            )
    expected_format = (
        "postgres-custom-v1" if engine == "postgresql" else "mariadb-sql-v1"
    )
    if (
        database["healthCheck"] != "select-one"
        or database["snapshotFormat"] != expected_format
        or database["replacementMode"] != "explicit-confirmed"
        or database["retention"] != {
            "maxCopies": SYNTHETIC_MAX_COPIES,
            "maxAgeDays": SYNTHETIC_MAX_AGE_DAYS,
        }
    ):
        raise LifecycleError(
            "DATABASE_MANIFEST_INVALID", "database lifecycle policy is invalid"
        )
    return json.loads(json.dumps(database, sort_keys=True))


class DatabaseRegistry:
    def __init__(self, root: Path):
        self.root = root.resolve()
        self.records_root = self.root / "records"
        self.snapshots_root = self.root / "snapshots"
        self._directory(self.root)
        self._directory(self.records_root)
        self._directory(self.snapshots_root)

    def create(
        self,
        *,
        database_id: str,
        work_session_id: str,
        project_id: str,
        worker_id: str,
        allocation_identity: str,
        allocation_fingerprint: str,
        slot: str,
        engine: str,
        database_name: str,
        manifest_sha256: str,
        now: datetime | None = None,
    ) -> tuple[dict[str, Any], bool]:
        identity = canonical_uuid(database_id, "DATABASE_ID_INVALID")
        session = canonical_uuid(work_session_id, "WORKSESSION_ID_INVALID")
        if not PROJECT_PATTERN.fullmatch(project_id):
            raise LifecycleError("PROJECT_ID_INVALID", "project identity is invalid")
        if not WORKER_PATTERN.fullmatch(worker_id):
            raise LifecycleError("WORKER_ID_INVALID", "worker identity is invalid")
        if not ALLOCATION_PATTERN.fullmatch(allocation_identity):
            raise LifecycleError(
                "ALLOCATION_IDENTITY_INVALID", "allocation identity is invalid"
            )
        if not SHA256_PATTERN.fullmatch(allocation_fingerprint):
            raise LifecycleError(
                "ALLOCATION_FINGERPRINT_INVALID", "allocation fingerprint is invalid"
            )
        if not SLOT_PATTERN.fullmatch(slot):
            raise LifecycleError("SLOT_INVALID", "rootless slot is invalid")
        if engine not in SUPPORTED_ENGINES:
            raise LifecycleError("ENGINE_INVALID", "database engine is unsupported")
        if (
            not DATABASE_NAME_PATTERN.fullmatch(database_name)
            or PRODUCTION_MARKERS.search(database_name)
        ):
            raise LifecycleError(
                "PRODUCTION_TARGET_DENIED", "database identity is not synthetic"
            )
        if not SHA256_PATTERN.fullmatch(manifest_sha256):
            raise LifecycleError("MANIFEST_HASH_INVALID", "manifest hash is invalid")
        short = identity.replace("-", "")
        runtime = f"ws-{session.replace('-', '')}"
        immutable = {
            "databaseId": identity,
            "workSessionId": session,
            "projectId": project_id,
            "workerId": worker_id,
            "allocationIdentity": allocation_identity,
            "allocationFingerprint": allocation_fingerprint,
            "slot": slot,
            "engine": engine,
            "databaseName": database_name,
            "manifestSha256": manifest_sha256,
            "containerIdentity": f"{runtime}-db-{short}",
            "networkIdentity": f"{runtime}-db-{short}-network",
            "volumeIdentity": f"{runtime}-db-{short}-data",
            "syntheticDevelopmentFixture": True,
        }
        path = self._record_path(identity)
        if path.exists():
            current = self.read(identity)
            if any(current.get(field) != immutable[field] for field in IMMUTABLE_FIELDS):
                raise LifecycleError(
                    "DATABASE_OWNERSHIP_CONFLICT",
                    "persisted database ownership differs",
                )
            return current, False
        current_time = now or utc_now()
        record = {
            "schemaVersion": RECORD_SCHEMA,
            "protocolVersion": PROTOCOL,
            **immutable,
            "state": "ALLOCATED",
            "desiredState": "STOPPED",
            "lifecycleRevision": 1,
            "pendingReplacement": None,
            "createdAt": timestamp(current_time),
            "updatedAt": timestamp(current_time),
        }
        self._validate_record(record)
        self._write(path, record)
        return record, True

    def read(self, database_id: str) -> dict[str, Any]:
        identity = canonical_uuid(database_id, "DATABASE_ID_INVALID")
        path = self._record_path(identity)
        if not path.is_file() or path.is_symlink():
            raise LifecycleError("DATABASE_NOT_FOUND", "database record does not exist")
        document = self._read_json(path)
        self._validate_record(document)
        return document

    def transition(
        self,
        database_id: str,
        expected_revision: int,
        target_state: str,
        *,
        desired_state: str | None = None,
        now: datetime | None = None,
    ) -> dict[str, Any]:
        record = self.read(database_id)
        if (
            record["state"] == target_state
            and record["lifecycleRevision"] == expected_revision + 1
        ):
            return record
        self._require_revision(record, expected_revision)
        if target_state not in STATES or target_state not in TRANSITIONS[record["state"]]:
            raise LifecycleError(
                "DATABASE_TRANSITION_INVALID",
                "database lifecycle transition is invalid",
            )
        if desired_state is not None and desired_state not in {"RUNNING", "STOPPED"}:
            raise LifecycleError("DESIRED_STATE_INVALID", "desired state is invalid")
        record["state"] = target_state
        if desired_state is not None:
            record["desiredState"] = desired_state
        record["lifecycleRevision"] += 1
        record["updatedAt"] = timestamp(now or utc_now())
        self._write(self._record_path(record["databaseId"]), record)
        return record

    def prepare_replace(
        self,
        database_id: str,
        expected_revision: int,
        *,
        now: datetime | None = None,
        confirmation: str | None = None,
        operation_id: str | None = None,
    ) -> dict[str, Any]:
        record = self.read(database_id)
        self._require_revision(record, expected_revision)
        if record["state"] != "HEALTHY":
            raise LifecycleError(
                "DATABASE_NOT_HEALTHY",
                "replacement requires an exact healthy database",
            )
        current_time = now or utc_now()
        value = confirmation or secrets.token_urlsafe(24)
        if len(value) < 20:
            raise LifecycleError(
                "CONFIRMATION_INVALID", "replacement confirmation is too short"
            )
        operation = canonical_uuid(
            operation_id or str(uuid.uuid4()), "OPERATION_ID_INVALID"
        )
        record["lifecycleRevision"] += 1
        record["pendingReplacement"] = {
            "operationId": operation,
            "confirmationSha256": hashlib.sha256(value.encode()).hexdigest(),
            "boundRevision": record["lifecycleRevision"],
            "expiresAt": timestamp(current_time + timedelta(seconds=CHALLENGE_SECONDS)),
            "consumed": False,
        }
        record["updatedAt"] = timestamp(current_time)
        self._write(self._record_path(record["databaseId"]), record)
        return {
            "protocolVersion": PROTOCOL,
            "databaseId": record["databaseId"],
            "operationId": operation,
            "confirmation": value,
            "expectedRevision": record["lifecycleRevision"],
            "expiresAt": record["pendingReplacement"]["expiresAt"],
        }

    def consume_replace(
        self,
        database_id: str,
        expected_revision: int,
        operation_id: str,
        confirmation: str,
        *,
        now: datetime | None = None,
    ) -> dict[str, Any]:
        record = self.read(database_id)
        self._require_revision(record, expected_revision)
        pending = record.get("pendingReplacement")
        if (
            not isinstance(pending, dict)
            or pending.get("operationId") != operation_id
            or pending.get("boundRevision") != expected_revision
            or pending.get("consumed") is not False
        ):
            raise LifecycleError(
                "REPLACEMENT_CONFIRMATION_CONFLICT",
                "replacement confirmation does not match pending ownership",
            )
        current_time = now or utc_now()
        if current_time >= parse_timestamp(pending.get("expiresAt"), "expiresAt"):
            raise LifecycleError(
                "REPLACEMENT_CONFIRMATION_EXPIRED",
                "replacement confirmation has expired",
            )
        observed = hashlib.sha256(confirmation.encode()).hexdigest()
        if not secrets.compare_digest(observed, str(pending["confirmationSha256"])):
            raise LifecycleError(
                "REPLACEMENT_CONFIRMATION_CONFLICT",
                "replacement confirmation does not match",
            )
        pending["consumed"] = True
        record["state"] = "REPLACING"
        record["lifecycleRevision"] += 1
        record["updatedAt"] = timestamp(current_time)
        self._write(self._record_path(record["databaseId"]), record)
        return record

    def register_snapshot(
        self,
        database_id: str,
        *,
        snapshot_id: str,
        content_sha256: str,
        size_bytes: int,
        now: datetime | None = None,
    ) -> tuple[dict[str, Any], bool]:
        record = self.read(database_id)
        identity = canonical_uuid(snapshot_id, "SNAPSHOT_ID_INVALID")
        if not SHA256_PATTERN.fullmatch(content_sha256):
            raise LifecycleError("SNAPSHOT_HASH_INVALID", "snapshot hash is invalid")
        if not isinstance(size_bytes, int) or isinstance(size_bytes, bool) or size_bytes < 1:
            raise LifecycleError("SNAPSHOT_SIZE_INVALID", "snapshot size is invalid")
        root = self.snapshots_root / record["databaseId"]
        self._directory(root)
        path = root / f"{identity}.json"
        document = {
            "schemaVersion": SNAPSHOT_SCHEMA,
            "protocolVersion": PROTOCOL,
            **{field: record[field] for field in IMMUTABLE_FIELDS},
            "snapshotId": identity,
            "storageIdentity": (
                f"{record['workSessionId']}/{record['databaseId']}/{identity}.snapshot"
            ),
            "contentSha256": content_sha256,
            "sizeBytes": size_bytes,
            "lifecycleRevision": record["lifecycleRevision"],
            "createdAt": timestamp(now or utc_now()),
            "syntheticSnapshot": True,
        }
        if path.exists():
            current = self._read_json(path)
            self._validate_snapshot(current)
            if current != document:
                raise LifecycleError(
                    "SNAPSHOT_OWNERSHIP_CONFLICT",
                    "snapshot identity already has different metadata",
                )
            return current, False
        self._validate_snapshot(document)
        self._write(path, document)
        return document, True

    def snapshots(self, database_id: str) -> list[dict[str, Any]]:
        record = self.read(database_id)
        root = self.snapshots_root / record["databaseId"]
        if not root.exists():
            return []
        if not root.is_dir() or root.is_symlink():
            raise LifecycleError(
                "PERSISTED_STATE_INVALID", "snapshot metadata root is unsafe"
            )
        snapshots: list[dict[str, Any]] = []
        for path in sorted(root.glob("*.json")):
            document = self._read_json(path)
            self._validate_snapshot(document)
            if any(document.get(field) != record[field] for field in IMMUTABLE_FIELDS):
                raise LifecycleError(
                    "SNAPSHOT_OWNERSHIP_CONFLICT",
                    "snapshot ownership differs from database",
                )
            snapshots.append(document)
        return sorted(snapshots, key=lambda item: (item["createdAt"], item["snapshotId"]))

    def retention_candidates(
        self, database_id: str, *, now: datetime | None = None
    ) -> list[dict[str, Any]]:
        snapshots = self.snapshots(database_id)
        current_time = now or utc_now()
        expired = {
            item["snapshotId"]
            for item in snapshots
            if current_time - parse_timestamp(item["createdAt"], "createdAt")
            > timedelta(days=SYNTHETIC_MAX_AGE_DAYS)
        }
        excess_count = max(0, len(snapshots) - SYNTHETIC_MAX_COPIES)
        excess = {item["snapshotId"] for item in snapshots[:excess_count]}
        candidates = [
            item
            for item in snapshots
            if item["snapshotId"] in expired or item["snapshotId"] in excess
        ]
        return candidates

    def reconcile(self) -> list[dict[str, Any]]:
        records = []
        for path in sorted(self.records_root.glob("*.json")):
            document = self._read_json(path)
            self._validate_record(document)
            records.append(
                {
                    "databaseId": document["databaseId"],
                    "workSessionId": document["workSessionId"],
                    "state": document["state"],
                    "desiredState": document["desiredState"],
                    "lifecycleRevision": document["lifecycleRevision"],
                    "implicitCreation": False,
                }
            )
        return records

    def _record_path(self, identity: str) -> Path:
        return self.records_root / f"{identity}.json"

    def _validate_record(self, record: dict[str, Any]) -> None:
        expected = {
            "schemaVersion",
            "protocolVersion",
            *IMMUTABLE_FIELDS,
            "state",
            "desiredState",
            "lifecycleRevision",
            "pendingReplacement",
            "createdAt",
            "updatedAt",
        }
        if set(record) != expected:
            raise LifecycleError(
                "PERSISTED_STATE_INVALID", "database record schema is invalid"
            )
        canonical_uuid(record["databaseId"], "PERSISTED_STATE_INVALID")
        canonical_uuid(record["workSessionId"], "PERSISTED_STATE_INVALID")
        if (
            record["schemaVersion"] != RECORD_SCHEMA
            or record["protocolVersion"] != PROTOCOL
            or record["syntheticDevelopmentFixture"] is not True
            or record["engine"] not in SUPPORTED_ENGINES
            or record["state"] not in STATES
            or record["desiredState"] not in {"RUNNING", "STOPPED"}
            or not isinstance(record["lifecycleRevision"], int)
            or isinstance(record["lifecycleRevision"], bool)
            or record["lifecycleRevision"] < 1
            or not PROJECT_PATTERN.fullmatch(record["projectId"])
            or not WORKER_PATTERN.fullmatch(record["workerId"])
            or not ALLOCATION_PATTERN.fullmatch(record["allocationIdentity"])
            or not SHA256_PATTERN.fullmatch(record["allocationFingerprint"])
            or not SLOT_PATTERN.fullmatch(record["slot"])
            or not DATABASE_NAME_PATTERN.fullmatch(record["databaseName"])
            or PRODUCTION_MARKERS.search(record["databaseName"])
            or not SHA256_PATTERN.fullmatch(record["manifestSha256"])
        ):
            raise LifecycleError(
                "PERSISTED_STATE_INVALID", "database record values are invalid"
            )
        parse_timestamp(record["createdAt"], "createdAt")
        parse_timestamp(record["updatedAt"], "updatedAt")
        short = record["databaseId"].replace("-", "")
        runtime = f"ws-{record['workSessionId'].replace('-', '')}"
        if (
            record["containerIdentity"] != f"{runtime}-db-{short}"
            or record["networkIdentity"] != f"{runtime}-db-{short}-network"
            or record["volumeIdentity"] != f"{runtime}-db-{short}-data"
        ):
            raise LifecycleError(
                "PERSISTED_STATE_INVALID", "database resource identities are invalid"
            )
        pending = record["pendingReplacement"]
        if pending is not None:
            if set(pending) != {
                "operationId",
                "confirmationSha256",
                "boundRevision",
                "expiresAt",
                "consumed",
            }:
                raise LifecycleError(
                    "PERSISTED_STATE_INVALID", "replacement challenge is invalid"
                )
            canonical_uuid(pending["operationId"], "PERSISTED_STATE_INVALID")
            if (
                not SHA256_PATTERN.fullmatch(pending["confirmationSha256"])
                or pending["boundRevision"] > record["lifecycleRevision"]
                or not isinstance(pending["consumed"], bool)
            ):
                raise LifecycleError(
                    "PERSISTED_STATE_INVALID", "replacement challenge is invalid"
                )
            parse_timestamp(pending["expiresAt"], "expiresAt")

    def _validate_snapshot(self, document: dict[str, Any]) -> None:
        expected = {
            "schemaVersion",
            "protocolVersion",
            *IMMUTABLE_FIELDS,
            "snapshotId",
            "storageIdentity",
            "contentSha256",
            "sizeBytes",
            "lifecycleRevision",
            "createdAt",
            "syntheticSnapshot",
        }
        if set(document) != expected:
            raise LifecycleError(
                "PERSISTED_STATE_INVALID", "snapshot metadata schema is invalid"
            )
        canonical_uuid(document["databaseId"], "PERSISTED_STATE_INVALID")
        canonical_uuid(document["workSessionId"], "PERSISTED_STATE_INVALID")
        canonical_uuid(document["snapshotId"], "PERSISTED_STATE_INVALID")
        if (
            document["schemaVersion"] != SNAPSHOT_SCHEMA
            or document["protocolVersion"] != PROTOCOL
            or document["syntheticDevelopmentFixture"] is not True
            or document["syntheticSnapshot"] is not True
            or not SHA256_PATTERN.fullmatch(document["contentSha256"])
            or not isinstance(document["sizeBytes"], int)
            or isinstance(document["sizeBytes"], bool)
            or document["sizeBytes"] < 1
            or not isinstance(document["lifecycleRevision"], int)
            or document["lifecycleRevision"] < 1
            or document["storageIdentity"]
            != (
                f"{document['workSessionId']}/{document['databaseId']}/"
                f"{document['snapshotId']}.snapshot"
            )
        ):
            raise LifecycleError(
                "PERSISTED_STATE_INVALID", "snapshot metadata values are invalid"
            )
        parse_timestamp(document["createdAt"], "createdAt")

    def _require_revision(self, record: dict[str, Any], expected: int) -> None:
        if (
            not isinstance(expected, int)
            or isinstance(expected, bool)
            or record["lifecycleRevision"] != expected
        ):
            raise LifecycleError(
                "STALE_REVISION", "database lifecycle revision is stale"
            )

    @staticmethod
    def _directory(path: Path) -> None:
        path.mkdir(mode=0o700, parents=True, exist_ok=True)
        if not path.is_dir() or path.is_symlink():
            raise LifecycleError("PERSISTED_STATE_INVALID", "state root is unsafe")
        path.chmod(0o700)

    @staticmethod
    def _read_json(path: Path) -> dict[str, Any]:
        if not path.is_file() or path.is_symlink():
            raise LifecycleError("PERSISTED_STATE_INVALID", "state file is unsafe")
        try:
            parsed = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as error:
            raise LifecycleError(
                "PERSISTED_STATE_INVALID", "state file is invalid"
            ) from error
        if not isinstance(parsed, dict):
            raise LifecycleError("PERSISTED_STATE_INVALID", "state file is invalid")
        return parsed

    @staticmethod
    def _write(path: Path, document: dict[str, Any]) -> None:
        path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
        encoded = (json.dumps(document, sort_keys=True, separators=(",", ":")) + "\n").encode()
        descriptor, temporary_name = tempfile.mkstemp(
            prefix=f".{path.name}.", dir=path.parent
        )
        temporary = Path(temporary_name)
        try:
            os.fchmod(descriptor, 0o600)
            with os.fdopen(descriptor, "wb", closefd=True) as handle:
                handle.write(encoded)
                handle.flush()
                os.fsync(handle.fileno())
            os.replace(temporary, path)
            path.chmod(0o600)
        finally:
            if temporary.exists():
                temporary.unlink()
