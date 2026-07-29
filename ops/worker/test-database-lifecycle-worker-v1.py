#!/usr/bin/env python3

from __future__ import annotations

import hashlib
import importlib.util
import json
import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
REPO_ROOT = SCRIPT_DIR.parent.parent
MODULE_PATH = SCRIPT_DIR / "database-lifecycle-worker-v1.py"
SPEC = importlib.util.spec_from_file_location("database_lifecycle_worker_v1", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)

DATABASE_ID = "71000000-0000-4000-8000-000000000001"
SESSION_ID = "71000000-0000-4000-8000-000000000002"


class FakeDocker:
    resources: dict[str, dict[str, dict]] = {
        "container": {},
        "network": {},
        "volume": {},
    }

    def __init__(self, slot: str, timeout_seconds: int = 120):
        self.slot = slot

    def inspect(self, kind: str, identity: str):
        return self.resources[kind].get(identity)

    def run(self, argv, *, input_bytes=None, capture=True, output_file=None):
        kind = argv[0]
        if kind in {"network", "volume"} and argv[1] == "create":
            labels = labels_from(argv)
            identity = argv[-1]
            self.resources[kind][identity] = {"Name": identity, "Labels": labels}
        elif kind == "run":
            labels = labels_from(argv)
            identity = argv[argv.index("--name") + 1]
            self.resources["container"][identity] = {
                "Name": identity,
                "Config": {"Labels": labels},
                "State": {"Running": True},
            }
        elif kind == "container" and argv[1] in {"rm", "stop"}:
            identity = argv[-1]
            if argv[1] == "rm":
                self.resources["container"].pop(identity, None)
            elif identity in self.resources["container"]:
                self.resources["container"][identity]["State"]["Running"] = False
        elif kind == "container" and argv[1] == "start":
            self.resources["container"][argv[-1]]["State"]["Running"] = True
        elif kind in {"network", "volume"} and argv[1] == "rm":
            self.resources[kind].pop(argv[-1], None)
        elif kind == "exec" and output_file is not None:
            output_file.write(b"synthetic-engine-native-snapshot\n")
        return subprocess.CompletedProcess(argv, 0, stdout=b"", stderr=b"")


def labels_from(argv: list[str]) -> dict[str, str]:
    labels = {}
    for index, value in enumerate(argv):
        if value == "--label":
            key, content = argv[index + 1].split("=", 1)
            labels[key] = content
    return labels


class WorkerTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="phase7-database-worker-")
        self.root = Path(self.temporary.name)
        os.environ["ATENEA_DATABASE_TEST_MODE"] = "1"
        os.environ["ATENEA_DATABASE_TEST_ROOT"] = str(self.root)
        FakeDocker.resources = {"container": {}, "network": {}, "volume": {}}
        MODULE.Docker = FakeDocker
        self.fixture = REPO_ROOT / "runtime-contract/fixtures/valid/database-postgresql"
        self.worktree = self.root / "workspaces/sessions" / SESSION_ID / "database-postgresql"
        shutil.copytree(self.fixture, self.worktree)
        allocation = {
            "schemaVersion": 1,
            "sessionId": SESSION_ID,
            "projectId": "database-postgresql",
            "worktreePath": str(self.worktree),
            "manifestRelativePath": "runtime.json",
            "runtimeId": "ws-" + SESSION_ID.replace("-", ""),
            "slot": "slot3",
            "state": "allocated",
            "allocatedPorts": [
                {
                    "name": "database",
                    "bindAddress": "127.0.0.1",
                    "loopbackPort": 24001,
                    "internalPort": 5432,
                    "protocol": "tcp",
                }
            ],
        }
        path = self.worktree.parent / "runtime-allocation-v1.json"
        path.write_text(json.dumps(allocation), encoding="utf-8")
        (self.root / "enabled").touch(mode=0o640)
        self.lifecycle = MODULE.Lifecycle()

    def tearDown(self):
        os.environ.pop("ATENEA_DATABASE_TEST_MODE", None)
        os.environ.pop("ATENEA_DATABASE_TEST_ROOT", None)
        self.temporary.cleanup()

    def register(self):
        return self.lifecycle.register(DATABASE_ID, SESSION_ID)["record"]

    def healthy(self):
        self.register()
        self.lifecycle.create(DATABASE_ID)
        self.lifecycle.apply_sql(DATABASE_ID, "migrationPaths")
        self.lifecycle.apply_sql(DATABASE_ID, "seedPaths")
        return self.lifecycle.health(DATABASE_ID)

    def test_complete_lifecycle_is_exact_and_idempotent(self):
        record = self.register()
        self.assertEqual(record, self.register())
        created = self.lifecycle.create(DATABASE_ID)
        self.assertEqual(created, self.lifecycle.create(DATABASE_ID))
        self.lifecycle.apply_sql(DATABASE_ID, "migrationPaths")
        self.lifecycle.apply_sql(DATABASE_ID, "seedPaths")
        healthy = self.lifecycle.health(DATABASE_ID)
        self.assertEqual("HEALTHY", healthy["state"])
        self.assertEqual(healthy, self.lifecycle.apply_sql(DATABASE_ID, "migrationPaths"))
        self.assertEqual(healthy, self.lifecycle.apply_sql(DATABASE_ID, "seedPaths"))
        status = self.lifecycle.status(DATABASE_ID)
        self.assertEqual(
            {"container": "present", "network": "present", "volume": "present"},
            status["resources"],
        )
        self.assertFalse(status["hostPortPublished"])

    def test_snapshot_restore_and_verified_replacement(self):
        healthy = self.healthy()
        first = self.lifecycle.snapshot(
            DATABASE_ID, "71000000-0000-4000-8000-000000000003"
        )
        self.lifecycle.verify_snapshot(DATABASE_ID, first["snapshotId"])
        prepared = self.lifecycle.registry.prepare_replace(
            DATABASE_ID,
            healthy["lifecycleRevision"],
            confirmation="explicit-phase7-replacement-confirmation",
            operation_id="71000000-0000-4000-8000-000000000004",
        )
        replaced = self.lifecycle.replace(
            DATABASE_ID,
            prepared["expectedRevision"],
            prepared["operationId"],
            prepared["confirmation"],
        )
        self.assertEqual("HEALTHY", replaced["record"]["state"])
        restored = self.lifecycle.restore(
            DATABASE_ID,
            replaced["record"]["lifecycleRevision"],
            first["snapshotId"],
        )
        self.assertEqual("HEALTHY", restored["state"])

    def test_foreign_partial_and_ambiguous_resources_are_unchanged(self):
        record = self.register()
        identity = record["networkIdentity"]
        FakeDocker.resources["network"][identity] = {
            "Name": identity,
            "Labels": {"com.atenea.engine": MODULE.ENGINE_LABEL},
        }
        before = hashlib.sha256(
            json.dumps(FakeDocker.resources, sort_keys=True).encode()
        ).hexdigest()
        with self.assertRaises(MODULE.LifecycleError) as raised:
            self.lifecycle.create(DATABASE_ID)
        self.assertEqual("DATABASE_RESOURCE_AMBIGUOUS", raised.exception.code)
        after = hashlib.sha256(
            json.dumps(FakeDocker.resources, sort_keys=True).encode()
        ).hexdigest()
        self.assertEqual(before, after)

    def test_stale_allocation_fails_before_docker_mutation(self):
        self.register()
        allocation = self.worktree.parent / "runtime-allocation-v1.json"
        document = json.loads(allocation.read_text())
        document["slot"] = "slot4"
        allocation.write_text(json.dumps(document))
        with self.assertRaises(MODULE.LifecycleError) as raised:
            self.lifecycle.create(DATABASE_ID)
        self.assertEqual("DATABASE_OWNERSHIP_CONFLICT", raised.exception.code)
        self.assertEqual({}, FakeDocker.resources["container"])

    def test_unconfirmed_expired_and_replayed_replacement_mutate_nothing(self):
        healthy = self.healthy()
        prepared = self.lifecycle.registry.prepare_replace(
            DATABASE_ID,
            healthy["lifecycleRevision"],
            confirmation="explicit-phase7-replacement-confirmation",
            operation_id="71000000-0000-4000-8000-000000000004",
        )
        before = json.dumps(FakeDocker.resources, sort_keys=True)
        with self.assertRaises(MODULE.LifecycleError):
            self.lifecycle.replace(
                DATABASE_ID,
                prepared["expectedRevision"],
                prepared["operationId"],
                "wrong-confirmation-value-long-enough",
            )
        self.assertEqual(before, json.dumps(FakeDocker.resources, sort_keys=True))

    def test_stop_and_exact_cleanup_are_repeatable(self):
        self.healthy()
        stopped = self.lifecycle.stop(DATABASE_ID)
        self.assertEqual(stopped, self.lifecycle.stop(DATABASE_ID))
        restarted = self.lifecycle.create(DATABASE_ID)
        self.assertEqual("CREATED", restarted["state"])
        stopped = self.lifecycle.stop(DATABASE_ID)
        self.lifecycle.cleanup(DATABASE_ID)
        self.assertEqual(
            {"container": {}, "network": {}, "volume": {}}, FakeDocker.resources
        )

    def test_restart_reconcile_and_retention_use_persisted_records(self):
        self.healthy()
        identities = [
            "71000000-0000-4000-8000-000000000010",
            "71000000-0000-4000-8000-000000000011",
            "71000000-0000-4000-8000-000000000012",
            "71000000-0000-4000-8000-000000000013",
        ]
        for identity in identities:
            self.lifecycle.snapshot(DATABASE_ID, identity)
        restarted = MODULE.Lifecycle()
        records = restarted.registry.reconcile()
        self.assertEqual(DATABASE_ID, records[0]["databaseId"])
        self.assertFalse(records[0]["implicitCreation"])
        removed = restarted.retain(DATABASE_ID)
        self.assertEqual([identities[0]], removed["removedSnapshotIds"])
        self.assertEqual(3, len(restarted.registry.snapshots(DATABASE_ID)))

    def test_cli_protocol_and_argument_surface_have_no_endpoint_or_password(self):
        completed = subprocess.run(
            [str(MODULE_PATH), "verify"],
            env={**os.environ},
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            check=True,
            timeout=10,
            text=True,
        )
        output = json.loads(completed.stdout)
        self.assertEqual(MODULE.PROTOCOL, output["protocolVersion"])
        self.assertFalse(output["hostListenerRequired"])
        rejected = subprocess.run(
            [str(MODULE_PATH), "create", DATABASE_ID, "--endpoint", "foreign:5432"],
            env={**os.environ},
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            timeout=10,
            text=True,
        )
        self.assertNotEqual(0, rejected.returncode)
        self.assertNotIn("password", completed.stdout.lower())

    def test_fixed_client_argv_consumes_database_once_for_both_engines(self):
        postgresql = MODULE.Lifecycle.client_command(
            {
                "engine": "postgresql",
                "containerIdentity": "exact-container",
                "databaseName": "synthetic_phase7_postgresql",
            },
            "SELECT 1",
        )
        mariadb = MODULE.Lifecycle.client_command(
            {
                "engine": "mariadb",
                "containerIdentity": "exact-container",
                "databaseName": "synthetic_phase7_mariadb",
            },
            "SELECT 1",
        )
        self.assertIn('database="$1"; shift', postgresql[5])
        self.assertIn('database="$1"; shift', mariadb[5])
        self.assertEqual(
            ["database-client", "synthetic_phase7_postgresql", "-c", "SELECT 1"],
            postgresql[-4:],
        )
        self.assertEqual(
            ["database-client", "synthetic_phase7_mariadb", "-e", "SELECT 1"],
            mariadb[-4:],
        )

    def test_default_disabled_and_reconcile_do_not_create(self):
        (self.root / "enabled").unlink()
        lifecycle = MODULE.Lifecycle()
        with self.assertRaises(MODULE.LifecycleError) as raised:
            lifecycle.require_enabled()
        self.assertEqual("DATABASE_LIFECYCLE_DISABLED", raised.exception.code)
        self.assertEqual([], lifecycle.registry.reconcile())
        self.assertEqual(
            {"container": {}, "network": {}, "volume": {}}, FakeDocker.resources
        )
        self.assertEqual({"stop", "cleanup"}, MODULE.DISABLED_ROLLBACK_ACTIONS)
        self.assertTrue(
            MODULE.DISABLED_ROLLBACK_ACTIONS < MODULE.MUTATING_ACTIONS
        )


if __name__ == "__main__":
    unittest.main(verbosity=2)
