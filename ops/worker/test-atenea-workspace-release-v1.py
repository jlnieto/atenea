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
AGENT_SPEC = importlib.util.spec_from_file_location(
    "agent_run_worker_release_receipt", Path(__file__).with_name("agent-run-worker-v1.py")
)
assert AGENT_SPEC is not None and AGENT_SPEC.loader is not None
AGENT_MODULE = importlib.util.module_from_spec(AGENT_SPEC)
AGENT_SPEC.loader.exec_module(AGENT_MODULE)


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


class AdversarialOwnershipFixtureTest(unittest.TestCase):
    def setUp(self) -> None:
        fixture = WorkspaceReleasePreflightTest(
            "test_complete_projection_is_accepted_without_mutation"
        )
        fixture.setUp()
        self.request = copy.deepcopy(fixture.request)
        self.projection = copy.deepcopy(fixture.projection)
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.unrelated = self.root / "unrelated-retained.fixture"
        self.unrelated.write_bytes(b"unrelated-retained\n")
        self.fixture_specs = {
            "unlabelled": ("labels", {}),
            "partially-labelled": (
                "labels",
                {"com.atenea.engine": "atenea-runtime-engine-v1"},
            ),
            "foreign-owned": ("workerId", "foreign-worker"),
            "wrong-session": (
                "sessionId", "95301be7-3b03-4ce7-91bd-38cebe8c4343",
            ),
            "wrong-project": ("projectId", "foreign-project"),
            "ambiguous": ("ambiguous", True),
        }
        self.fixture_paths = {}
        for name, (field, value) in self.fixture_specs.items():
            path = self.root / f"{name}.fixture"
            path.write_bytes(
                json.dumps(
                    {
                        "fixture": name, "field": field,
                        "synthetic": True, "value": value,
                    },
                    sort_keys=True,
                    separators=(",", ":"),
                ).encode() + b"\n"
            )
            self.fixture_paths[name] = path
        target = self.root / "symlink-target.fixture"
        target.write_bytes(b'{"fixture":"symlink-target","synthetic":true}\n')
        symlink = self.root / "symlinked.fixture"
        symlink.symlink_to(target.name)
        self.fixture_paths["symlink-target"] = target
        self.fixture_paths["symlinked"] = symlink
        self.identities = {
            name: self._identity(path)
            for name, path in self.fixture_paths.items()
        }
        self.unrelated_identity = self._identity(self.unrelated)

    def tearDown(self) -> None:
        self.temporary.cleanup()

    @staticmethod
    def _identity(path: Path) -> dict:
        observed = path.lstat()
        if stat.S_ISLNK(observed.st_mode):
            content = os.readlink(path).encode()
        else:
            content = path.read_bytes()
        return {
            "device": observed.st_dev,
            "inode": observed.st_ino,
            "mode": observed.st_mode,
            "size": observed.st_size,
            "contentSha256": MODULE.hashlib.sha256(content).hexdigest(),
        }

    def _remove_exact(self, name: str) -> None:
        path = self.fixture_paths[name]
        self.assertEqual(self.identities[name], self._identity(path))
        path.unlink()

    def test_all_inexact_fixtures_reject_unchanged_then_exact_cleanup(self) -> None:
        recorded = copy.deepcopy(self.identities)
        unrelated_before = copy.deepcopy(self.unrelated_identity)
        for name, (key, value) in self.fixture_specs.items():
            with self.subTest(fixture=name):
                changed = copy.deepcopy(self.projection)
                changed["runtimeContainers"][0]["ownership"][key] = value
                projection_before = copy.deepcopy(changed)
                with self.assertRaises(MODULE.PreflightRejected):
                    MODULE.validate_release_preflight(self.request, changed)
                self.assertEqual(projection_before, changed)
                self.assertEqual(recorded, {
                    fixture_name: self._identity(path)
                    for fixture_name, path in self.fixture_paths.items()
                })
                self.assertEqual(unrelated_before, self._identity(self.unrelated))

        symlinked = copy.deepcopy(self.projection)
        symlinked["allocation"]["symlink"] = True
        symlinked_before = copy.deepcopy(symlinked)
        with self.assertRaises(MODULE.PreflightRejected):
            MODULE.validate_release_preflight(self.request, symlinked)
        self.assertEqual(symlinked_before, symlinked)
        self.assertEqual(recorded, {
            fixture_name: self._identity(path)
            for fixture_name, path in self.fixture_paths.items()
        })
        self.assertEqual(unrelated_before, self._identity(self.unrelated))

        for name in sorted(self.fixture_paths):
            self._remove_exact(name)
        self.assertTrue(all(
            not path.exists() and not path.is_symlink()
            for path in self.fixture_paths.values()
        ))
        self.assertEqual(unrelated_before, self._identity(self.unrelated))


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


