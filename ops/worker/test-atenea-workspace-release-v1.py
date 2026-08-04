#!/usr/bin/env python3

from __future__ import annotations

import copy
import importlib.util
import unittest
import uuid
from pathlib import Path


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


if __name__ == "__main__":
    unittest.main(verbosity=2)
