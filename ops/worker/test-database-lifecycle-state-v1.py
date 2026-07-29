#!/usr/bin/env python3

from __future__ import annotations

import copy
import hashlib
import importlib.util
import json
import sys
import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path

try:
    from jsonschema import Draft202012Validator
except ImportError:
    Draft202012Validator = None

sys.dont_write_bytecode = True
SCRIPT_DIR = Path(__file__).resolve().parent
REPO_ROOT = SCRIPT_DIR.parent.parent
MODULE_PATH = SCRIPT_DIR / "database-lifecycle-state-v1.py"
SPEC = importlib.util.spec_from_file_location("database_lifecycle_state_v1", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)

DATABASE_ID = "70000000-0000-4000-8000-000000000001"
SESSION_ID = "70000000-0000-4000-8000-000000000002"
FOREIGN_SESSION_ID = "70000000-0000-4000-8000-000000000003"
ALLOCATION_IDENTITY = "ws-" + SESSION_ID.replace("-", "")
ALLOCATION_FINGERPRINT = "a" * 64
MANIFEST_SHA256 = "b" * 64
BASE_TIME = datetime(2026, 7, 29, 4, 0, tzinfo=timezone.utc)


class DatabaseLifecycleStateTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory(prefix="phase7-database-state-")
        self.root = Path(self.temporary.name)
        self.registry = MODULE.DatabaseRegistry(self.root / "state")

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def create(self, **overrides):
        values = {
            "database_id": DATABASE_ID,
            "work_session_id": SESSION_ID,
            "project_id": "database-postgresql",
            "worker_id": "ax42-01",
            "allocation_identity": ALLOCATION_IDENTITY,
            "allocation_fingerprint": ALLOCATION_FINGERPRINT,
            "slot": "slot3",
            "engine": "postgresql",
            "database_name": "synthetic_phase7_postgresql",
            "manifest_sha256": MANIFEST_SHA256,
            "now": BASE_TIME,
        }
        values.update(overrides)
        return self.registry.create(**values)

    def healthy(self):
        self.create()
        revision = 1
        for state in ("CREATED", "MIGRATED", "SEEDED", "HEALTHY"):
            record = self.registry.transition(
                DATABASE_ID, revision, state, desired_state="RUNNING", now=BASE_TIME
            )
            revision = record["lifecycleRevision"]
        return record

    def test_exact_create_is_idempotent_and_derives_resource_names(self):
        first, created = self.create()
        second, repeated = self.create()

        self.assertTrue(created)
        self.assertFalse(repeated)
        self.assertEqual(first, second)
        self.assertEqual(
            f"ws-{SESSION_ID.replace('-', '')}-db-{DATABASE_ID.replace('-', '')}-data",
            first["volumeIdentity"],
        )

    def test_foreign_create_conflict_preserves_record(self):
        self.create()
        path = self.registry.records_root / f"{DATABASE_ID}.json"
        fingerprint = hashlib.sha256(path.read_bytes()).hexdigest()

        with self.assertRaisesRegex(MODULE.LifecycleError, "ownership"):
            self.create(project_id="foreign-project")

        self.assertEqual(fingerprint, hashlib.sha256(path.read_bytes()).hexdigest())

    def test_production_like_database_identity_is_denied_before_record(self):
        with self.assertRaisesRegex(MODULE.LifecycleError, "not synthetic"):
            self.create(database_name="synthetic_prod_database")

        self.assertEqual([], list(self.registry.records_root.iterdir()))

    def test_transitions_are_monotonic_and_idempotent(self):
        self.create()
        created = self.registry.transition(
            DATABASE_ID, 1, "CREATED", desired_state="RUNNING", now=BASE_TIME
        )
        repeated = self.registry.transition(
            DATABASE_ID, 1, "CREATED", desired_state="RUNNING", now=BASE_TIME
        )

        self.assertEqual(2, created["lifecycleRevision"])
        self.assertEqual(created, repeated)
        with self.assertRaisesRegex(MODULE.LifecycleError, "stale"):
            self.registry.transition(DATABASE_ID, 1, "MIGRATED")
        with self.assertRaisesRegex(MODULE.LifecycleError, "invalid"):
            self.registry.transition(DATABASE_ID, 2, "HEALTHY")
        self.assertEqual(created, self.registry.read(DATABASE_ID))

    def test_partial_record_fails_closed_and_remains_unchanged(self):
        self.create()
        path = self.registry.records_root / f"{DATABASE_ID}.json"
        document = json.loads(path.read_text())
        document.pop("workerId")
        path.write_text(json.dumps(document, sort_keys=True))
        fingerprint = hashlib.sha256(path.read_bytes()).hexdigest()

        with self.assertRaisesRegex(MODULE.LifecycleError, "schema"):
            self.registry.read(DATABASE_ID)

        self.assertEqual(fingerprint, hashlib.sha256(path.read_bytes()).hexdigest())

    def test_confirmation_is_revision_bound_one_use_and_monotonic(self):
        healthy = self.healthy()
        prepared = self.registry.prepare_replace(
            DATABASE_ID,
            healthy["lifecycleRevision"],
            now=BASE_TIME,
            confirmation="phase7-explicit-confirmation-value",
            operation_id="70000000-0000-4000-8000-000000000004",
        )
        consumed = self.registry.consume_replace(
            DATABASE_ID,
            prepared["expectedRevision"],
            prepared["operationId"],
            prepared["confirmation"],
            now=BASE_TIME + timedelta(seconds=1),
        )

        self.assertEqual("REPLACING", consumed["state"])
        self.assertEqual(7, consumed["lifecycleRevision"])
        with self.assertRaises(MODULE.LifecycleError):
            self.registry.consume_replace(
                DATABASE_ID,
                prepared["expectedRevision"],
                prepared["operationId"],
                prepared["confirmation"],
                now=BASE_TIME + timedelta(seconds=2),
            )
        self.assertEqual(consumed, self.registry.read(DATABASE_ID))

    def test_wrong_or_expired_confirmation_mutates_nothing(self):
        healthy = self.healthy()
        prepared = self.registry.prepare_replace(
            DATABASE_ID,
            healthy["lifecycleRevision"],
            now=BASE_TIME,
            confirmation="phase7-explicit-confirmation-value",
            operation_id="70000000-0000-4000-8000-000000000004",
        )
        path = self.registry.records_root / f"{DATABASE_ID}.json"
        fingerprint = hashlib.sha256(path.read_bytes()).hexdigest()

        with self.assertRaisesRegex(MODULE.LifecycleError, "does not match"):
            self.registry.consume_replace(
                DATABASE_ID,
                prepared["expectedRevision"],
                prepared["operationId"],
                "wrong-confirmation-value-that-is-long",
                now=BASE_TIME + timedelta(seconds=1),
            )
        self.assertEqual(fingerprint, hashlib.sha256(path.read_bytes()).hexdigest())
        with self.assertRaisesRegex(MODULE.LifecycleError, "expired"):
            self.registry.consume_replace(
                DATABASE_ID,
                prepared["expectedRevision"],
                prepared["operationId"],
                prepared["confirmation"],
                now=BASE_TIME + timedelta(seconds=301),
            )
        self.assertEqual(fingerprint, hashlib.sha256(path.read_bytes()).hexdigest())

    def test_snapshot_metadata_is_exact_and_idempotent(self):
        healthy = self.healthy()
        snapshot_id = "70000000-0000-4000-8000-000000000005"
        first, created = self.registry.register_snapshot(
            DATABASE_ID,
            snapshot_id=snapshot_id,
            content_sha256="c" * 64,
            size_bytes=4096,
            now=BASE_TIME,
        )
        second, repeated = self.registry.register_snapshot(
            DATABASE_ID,
            snapshot_id=snapshot_id,
            content_sha256="c" * 64,
            size_bytes=4096,
            now=BASE_TIME,
        )

        self.assertTrue(created)
        self.assertFalse(repeated)
        self.assertEqual(first, second)
        self.assertEqual(healthy["lifecycleRevision"], first["lifecycleRevision"])

    def test_snapshot_conflict_and_foreign_metadata_remain_unchanged(self):
        self.healthy()
        snapshot_id = "70000000-0000-4000-8000-000000000005"
        self.registry.register_snapshot(
            DATABASE_ID,
            snapshot_id=snapshot_id,
            content_sha256="c" * 64,
            size_bytes=4096,
            now=BASE_TIME,
        )
        path = self.registry.snapshots_root / DATABASE_ID / f"{snapshot_id}.json"
        with self.assertRaisesRegex(MODULE.LifecycleError, "different metadata"):
            self.registry.register_snapshot(
                DATABASE_ID,
                snapshot_id=snapshot_id,
                content_sha256="c" * 64,
                size_bytes=8192,
                now=BASE_TIME,
            )
        document = json.loads(path.read_text())
        document["workSessionId"] = FOREIGN_SESSION_ID
        document["storageIdentity"] = (
            f"{FOREIGN_SESSION_ID}/{DATABASE_ID}/{snapshot_id}.snapshot"
        )
        path.write_text(json.dumps(document, sort_keys=True))
        fingerprint = hashlib.sha256(path.read_bytes()).hexdigest()

        with self.assertRaisesRegex(MODULE.LifecycleError, "ownership"):
            self.registry.snapshots(DATABASE_ID)
        self.assertEqual(fingerprint, hashlib.sha256(path.read_bytes()).hexdigest())

    def test_retention_selects_only_expired_or_excess_exact_snapshots(self):
        self.healthy()
        snapshots = []
        for index, age_days in enumerate((8, 6, 3, 2, 1), start=10):
            snapshot_id = f"70000000-0000-4000-8000-{index:012d}"
            self.registry.register_snapshot(
                DATABASE_ID,
                snapshot_id=snapshot_id,
                content_sha256=f"{index:064x}",
                size_bytes=index,
                now=BASE_TIME - timedelta(days=age_days),
            )
            snapshots.append(snapshot_id)

        candidates = self.registry.retention_candidates(
            DATABASE_ID, now=BASE_TIME
        )

        self.assertEqual(snapshots[:2], [item["snapshotId"] for item in candidates])
        self.assertEqual(5, len(self.registry.snapshots(DATABASE_ID)))

    def test_reconcile_reports_persisted_intent_without_creation(self):
        self.create()
        stopped = self.registry.transition(
            DATABASE_ID, 1, "STOPPED", desired_state="STOPPED", now=BASE_TIME
        )

        reconciled = MODULE.DatabaseRegistry(self.root / "state").reconcile()

        self.assertEqual(
            [
                {
                    "databaseId": DATABASE_ID,
                    "workSessionId": SESSION_ID,
                    "state": "STOPPED",
                    "desiredState": "STOPPED",
                    "lifecycleRevision": stopped["lifecycleRevision"],
                    "implicitCreation": False,
                }
            ],
            reconciled,
        )


class DatabaseManifestTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.schema = json.loads(
            (REPO_ROOT / "runtime-contract/project-runtime-v1.schema.json").read_text()
        )
        if Draft202012Validator is not None:
            Draft202012Validator.check_schema(cls.schema)
            cls.validator = Draft202012Validator(cls.schema)
        else:
            cls.validator = None
        cls.paths = [
            REPO_ROOT
            / "runtime-contract/fixtures/valid/database-postgresql/runtime.json",
            REPO_ROOT / "runtime-contract/fixtures/valid/database-mariadb/runtime.json",
        ]

    def test_postgresql_and_mariadb_manifests_are_valid(self):
        for path in self.paths:
            document = json.loads(path.read_text())
            if self.validator is not None:
                self.validator.validate(document)
            normalized = MODULE.validate_database_manifest(document)
            self.assertTrue(normalized["syntheticDevelopmentFixture"])

    def test_unsafe_database_manifest_variants_are_rejected(self):
        base = json.loads(self.paths[0].read_text())
        variants = []
        production = copy.deepcopy(base)
        production["database"]["classification"] = "production"
        variants.append((production, True))
        literal = copy.deepcopy(base)
        literal["database"]["password"] = "forbidden-literal"
        variants.append((literal, True))
        unpinned = copy.deepcopy(base)
        unpinned["database"]["image"] = "postgres:16"
        variants.append((unpinned, True))
        arbitrary_host = copy.deepcopy(base)
        arbitrary_host["database"]["host"] = "prod.example.invalid"
        variants.append((arbitrary_host, True))
        mismatched_format = copy.deepcopy(base)
        mismatched_format["database"]["snapshotFormat"] = "mariadb-sql-v1"
        variants.append((mismatched_format, True))
        wrong_secret = copy.deepcopy(base)
        wrong_secret["database"]["secretRef"] = "UNDECLARED_DATABASE_PASSWORD"
        variants.append((wrong_secret, False))

        for document, schema_must_reject in variants:
            if schema_must_reject:
                if self.validator is not None:
                    self.assertTrue(list(self.validator.iter_errors(document)))
            with self.assertRaises(MODULE.LifecycleError):
                MODULE.validate_database_manifest(document)


if __name__ == "__main__":
    unittest.main()