class FakeReleaseBoundary:
    def __init__(self, projection: dict):
        self.projection = projection
        self.actions: list[str] = []
        self.override: dict[str, dict] = {}

    def release_ephemeral(self, _request: dict, _projection: dict) -> dict:
        self.actions.append("ephemeral")
        return self.override.get("ephemeral", {
            "schemaVersion": "atenea-ephemeral-release-v1",
            "state": "RELEASED",
            "removed": {
                category: len(self.projection[category])
                for category in MODULE.EPHEMERAL_CATEGORIES
            },
            "changed": {
                category: len(self.projection[category])
                for category in MODULE.EPHEMERAL_CATEGORIES
            },
            "policyVolumesRetained": True,
            "valuesExposed": False,
        })

    def unregister_workspace(self, request: dict) -> dict:
        self.actions.append("unregister")
        return self.override.get("unregister", {
            "schemaVersion": "atenea-workspace-unregistration-v1",
            "state": "UNREGISTERED",
            "sessionId": request["sessionId"],
            "workspaceIdentity": request["workspaceIdentity"],
            "registrationRemoved": True,
            "selectionEnabled": False,
            "executionEnabled": False,
            "remainingRegistrations": 0,
            "valuesExposed": False,
        })

    def release_heavy_admission(self, request: dict) -> dict:
        self.actions.append("heavy")
        return self._admission(request, "heavy")

    def release_normal_admission(self, request: dict) -> dict:
        self.actions.append("normal")
        return self._admission(request, "normal")

    def _admission(self, request: dict, kind: str) -> dict:
        return self.override.get(kind, {
            "schemaVersion": "atenea-admission-release-v1",
            "state": "RELEASED",
            "sessionId": request["sessionId"],
            "kind": kind,
            "changed": True,
            "valuesExposed": False,
        })

    def retire_allocation(self, request: dict, fingerprint: str) -> dict:
        self.actions.append("allocation")
        return self.override.get("allocation", {
            "schemaVersion": "atenea-allocation-retirement-v1",
            "state": "RETIRED",
            "sessionId": request["sessionId"],
            "sourceName": "runtime-allocation-v1.json",
            "retiredName": MODULE.RETIRED_ALLOCATION_NAME,
            "fingerprintSha256": fingerprint,
            "device": 1,
            "inode": 2,
            "uid": os.geteuid(),
            "gid": os.getegid(),
            "mode": 0o640,
            "size": 100,
            "mtimeNs": 10,
            "atimeBeforeNs": 10,
            "atimeAfterNs": 11,
            "ctimeBeforeNs": 10,
            "ctimeAfterNs": 11,
            "changed": True,
            "valuesExposed": False,
        })

    def verify_retained(self, request: dict, _projection: dict) -> dict:
        self.actions.append("verify")
        return self.override.get("verify", {
            "schemaVersion": "atenea-workspace-release-proof-v1",
            "state": "RELEASED",
            "sessionId": request["sessionId"],
            "ephemeralRemaining": 0,
            "registrationPresent": False,
            "normalAdmission": "released",
            "heavyAdmission": "released",
            "activeAllocationPresent": False,
            "retiredAllocationPresent": True,
            "retained": {key: True for key in MODULE.RETAINED_KEYS},
            "valuesExposed": False,
        })


