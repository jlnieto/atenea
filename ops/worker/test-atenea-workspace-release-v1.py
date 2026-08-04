#!/usr/bin/env python3

from __future__ import annotations

import copy
import importlib.util
import json
import os
import stat
import tempfile
import unittest
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path
from unittest import mock


MODULE_PATH = Path(__file__).with_name("atenea-workspace-release-v1.py")
SPEC = importlib.util.spec_from_file_location("atenea_workspace_release_v1", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class WorkspaceReleasePreflightTest(unittest.TestCase):
    session = "7151dce0-69ab-4614-86e4-f93f1af825e4"
    operation = "706329f4-e9ea-46e4-b950-0afd09684e2d"
    idempotency = "940e80b1-a7f5-4ff8-a5f2-bdd815a07a92"
    commit = "6" * 40
    allocation_sha = "a" * 64
    runtime = "ws-7151dce069ab461486e4f93f1af825e4"
    preview_id = "c5161f55-86d4-45b7-9e96-33ae99b908e8"
    execution_id = "008ef456-3678-4f01-a77c-61631554de01"

    def setUp(self) -> None:
        self.request = {
            "operationId": self.operation,
            "idempotencyKey": self.idempotency,
            "sessionId": self.session,
            "workspaceIdentity": f"remote:ax42-01:work-session:{self.session}",
            "projectId": "atenea",
            "repository": "https://github.com/jlnieto/atenea.git",
            "branch": "main",
            "commit": self.commit,
            "manifestSha256": MODULE.MANIFEST_SHA256,
            "workspaceBranch": f"atenea/session-{self.session}",
        }
        session_root = f"/srv/atenea/workspaces/sessions/{self.session}"
        worktree = f"{session_root}/atenea"
        ports = [
            {
                "name": "codex", "internalPort": 8092, "protocol": "tcp",
                "bindAddress": "127.0.0.1", "loopbackPort": 21001,
            },
            {
                "name": "postgres", "internalPort": 5432, "protocol": "tcp",
                "bindAddress": "127.0.0.1", "loopbackPort": 21002,
            },
            {
                "name": "web", "internalPort": 8081, "protocol": "http",
                "bindAddress": "127.0.0.1", "loopbackPort": 21003,
            },
        ]
        self.projection = {
            "schemaVersion": MODULE.SCHEMA,
            "requestFingerprintSha256": MODULE.canonical_hash(self.request),
            "sessionId": self.session,
            "workspaceIdentity": self.request["workspaceIdentity"],
            "projectId": "atenea",
            "workerId": "ax42-01",
            "workspace": {
                "recordPath": f"{session_root}/workspace-v1.json",
                "worktreePath": worktree,
                "sessionId": self.session,
                "projectId": "atenea",
                "repository": self.request["repository"],
                "baseBranch": "main",
                "workspaceBranch": self.request["workspaceBranch"],
                "canonicalCommit": self.commit,
                "manifestSha256": MODULE.MANIFEST_SHA256,
                "state": "ready",
                "owner": "atenea-worker",
                "group": "atenea",
                "mode": 640,
                "symlink": False,
            },
            "registry": {
                "configurationPath": "/etc/atenea-worker/project-codex-v1.json",
                "selectionEnabled": True,
                "executionEnabled": True,
                "workspaceIdentity": self.request["workspaceIdentity"],
                "sessionId": self.session,
                "worktreePath": worktree,
                "allocationFingerprintSha256": self.allocation_sha,
                "canonicalCommit": self.commit,
                "projectId": "atenea",
                "repository": self.request["repository"],
                "owner": "root",
                "group": "atenea",
                "mode": 640,
                "symlink": False,
            },
            "admission": {
                "recordPath": f"/srv/atenea/worker/runtime-admission-v1/records/{self.session}.json",
                "sessionId": self.session,
                "normal": {"slot": "slot2", "state": "held"},
                "heavy": {"permit": "heavy1", "state": "held"},
                "owner": "atenea-worker",
                "group": "atenea",
                "mode": 640,
                "symlink": False,
            },
            "allocation": {
                "recordPath": f"{session_root}/runtime-allocation-v1.json",
                "fingerprintSha256": self.allocation_sha,
                "sessionId": self.session,
                "projectId": "atenea",
                "runtimeId": self.runtime,
                "worktreePath": worktree,
                "manifestRelativePath": "ops/atenea-runtime.json",
                "slot": "slot2",
                "heavyPermit": "heavy1",
                "state": "allocated",
                "owner": "atenea-worker",
                "group": "atenea",
                "mode": 640,
                "symlink": False,
                "allocatedPorts": ports,
            },
            "runtimeContainers": [
                self.candidate(
                    f"{self.runtime}-atenea-dev", "atenea-dev",
                    {"service": "atenea-dev", "state": "stopped"},
                )
            ],
            "runtimeNetworks": [
                self.candidate(
                    f"{self.runtime}-network", "runtime",
                    {"service": "runtime", "internal": True},
                )
            ],
            "sessionImages": [
                self.candidate(
                    f"{self.runtime}-image-{'b' * 64}", "session-image",
                    {"service": "session-image", "imageId": "b" * 64},
                )
            ],
            "previewResources": [
                self.candidate(
                    f"preview:{self.preview_id}", "preview",
                    {
                        "service": "preview", "previewId": self.preview_id,
                        "ingressPort": 23001, "upstreamPort": 21003,
                        "state": "READY",
                    },
                )
            ],
            "listeners": [
                self.candidate(
                    "listener:tcp:127.0.0.1:21003", "runtime-listener",
                    {
                        "service": "runtime-listener", "role": "runtime",
                        "bindAddress": "127.0.0.1", "port": 21003,
                        "previewId": None,
                    },
                ),
                self.candidate(
                    "listener:tcp:100.80.20.10:23001", "preview-listener",
                    {
                        "service": "preview-listener", "role": "preview",
                        "bindAddress": "100.80.20.10", "port": 23001,
                        "previewId": self.preview_id,
                    },
                ),
            ],
            "brokerResources": [
                self.candidate(
                    "broker:rootless:21003", "broker",
                    {"service": "broker", "kind": "rootless-port", "port": 21003},
                ),
                self.candidate(
                    f"broker:codex:{self.runtime}:21001", "codex-app-server",
                    {
                        "service": "codex-app-server",
                        "kind": "codex-loopback-proxy", "port": 21001,
                    },
                ),
            ],
            "materializations": [
                self.candidate(
                    f"materialization:{self.execution_id}", "materialization",
                    {
                        "service": "materialization", "executionId": self.execution_id,
                        "path": f"/run/atenea/codex-images/{self.execution_id}",
                        "terminal": True,
                    },
                )
            ],
            "browserProcesses": [
                self.candidate(
                    f"browser:atenea-project-codex-{self.execution_id.replace('-', '')}",
                    "browser",
                    {
                        "service": "browser", "kind": "codex",
                        "operationId": self.execution_id,
                        "unit": f"atenea-project-codex-{self.execution_id.replace('-', '')}",
                    },
                )
            ],
            "valuesExposed": False,
        }

    def candidate(self, resource_id: str, service: str, details: dict) -> dict:
        return {
            "resourceId": resource_id,
            "ownership": {
                "workerId": "ax42-01",
                "sessionId": self.session,
                "runtimeId": self.runtime,
                "projectId": "atenea",
                "allocationFingerprintSha256": self.allocation_sha,
                "labels": MODULE._expected_labels(self.session, self.runtime, service),
                "productionLike": False,
                "ambiguous": False,
            },
            "details": details,
        }

    def assert_rejected(self, projection: dict) -> None:
        with self.assertRaises(MODULE.PreflightRejected) as rejected:
            MODULE.validate_release_preflight(self.request, projection)
        self.assertEqual("WORKSPACE_RELEASE_PREFLIGHT_REJECTED", rejected.exception.code)

    def test_complete_projection_is_accepted_without_mutation(self) -> None:
        before_request = copy.deepcopy(self.request)
        before_projection = copy.deepcopy(self.projection)
        result = MODULE.validate_release_preflight(self.request, self.projection)
        self.assertEqual("PREFLIGHT_ACCEPTED", result["state"])
        self.assertEqual(self.session, result["sessionId"])
        self.assertEqual(1, result["candidateCounts"]["runtimeContainers"])
        self.assertEqual(2, result["candidateCounts"]["listeners"])
        self.assertRegex(result["ownershipFingerprintSha256"], r"^[0-9a-f]{64}$")
        self.assertFalse(result["valuesExposed"])
        self.assertEqual(before_request, self.request)
        self.assertEqual(before_projection, self.projection)

    def test_empty_ephemeral_projection_is_accepted(self) -> None:
        for category in (
            "runtimeContainers", "runtimeNetworks", "sessionImages",
            "previewResources", "listeners", "brokerResources",
            "materializations", "browserProcesses",
        ):
            self.projection[category] = []
        result = MODULE.validate_release_preflight(self.request, self.projection)
        self.assertTrue(all(value == 0 for value in result["candidateCounts"].values()))

    def test_workspace_registry_admission_and_allocation_are_all_exact(self) -> None:
        mutations = (
            ("workspace", "worktreePath", "/srv/atenea/production"),
            ("registry", "workspaceIdentity", "remote:ax42-01:work-session:foreign"),
            ("admission", "normal", {"slot": "slot4", "state": "held"}),
            ("allocation", "symlink", True),
        )
        for section, key, value in mutations:
            with self.subTest(section=section, key=key):
                changed = copy.deepcopy(self.projection)
                changed[section][key] = value
                self.assert_rejected(changed)

    def test_each_ephemeral_projection_rejects_incomplete_ownership(self) -> None:
        categories = (
            "runtimeContainers", "runtimeNetworks", "sessionImages",
            "previewResources", "listeners", "brokerResources",
            "materializations", "browserProcesses",
        )
        for category in categories:
            with self.subTest(category=category):
                changed = copy.deepcopy(self.projection)
                del changed[category][0]["ownership"]["allocationFingerprintSha256"]
                self.assert_rejected(changed)

    def test_foreign_wrong_session_wrong_project_and_ambiguous_candidates_reject(self) -> None:
        mutations = {
            "workerId": "foreign-worker",
            "sessionId": str(uuid.uuid4()),
            "projectId": "beautips",
            "ambiguous": True,
            "productionLike": True,
        }
        for key, value in mutations.items():
            with self.subTest(key=key):
                changed = copy.deepcopy(self.projection)
                changed["runtimeContainers"][0]["ownership"][key] = value
                self.assert_rejected(changed)

    def test_unknown_or_production_like_identity_rejects(self) -> None:
        for identity in ("foreign-container", "atenea-production-runtime"):
            with self.subTest(identity=identity):
                changed = copy.deepcopy(self.projection)
                changed["runtimeContainers"][0]["resourceId"] = identity
                self.assert_rejected(changed)

    def test_duplicate_candidate_identity_rejects_whole_projection(self) -> None:
        changed = copy.deepcopy(self.projection)
        changed["runtimeContainers"].append(copy.deepcopy(changed["runtimeContainers"][0]))
        self.assert_rejected(changed)

    def test_listener_must_resolve_to_allocation_or_exact_preview(self) -> None:
        changed = copy.deepcopy(self.projection)
        changed["listeners"][0]["details"]["port"] = 29999
        changed["listeners"][0]["resourceId"] = "listener:tcp:127.0.0.1:29999"
        self.assert_rejected(changed)

    def test_materialization_and_browser_operation_ids_are_canonical(self) -> None:
        for category, field in (
            ("materializations", "executionId"),
            ("browserProcesses", "operationId"),
        ):
            with self.subTest(category=category):
                changed = copy.deepcopy(self.projection)
                changed[category][0]["details"][field] = "not-an-operation"
                self.assert_rejected(changed)

    def test_request_fingerprint_and_projection_schema_are_immutable(self) -> None:
        changed = copy.deepcopy(self.projection)
        changed["requestFingerprintSha256"] = "0" * 64
        self.assert_rejected(changed)
        changed = copy.deepcopy(self.projection)
        changed["unexpected"] = True
        self.assert_rejected(changed)


class ReleaseJournalStoreTest(unittest.TestCase):
    def setUp(self) -> None:
        fixture = WorkspaceReleasePreflightTest(
            "test_complete_projection_is_accepted_without_mutation"
        )
        fixture.setUp()
        self.request = copy.deepcopy(fixture.request)
        self.projection = copy.deepcopy(fixture.projection)
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        os.chmod(self.root, 0o700)
        self.now = datetime(2026, 8, 4, 4, 0, tzinfo=timezone.utc)

        def clock() -> datetime:
            observed = self.now
            self.now += timedelta(seconds=1)
            return observed

        self.store = MODULE.ReleaseJournalStore(
            self.root, test_mode=True, clock=clock
        )

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def journal_path(self) -> Path:
        return self.root / self.request["sessionId"] / "journal-v1.json"

    def prepare(self) -> dict:
        return self.store.prepare(self.request, self.projection)

    def assert_rejected(self, operation) -> None:
        with self.assertRaises(MODULE.PreflightRejected) as rejected:
            operation()
        self.assertEqual("WORKSPACE_RELEASE_PREFLIGHT_REJECTED", rejected.exception.code)

    def test_prepare_persists_private_sealed_immutable_identity_once(self) -> None:
        first = self.prepare()
        path = self.journal_path()
        observed = path.stat()
        before = path.read_bytes()
        before_mtime = observed.st_mtime_ns
        second = self.prepare()
        self.assertEqual(first, second)
        self.assertEqual(before, path.read_bytes())
        self.assertEqual(before_mtime, path.stat().st_mtime_ns)
        self.assertEqual("PREPARED", first["state"])
        self.assertEqual(1, first["revision"])
        self.assertEqual(
            first["ownershipFingerprintSha256"],
            first["stageEvidence"]["PREPARED"],
        )
        self.assertEqual(0o600, stat.S_IMODE(observed.st_mode))
        self.assertEqual(1, observed.st_nlink)
        self.assertEqual(0o700, stat.S_IMODE(path.parent.stat().st_mode))

    def test_all_stages_advance_once_in_exact_monotonic_order(self) -> None:
        current = self.prepare()
        for revision, (expected, successor) in enumerate(
            zip(MODULE.JOURNAL_STAGES, MODULE.JOURNAL_STAGES[1:]), start=2
        ):
            evidence = f"{revision:x}" * 64
            evidence = evidence[:64]
            current = self.store.advance(
                self.request, expected, successor, evidence
            )
            self.assertEqual(successor, current["state"])
            self.assertEqual(revision, current["revision"])
            self.assertEqual(evidence, current["stageEvidence"][successor])
            self.assertEqual(
                MODULE.canonical_hash({
                    key: value
                    for key, value in current.items()
                    if key != "journalSha256"
                }),
                current["journalSha256"],
            )
        self.assertEqual("RELEASED", self.store.load(self.request)["state"])
        self.assertEqual(set(MODULE.JOURNAL_STAGES), set(current["stageEvidence"]))

    def test_skip_backward_and_wrong_expected_stage_leave_bytes_unchanged(self) -> None:
        self.prepare()
        path = self.journal_path()
        before = path.read_bytes()
        invalid = (
            ("PREPARED", "UNREGISTERED"),
            ("EPHEMERAL_RELEASED", "PREPARED"),
            ("EPHEMERAL_RELEASED", "UNREGISTERED"),
        )
        for expected, successor in invalid:
            with self.subTest(expected=expected, successor=successor):
                self.assert_rejected(
                    lambda expected=expected, successor=successor: self.store.advance(
                        self.request, expected, successor, "1" * 64
                    )
                )
                self.assertEqual(before, path.read_bytes())

    def test_changed_request_or_preflight_cannot_reuse_journal(self) -> None:
        self.prepare()
        path = self.journal_path()
        before = path.read_bytes()
        changed_request = copy.deepcopy(self.request)
        changed_request["idempotencyKey"] = str(uuid.uuid4())
        self.assert_rejected(
            lambda: self.store.prepare(changed_request, self.projection)
        )
        changed_projection = copy.deepcopy(self.projection)
        changed_projection["runtimeContainers"] = []
        self.assert_rejected(
            lambda: self.store.prepare(self.request, changed_projection)
        )
        self.assertEqual(before, path.read_bytes())

    def test_forged_schema_revision_evidence_or_seal_rejects_on_read(self) -> None:
        self.prepare()
        path = self.journal_path()
        original = json.loads(path.read_text(encoding="utf-8"))
        for key, value in (
            ("schemaVersion", "foreign"),
            ("revision", 6),
            ("stageEvidence", {"PREPARED": "0" * 64, "UNREGISTERED": "1" * 64}),
            ("journalSha256", "0" * 64),
        ):
            with self.subTest(key=key):
                changed = copy.deepcopy(original)
                changed[key] = value
                path.write_text(json.dumps(changed), encoding="utf-8")
                os.chmod(path, 0o600)
                self.assert_rejected(lambda: self.store.load(self.request))
        path.write_text(json.dumps(original), encoding="utf-8")
        os.chmod(path, 0o600)
        self.assertEqual("PREPARED", self.store.load(self.request)["state"])

    def test_symlinked_or_non_private_journal_is_rejected_without_following(self) -> None:
        session_root = self.root / self.request["sessionId"]
        session_root.mkdir(mode=0o700)
        foreign = self.root / "foreign.json"
        foreign.write_text("foreign", encoding="utf-8")
        path = session_root / "journal-v1.json"
        path.symlink_to(foreign)
        before = foreign.read_bytes()
        self.assert_rejected(self.prepare)
        self.assertEqual(before, foreign.read_bytes())
        path.unlink()
        path.write_text("{}", encoding="utf-8")
        os.chmod(path, 0o644)
        self.assert_rejected(lambda: self.store.load(self.request))

    def test_invalid_stage_evidence_is_rejected_before_write(self) -> None:
        self.prepare()
        before = self.journal_path().read_bytes()
        for evidence in ("", "f" * 63, "g" * 64):
            with self.subTest(evidence=evidence):
                self.assert_rejected(
                    lambda evidence=evidence: self.store.advance(
                        self.request, "PREPARED", "EPHEMERAL_RELEASED", evidence
                    )
                )
                self.assertEqual(before, self.journal_path().read_bytes())

    def test_atomic_replace_failure_preserves_previous_valid_revision(self) -> None:
        self.prepare()
        path = self.journal_path()
        before = path.read_bytes()
        with mock.patch.object(MODULE.os, "replace", side_effect=OSError("synthetic")):
            self.assert_rejected(
                lambda: self.store.advance(
                    self.request, "PREPARED", "EPHEMERAL_RELEASED", "2" * 64
                )
            )
        self.assertEqual(before, path.read_bytes())
        self.assertEqual([], list(path.parent.glob(".journal-v1.*")))
        self.assertEqual("PREPARED", self.store.load(self.request)["state"])

    def test_changed_journal_between_read_and_replace_is_never_overwritten(self) -> None:
        self.prepare()
        path = self.journal_path()
        original_read = self.store._read
        raced_bytes = b""
        calls = 0

        def racing_read(target: Path) -> dict:
            nonlocal calls, raced_bytes
            observed = original_read(target)
            calls += 1
            if calls == 1:
                changed = {
                    key: value
                    for key, value in observed.items()
                    if key != "journalSha256"
                }
                changed["updatedAt"] = "2026-08-04T04:00:09Z"
                changed = MODULE.ReleaseJournalStore._seal(changed)
                raced_bytes = (
                    json.dumps(changed, sort_keys=True, separators=(",", ":")) + "\n"
                ).encode()
                path.write_bytes(raced_bytes)
                os.chmod(path, 0o600)
            return observed

        with mock.patch.object(self.store, "_read", side_effect=racing_read):
            self.assert_rejected(
                lambda: self.store.advance(
                    self.request, "PREPARED", "EPHEMERAL_RELEASED", "3" * 64
                )
            )
        self.assertEqual(raced_bytes, path.read_bytes())
        self.assertEqual([], list(path.parent.glob(".journal-v1.*")))
        self.assertEqual("PREPARED", self.store.load(self.request)["state"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