class RecordingReleaseOperator:
    def __init__(self, request: dict, projection: dict):
        self.request = request
        self.resources = {
            (category, candidate["resourceId"])
            for category in MODULE.EPHEMERAL_CATEGORIES
            for candidate in projection[category]
        }
        self.known_resources = set(self.resources)
        self.removals: list[tuple[str, str]] = []
        self.changed_removals: list[tuple[str, str]] = []
        self.admissions: list[str] = []
        self.registered = True
        self.retained_volume = b"policy-retained"

    def remove_ephemeral(self, category: str, resource_id: str, _candidate: dict) -> dict:
        identity = (category, resource_id)
        if identity not in self.known_resources:
            raise MODULE.PreflightRejected()
        changed = identity in self.resources
        self.resources.discard(identity)
        self.removals.append(identity)
        if changed:
            self.changed_removals.append(identity)
        return {
            "schemaVersion": "atenea-ephemeral-resource-release-v1",
            "state": "RELEASED",
            "category": category,
            "resourceId": resource_id,
            "sessionId": self.request["sessionId"],
            "changed": changed,
            "policyVolumeChanged": False,
            "valuesExposed": False,
        }

    def unregister_workspace(self, session_id: str, workspace_identity: str) -> dict:
        changed = self.registered
        self.registered = False
        return {
            "schemaVersion": "atenea-workspace-unregistration-v1",
            "state": "UNREGISTERED",
            "sessionId": session_id,
            "workspaceIdentity": workspace_identity,
            "registrationRemoved": changed,
            "selectionEnabled": False,
            "executionEnabled": False,
            "remainingRegistrations": 0,
            "valuesExposed": False,
        }

    def release_admission(self, session_id: str, kind: str) -> dict:
        if kind == "normal" and "heavy" not in self.admissions:
            raise MODULE.PreflightRejected()
        changed = kind not in self.admissions
        if changed:
            self.admissions.append(kind)
        return {
            "schemaVersion": "atenea-admission-release-v1",
            "state": "RELEASED",
            "sessionId": session_id,
            "kind": kind,
            "changed": changed,
            "valuesExposed": False,
        }

    def verify_retained(self, session_id: str, _projection: dict) -> dict:
        if self.resources or self.registered or self.admissions != ["heavy", "normal"]:
            raise MODULE.PreflightRejected()
        return {
            "schemaVersion": "atenea-workspace-release-proof-v1",
            "state": "RELEASED",
            "sessionId": session_id,
            "ephemeralRemaining": 0,
            "registrationPresent": False,
            "normalAdmission": "released",
            "heavyAdmission": "released",
            "activeAllocationPresent": False,
            "retiredAllocationPresent": True,
            "retained": {key: True for key in MODULE.RETAINED_KEYS},
            "valuesExposed": False,
        }


class RecordingAllocationRetirer:
    def __init__(self):
        self.calls: list[tuple[str, str]] = []
        self.change_count = 0
        self.retired = False

    def retire(self, session_id: str, fingerprint: str) -> dict:
        self.calls.append((session_id, fingerprint))
        changed = not self.retired
        if changed:
            self.change_count += 1
            self.retired = True
        return {
            "schemaVersion": "atenea-allocation-retirement-v1",
            "state": "RETIRED",
            "sessionId": session_id,
            "sourceName": "runtime-allocation-v1.json",
            "retiredName": MODULE.RETIRED_ALLOCATION_NAME,
            "fingerprintSha256": fingerprint,
            "device": 1,
            "inode": 2,
            "uid": os.geteuid(),
            "gid": os.getegid(),
            "mode": 0o640,
            "size": 100,
            "mtimeNs": 10,
            "atimeBeforeNs": 10,
            "atimeAfterNs": 11,
            "ctimeBeforeNs": 10,
            "ctimeAfterNs": 11,
            "changed": changed,
            "valuesExposed": False,
        }


class SyntheticInterruption(Exception):
    pass


class InterruptAfterBoundaryCall:
    def __init__(self, delegate, method_name: str):
        self.delegate = delegate
        self.method_name = method_name
        self.interrupted = False

    def __getattr__(self, name: str):
        method = getattr(self.delegate, name)

        def call(*args, **kwargs):
            result = method(*args, **kwargs)
            if name == self.method_name and not self.interrupted:
                self.interrupted = True
                raise SyntheticInterruption(name)
            return result

        return call


class WorkspaceReleaseFinalizerTest(unittest.TestCase):
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
        self.store = MODULE.ReleaseJournalStore(self.root, test_mode=True)
        self.boundary = FakeReleaseBoundary(self.projection)
        self.finalizer = MODULE.WorkspaceReleaseFinalizer(
            self.store, self.boundary
        )

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def assert_rejected(self, operation) -> None:
        with self.assertRaises(MODULE.PreflightRejected):
            operation()

    def reviewed_components(self, root: Path):
        store = MODULE.ReleaseJournalStore(root, test_mode=True)
        operator = RecordingReleaseOperator(self.request, self.projection)
        retirer = RecordingAllocationRetirer()
        boundary = MODULE.ReviewedReleaseBoundary(operator, retirer)
        return store, operator, retirer, boundary

    def test_release_orders_exact_mutations_and_returns_closed_projection(self) -> None:
        result = self.finalizer.release(self.request, self.projection)
        self.assertEqual(
            ["ephemeral", "unregister", "heavy", "normal", "allocation", "verify"],
            self.boundary.actions,
        )
        self.assertEqual("RELEASED", result["state"])
        self.assertEqual(6, result["revision"])
        self.assertEqual(1, result["removed"]["runtimeContainers"])
        self.assertEqual(3, result["removed"]["previewResources"])
        self.assertEqual(2, result["removed"]["browserProcesses"])
        self.assertTrue(all(result["released"].values()))
        self.assertTrue(all(result["retained"].values()))
        self.assertFalse(result["valuesExposed"])
        self.assertEqual("RELEASED", self.store.load(self.request)["state"])

    def test_reviewed_boundary_removes_only_projected_ids_and_retains_volume(self) -> None:
        operator = RecordingReleaseOperator(self.request, self.projection)
        retirer = RecordingAllocationRetirer()
        boundary = MODULE.ReviewedReleaseBoundary(operator, retirer)
        finalizer = MODULE.WorkspaceReleaseFinalizer(self.store, boundary)
        expected = [
            (category, candidate["resourceId"])
            for category in MODULE.EPHEMERAL_RELEASE_ORDER
            for candidate in self.projection[category]
        ]
        before_projection = copy.deepcopy(self.projection)
        retained_volume = operator.retained_volume
        result = finalizer.release(self.request, self.projection)
        self.assertEqual(expected, operator.removals)
        self.assertEqual(set(), operator.resources)
        self.assertEqual(["heavy", "normal"], operator.admissions)
        self.assertEqual(retained_volume, operator.retained_volume)
        self.assertEqual(before_projection, self.projection)
        self.assertEqual(
            [(self.request["sessionId"], self.projection["allocation"]["fingerprintSha256"])],
            retirer.calls,
        )
        self.assertEqual("RELEASED", result["state"])

    def test_completed_repetition_returns_identical_receipt_without_mutation(self) -> None:
        store, operator, retirer, boundary = self.reviewed_components(self.root)
        finalizer = MODULE.WorkspaceReleaseFinalizer(store, boundary)
        first = finalizer.release(self.request, self.projection)
        self.assertEqual(
            first,
            AGENT_MODULE.validate_workspace_release_receipt(
                self.request, "ax42-01", first
            ),
        )
        mutation_projection = (
            list(operator.changed_removals), list(operator.admissions),
            operator.registered, retirer.change_count,
        )
        repeated = MODULE.WorkspaceReleaseFinalizer(
            MODULE.ReleaseJournalStore(self.root, test_mode=True), boundary
        ).release(self.request, self.projection)
        self.assertEqual(first, repeated)
        self.assertEqual(6, repeated["revision"])
        self.assertEqual(first["ownershipFingerprintSha256"], repeated["ownershipFingerprintSha256"])
        self.assertEqual(first["receiptSha256"], repeated["receiptSha256"])
        self.assertEqual(
            mutation_projection,
            (
                list(operator.changed_removals), list(operator.admissions),
                operator.registered, retirer.change_count,
            ),
        )

    def test_restart_after_each_mutation_resumes_same_operation(self) -> None:
        cases = (
            ("release_ephemeral", "PREPARED"),
            ("unregister_workspace", "EPHEMERAL_RELEASED"),
            ("release_heavy_admission", "UNREGISTERED"),
            ("release_normal_admission", "UNREGISTERED"),
            ("retire_allocation", "ADMISSION_RELEASED"),
            ("verify_retained", "ALLOCATION_RETIRED"),
        )
        for method_name, expected_state in cases:
            with self.subTest(method=method_name), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                os.chmod(root, 0o700)
                store, operator, retirer, reviewed = self.reviewed_components(root)
                interrupted = InterruptAfterBoundaryCall(reviewed, method_name)
                finalizer = MODULE.WorkspaceReleaseFinalizer(store, interrupted)
                with self.assertRaises(SyntheticInterruption):
                    finalizer.release(self.request, self.projection)
                self.assertEqual(expected_state, store.load(self.request)["state"])
                resumed = MODULE.WorkspaceReleaseFinalizer(
                    MODULE.ReleaseJournalStore(root, test_mode=True), interrupted
                ).release(self.request, self.projection)
                self.assertEqual("RELEASED", resumed["state"])
                self.assertEqual(
                    len(operator.known_resources), len(operator.changed_removals)
                )
                self.assertEqual(["heavy", "normal"], operator.admissions)
                self.assertEqual(1, retirer.change_count)

    def test_lost_response_after_each_journal_revision_never_repeats_stage(self) -> None:
        for next_state in MODULE.JOURNAL_STAGES[1:]:
            with self.subTest(state=next_state), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                os.chmod(root, 0o700)
                store, operator, retirer, boundary = self.reviewed_components(root)
                original_advance = store.advance
                interrupted = False

                def advance(request, expected, successor, evidence):
                    nonlocal interrupted
                    result = original_advance(request, expected, successor, evidence)
                    if successor == next_state and not interrupted:
                        interrupted = True
                        raise SyntheticInterruption(successor)
                    return result

                with mock.patch.object(store, "advance", side_effect=advance):
                    with self.assertRaises(SyntheticInterruption):
                        MODULE.WorkspaceReleaseFinalizer(store, boundary).release(
                            self.request, self.projection
                        )
                persisted = MODULE.ReleaseJournalStore(root, test_mode=True)
                self.assertEqual(next_state, persisted.load(self.request)["state"])
                receipt = MODULE.WorkspaceReleaseFinalizer(
                    persisted, boundary
                ).release(self.request, self.projection)
                self.assertEqual("RELEASED", receipt["state"])
                self.assertEqual(
                    len(operator.known_resources), len(operator.changed_removals)
                )
                self.assertEqual(["heavy", "normal"], operator.admissions)
                self.assertEqual(1, retirer.change_count)

    def test_default_boundary_is_unavailable_after_prepared_without_mutation(self) -> None:
        finalizer = MODULE.WorkspaceReleaseFinalizer(self.store)
        self.assert_rejected(lambda: finalizer.release(self.request, self.projection))
        self.assertEqual("PREPARED", self.store.load(self.request)["state"])

    def test_inexact_ephemeral_result_stops_before_unregistration(self) -> None:
        invalid = self.boundary.release_ephemeral(self.request, self.projection)
        self.boundary.actions.clear()
        invalid["removed"]["runtimeContainers"] = 0
        self.boundary.override["ephemeral"] = invalid
        self.assert_rejected(
            lambda: self.finalizer.release(self.request, self.projection)
        )
        self.assertEqual(["ephemeral"], self.boundary.actions)
        self.assertEqual("PREPARED", self.store.load(self.request)["state"])

    def test_inexact_unregistration_stops_before_admission(self) -> None:
        invalid = self.boundary.unregister_workspace(self.request)
        self.boundary.actions.clear()
        invalid["remainingRegistrations"] = 1
        self.boundary.override["unregister"] = invalid
        self.assert_rejected(
            lambda: self.finalizer.release(self.request, self.projection)
        )
        self.assertEqual(["ephemeral", "unregister"], self.boundary.actions)
        self.assertEqual(
            "EPHEMERAL_RELEASED", self.store.load(self.request)["state"]
        )

    def test_heavy_must_release_before_normal_and_allocation(self) -> None:
        self.boundary.override["heavy"] = {
            "schemaVersion": "atenea-admission-release-v1",
            "state": "RELEASED",
            "sessionId": self.request["sessionId"],
            "kind": "normal",
            "changed": True,
            "valuesExposed": False,
        }
        self.assert_rejected(
            lambda: self.finalizer.release(self.request, self.projection)
        )
        self.assertEqual(["ephemeral", "unregister", "heavy"], self.boundary.actions)
        self.assertEqual("UNREGISTERED", self.store.load(self.request)["state"])

    def test_inexact_allocation_proof_stops_before_retention_proof(self) -> None:
        valid = self.boundary.retire_allocation(
            self.request, self.projection["allocation"]["fingerprintSha256"]
        )
        self.boundary.actions.clear()
        valid["retiredName"] = "foreign.json"
        self.boundary.override["allocation"] = valid
        self.assert_rejected(
            lambda: self.finalizer.release(self.request, self.projection)
        )
        self.assertEqual(
            ["ephemeral", "unregister", "heavy", "normal", "allocation"],
            self.boundary.actions,
        )
        self.assertEqual(
            "ADMISSION_RELEASED", self.store.load(self.request)["state"]
        )

    def test_missing_retained_state_never_reaches_released(self) -> None:
        proof = self.boundary.verify_retained(self.request, self.projection)
        self.boundary.actions.clear()
        proof["retained"]["attachments"] = False
        self.boundary.override["verify"] = proof
        self.assert_rejected(
            lambda: self.finalizer.release(self.request, self.projection)
        )
        self.assertEqual(
            "ALLOCATION_RETIRED", self.store.load(self.request)["state"]
        )


class AllocationRetirerTest(unittest.TestCase):
    session = WorkspaceReleasePreflightTest.session

    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name) / "workspaces"
        self.session_root = self.root / "sessions" / self.session
        self.session_root.mkdir(parents=True, mode=0o700)
        self.source = self.session_root / "runtime-allocation-v1.json"
        self.content = b'{"schemaVersion":1,"state":"allocated"}\n'
        self.source.write_bytes(self.content)
        os.chmod(self.source, 0o640)
        self.fingerprint = MODULE.hashlib.sha256(self.content).hexdigest()
        self.retained = self.session_root / "workspace-v1.json"
        self.retained.write_bytes(b"retained")
        self.retirer = MODULE.AllocationRetirer(self.root, test_mode=True)

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def assert_rejected(self, operation) -> None:
        with self.assertRaises(MODULE.PreflightRejected):
            operation()

    def test_same_directory_rename_preserves_required_identity_and_retained_state(self) -> None:
        before = self.source.stat()
        retained_before = self.retained.read_bytes()
        result = self.retirer.retire(self.session, self.fingerprint)
        retired = self.session_root / MODULE.RETIRED_ALLOCATION_NAME
        after = retired.stat()
        self.assertFalse(self.source.exists())
        self.assertEqual(self.content, retired.read_bytes())
        for field in ("st_dev", "st_ino", "st_uid", "st_gid", "st_mode", "st_size", "st_mtime_ns"):
            self.assertEqual(getattr(before, field), getattr(after, field))
        self.assertEqual(before.st_ino, result["inode"])
        self.assertEqual(self.fingerprint, result["fingerprintSha256"])
        self.assertEqual(retained_before, self.retained.read_bytes())
        repeated = self.retirer.retire(self.session, self.fingerprint)
        self.assertFalse(repeated["changed"])
        self.assertEqual(before.st_ino, repeated["inode"])
        self.assertFalse(self.source.exists())
        self.assertEqual(self.content, retired.read_bytes())

    def test_wrong_fingerprint_or_existing_retired_target_rejects_unchanged(self) -> None:
        before = self.source.read_bytes()
        self.assert_rejected(lambda: self.retirer.retire(self.session, "0" * 64))
        self.assertEqual(before, self.source.read_bytes())
        retired = self.session_root / MODULE.RETIRED_ALLOCATION_NAME
        retired.write_bytes(b"foreign")
        self.assert_rejected(
            lambda: self.retirer.retire(self.session, self.fingerprint)
        )
        self.assertEqual(before, self.source.read_bytes())
        self.assertEqual(b"foreign", retired.read_bytes())

    def test_symlinked_active_allocation_is_rejected_without_following(self) -> None:
        self.source.unlink()
        foreign = self.session_root / "foreign.json"
        foreign.write_bytes(self.content)
        self.source.symlink_to(foreign)
        before = foreign.read_bytes()
        self.assert_rejected(
            lambda: self.retirer.retire(self.session, self.fingerprint)
        )
        self.assertEqual(before, foreign.read_bytes())


if __name__ == "__main__":
    unittest.main(verbosity=2)
