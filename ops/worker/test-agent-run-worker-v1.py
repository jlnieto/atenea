#!/usr/bin/env python3

import json
import tempfile
import threading
import time
import unittest
from unittest import mock
import uuid
import os
import subprocess
import urllib.error
import urllib.request
from pathlib import Path

from importlib.machinery import SourceFileLoader

MODULE = SourceFileLoader(
    "agent_run_worker_v1",
    str(Path(__file__).with_name("agent-run-worker-v1.py")),
).load_module()
TEST_COMMIT = "1" * 40


class WorkerStateTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.state = MODULE.WorkerState(Path(self.temporary.name), "test-worker", 4, 2)
        self.state.start()

    def tearDown(self):
        self.state.stop()
        self.temporary.cleanup()

    def request(self, *, workload_class="NORMAL", duration=250, message="hello"):
        return {
            "dispatchId": str(uuid.uuid4()),
            "sessionId": str(uuid.uuid4()),
            "workspaceIdentity": "remote:test:" + str(uuid.uuid4()),
            "workloadClass": workload_class,
            "leaseGeneration": 1,
            "workload": {
                "kind": "synthetic-routing-v1",
                "message": message,
                "durationMs": duration,
                "steps": 5,
            },
        }

    def wait_terminal(self, dispatch_id, timeout=5):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            execution = self.state.get(dispatch_id)
            if execution["status"] in MODULE.TERMINAL:
                return execution
            time.sleep(0.02)
        self.fail("execution did not become terminal")

    def test_duplicate_dispatch_returns_same_execution_and_conflict_is_closed(self):
        request = self.request()
        first, created = self.state.create(request)
        second, created_again = self.state.create(json.loads(json.dumps(request)))
        self.assertTrue(created)
        self.assertFalse(created_again)
        self.assertEqual(first["executionId"], second["executionId"])
        request["workload"]["message"] = "different"
        with self.assertRaisesRegex(MODULE.ProtocolError, "different immutable request"):
            self.state.create(request)
        self.assertEqual(first["executionId"], self.state.get(first["dispatchId"])["executionId"])

    def test_four_normal_slots_queue_fifth(self):
        requests = [self.request(duration=800) for _ in range(5)]
        for request in requests:
            self.state.create(request)
        time.sleep(0.15)
        statuses = [self.state.get(item["dispatchId"])["status"] for item in requests]
        self.assertEqual(4, sum(status in {"STARTING", "RUNNING"} for status in statuses))
        self.assertEqual(1, statuses.count("QUEUED"))

    def test_two_heavy_permits_queue_third(self):
        requests = [self.request(workload_class="HEAVY", duration=700) for _ in range(3)]
        for request in requests:
            self.state.create(request)
        time.sleep(0.15)
        statuses = [self.state.get(item["dispatchId"])["status"] for item in requests]
        self.assertEqual(2, sum(status in {"STARTING", "RUNNING"} for status in statuses))
        self.assertEqual(1, statuses.count("QUEUED"))

    def test_cancel_exact_execution_preserves_other(self):
        first = self.request(duration=700)
        second = self.request(duration=250)
        first_execution, _ = self.state.create(first)
        second_execution, _ = self.state.create(second)
        self.state.cancel(first["dispatchId"], {"executionId": first_execution["executionId"]})
        cancelled = self.wait_terminal(first["dispatchId"])
        completed = self.wait_terminal(second["dispatchId"])
        self.assertEqual("CANCELLED", cancelled["status"])
        self.assertEqual("SUCCEEDED", completed["status"])

    def test_restart_recovers_same_execution_identity(self):
        request = self.request(duration=700)
        created, _ = self.state.create(request)
        time.sleep(0.15)
        self.state.stop()
        recovered = MODULE.WorkerState(Path(self.temporary.name), "test-worker", 4, 2)
        recovered.start()
        self.state = recovered
        terminal = self.wait_terminal(request["dispatchId"])
        self.assertEqual(created["executionId"], terminal["executionId"])
        self.assertEqual("SUCCEEDED", terminal["status"])

    def test_unknown_or_arbitrary_fields_are_rejected(self):
        request = self.request()
        request["command"] = "id"
        with self.assertRaisesRegex(MODULE.ProtocolError, "dispatch fields"):
            self.state.create(request)


class WorkspaceActivationTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        root = Path(self.temporary.name)
        self.calls = root / "calls"
        self.activator = root / "activator"
        self.activator.write_text(
            """#!/usr/bin/env python3
import json
import pathlib
import sys
calls = pathlib.Path(sys.argv[0]).with_name("calls")
calls.write_text(calls.read_text() + "1\\n" if calls.exists() else "1\\n")
session_id = sys.argv[2]
branch = sys.argv[3]
print(json.dumps({
    "state": "ready",
    "sessionId": session_id,
    "workspaceIdentity": "remote:ax42-01:work-session:" + session_id,
    "projectId": "beautips",
    "workspaceBranch": branch,
    "slot": "slot4",
    "canonicalCommit": "e9e0b3c319c518363d4135f5378ebbddced96dfb",
    "selectionEnabled": True,
    "executionEnabled": True,
    "valuesExposed": False,
}))
""",
            encoding="utf-8",
        )
        self.activator.chmod(0o755)
        self.atenea_activator = root / "atenea-activator"
        self.atenea_activator.write_text(
            self.activator.read_text()
            .replace('"projectId": "beautips"', '"projectId": "atenea"')
            .replace('"slot": "slot4"', '"slot": "slot2"')
            .replace(
                '"e9e0b3c319c518363d4135f5378ebbddced96dfb"',
                '"' + TEST_COMMIT + '"',
            ),
            encoding="utf-8",
        )
        self.atenea_activator.chmod(0o755)
        self.state = MODULE.WorkerState(
            root / "state",
            "ax42-01",
            privilege_command=(),
            project_workspace_activator=self.atenea_activator,
            beautips_workspace_activator=self.activator,
        )
        self.state._observe_project_commit = lambda route: (
            TEST_COMMIT
            if route["identity"]["projectId"] == MODULE.PROJECT_ID
            else MODULE.BEAUTIPS_PROJECT_COMMIT
        )
        self.session_id = str(uuid.uuid4())

    def tearDown(self):
        self.temporary.cleanup()

    def request(self):
        return {
            "sessionId": self.session_id,
            "workspaceIdentity": "remote:ax42-01:work-session:" + self.session_id,
            "projectId": MODULE.BEAUTIPS_PROJECT_ID,
            "repository": MODULE.BEAUTIPS_PROJECT_REPOSITORY,
            "branch": MODULE.BEAUTIPS_PROJECT_BRANCH,
            "commit": MODULE.BEAUTIPS_PROJECT_COMMIT,
            "manifestSha256": MODULE.BEAUTIPS_PROJECT_MANIFEST_SHA256,
            "workspaceBranch": "atenea/session-" + self.session_id,
        }

    def test_exact_workspace_can_be_ensured_repeatedly(self):
        first = self.state.ensure_workspace(self.request())
        second = self.state.ensure_workspace(self.request())
        self.assertEqual(first, second)
        self.assertEqual("ready", first["state"])
        self.assertEqual(2, len(self.calls.read_text().splitlines()))

    def test_exact_atenea_workspace_uses_its_separate_activator(self):
        request = self.request()
        request.update({
            "projectId": MODULE.PROJECT_ID,
            "repository": MODULE.PROJECT_REPOSITORY,
            "branch": MODULE.PROJECT_BRANCH,
            "commit": TEST_COMMIT,
            "manifestSha256": MODULE.PROJECT_MANIFEST_SHA256,
        })
        result = self.state.ensure_workspace(request)
        self.assertEqual("ready", result["state"])
        self.assertEqual("atenea", result["projectId"])
        self.assertEqual("slot2", result["slot"])

    def test_foreign_identity_and_arbitrary_field_fail_before_activation(self):
        foreign = self.request()
        foreign["repository"] = "https://github.com/foreign/beautips.git"
        with self.assertRaisesRegex(MODULE.ProtocolError, "not exact"):
            self.state.ensure_workspace(foreign)
        arbitrary = self.request()
        arbitrary["command"] = "id"
        with self.assertRaisesRegex(MODULE.ProtocolError, "fields"):
            self.state.ensure_workspace(arbitrary)
        self.assertFalse(self.calls.exists())

    def test_noncanonical_branch_fails_before_activation(self):
        request = self.request()
        request["workspaceBranch"] = "main"
        with self.assertRaisesRegex(MODULE.ProtocolError, "persisted WorkSession"):
            self.state.ensure_workspace(request)
        self.assertFalse(self.calls.exists())

    def test_branch_owned_by_another_session_fails_before_activation(self):
        request = self.request()
        request["workspaceBranch"] = "atenea/session-22222222-2222-4222-8222-222222222222"
        with self.assertRaisesRegex(MODULE.ProtocolError, "persisted WorkSession"):
            self.state.ensure_workspace(request)
        self.assertFalse(self.calls.exists())


class RetainedDraftFingerprintTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        root = Path(self.temporary.name)
        self.worktree = root / "worktree"
        self.worktree.mkdir()
        subprocess.run(["git", "init", "-q", "-b", MODULE.PROJECT_BRANCH], cwd=self.worktree, check=True)
        subprocess.run(["git", "config", "user.name", "Test"], cwd=self.worktree, check=True)
        subprocess.run(["git", "config", "user.email", "test@example.invalid"], cwd=self.worktree, check=True)
        (self.worktree / "tracked.txt").write_text("base\n", encoding="utf-8")
        subprocess.run(["git", "add", "tracked.txt"], cwd=self.worktree, check=True)
        subprocess.run(["git", "commit", "-qm", "stale base"], cwd=self.worktree, check=True)
        self.retained_head = subprocess.run(
            ["git", "rev-parse", "HEAD"],
            cwd=self.worktree,
            check=True,
            text=True,
            stdout=subprocess.PIPE,
        ).stdout.strip()
        (self.worktree / "tracked.txt").write_text("unstaged\n", encoding="utf-8")
        (self.worktree / "staged.txt").write_text("staged\n", encoding="utf-8")
        subprocess.run(["git", "add", "staged.txt"], cwd=self.worktree, check=True)
        (self.worktree / "untracked.txt").write_text("untracked secret-shaped value\n", encoding="utf-8")

        self.accepted_commit = "2" * 40
        self.session_id = str(uuid.uuid4())
        self.workspace_identity = "remote:ax42-01:work-session:" + self.session_id
        self.runner = root / "runner"
        self.runner.write_text("#!/bin/sh\nexit 0\n", encoding="utf-8")
        self.runner.chmod(0o755)
        self.validation_calls = root / "validation-calls"
        self.validation_mediator = root / "validation-mediator"
        self.validation_mediator.write_text(
            """#!/usr/bin/env python3
import hashlib
import json
import pathlib
import sys
operation, session_id, source_sha, validation_id = sys.argv[1:]
calls = pathlib.Path(__file__).with_name("validation-calls")
calls.write_text(calls.read_text() + "call\\n" if calls.exists() else "call\\n")
definitions = {
    "BACKEND_TEST": "atenea-backend-test-v1",
    "WEB_BUILD": "atenea-web-build-v1",
    "ANDROID_BUILD": "atenea-android-build-v1",
    "PLAYWRIGHT_ACCEPTANCE": "atenea-playwright-acceptance-v1",
}
print(json.dumps({
    "validationId": validation_id,
    "sessionId": session_id,
    "operation": operation,
    "definitionRevision": definitions[operation],
    "sourceTreeFingerprintSha256": source_sha,
    "status": "SUCCEEDED",
    "exitCode": 0,
    "durationMillis": 7,
    "artifactManifestSha256": hashlib.sha256(validation_id.encode()).hexdigest(),
    "summary": "Closed validation passed",
    "valuesExposed": False,
}))
""",
            encoding="utf-8",
        )
        self.validation_mediator.chmod(0o755)
        self.config = root / "project.json"
        self.config.write_text(json.dumps({
            "schemaVersion": MODULE.PROJECT_CAPABILITY,
            "selectionEnabled": True,
            "executionEnabled": False,
            "projectId": MODULE.PROJECT_ID,
            "repository": MODULE.PROJECT_REPOSITORY,
            "branch": MODULE.PROJECT_BRANCH,
            "commit": self.retained_head,
            "manifestSha256": MODULE.PROJECT_MANIFEST_SHA256,
            "runner": str(self.runner),
            "workspaces": {
                self.workspace_identity: {
                    "sessionId": self.session_id,
                    "worktree": str(self.worktree),
                    "allocationSha256": "a" * 64,
                    "canonicalCommit": self.retained_head,
                }
            },
        }), encoding="utf-8")
        self.config.chmod(0o644)
        self.state = MODULE.WorkerState(
            root / "state",
            "ax42-01",
            project_config=self.config,
            project_runner=self.runner,
            project_config_uid=os.getuid(),
            privilege_command=(),
            project_validation_mediator=self.validation_mediator,
        )
        self.state._observe_project_commit = lambda _route: self.accepted_commit

    def tearDown(self):
        self.temporary.cleanup()

    def request(self):
        return {
            "sessionId": self.session_id,
            "workspaceIdentity": self.workspace_identity,
            "projectId": MODULE.PROJECT_ID,
            "repository": MODULE.PROJECT_REPOSITORY,
            "branch": MODULE.PROJECT_BRANCH,
            "acceptedCommit": self.accepted_commit,
            "manifestSha256": MODULE.PROJECT_MANIFEST_SHA256,
        }

    def test_dirty_stale_draft_fingerprint_is_sanitized_repeatable_and_read_only(self):
        before = subprocess.run(
            ["git", "status", "--porcelain=v1"],
            cwd=self.worktree,
            check=True,
            text=True,
            stdout=subprocess.PIPE,
        ).stdout
        first = self.state.fingerprint_retained_draft(self.request())
        second = self.state.fingerprint_retained_draft(self.request())
        after = subprocess.run(
            ["git", "status", "--porcelain=v1"],
            cwd=self.worktree,
            check=True,
            text=True,
            stdout=subprocess.PIPE,
        ).stdout

        self.assertEqual(first, second)
        self.assertEqual(before, after)
        self.assertEqual("draft_blocked_ready", first["state"])
        self.assertEqual(self.retained_head, first["retainedHead"])
        self.assertEqual(1, first["stagedChangeCount"])
        self.assertEqual(1, first["unstagedChangeCount"])
        self.assertEqual(1, first["untrackedChangeCount"])
        self.assertFalse(first["valuesExposed"])
        serialized = json.dumps(first)
        self.assertNotIn("tracked.txt", serialized)
        self.assertNotIn("secret-shaped", serialized)

    def test_foreign_ambiguous_or_active_ownership_fails_closed(self):
        foreign = self.request()
        foreign["workspaceIdentity"] = "remote:ax42-01:work-session:" + str(uuid.uuid4())
        with self.assertRaisesRegex(MODULE.ProtocolError, "not exact"):
            self.state.fingerprint_retained_draft(foreign)

        ambiguous = self.request()
        ambiguous["extra"] = "arbitrary"
        with self.assertRaisesRegex(MODULE.ProtocolError, "fields"):
            self.state.fingerprint_retained_draft(ambiguous)

        self.state.executions["active"] = {
            "sessionId": self.session_id,
            "status": "RUNNING",
        }
        with self.assertRaisesRegex(MODULE.ProtocolError, "non-terminal"):
            self.state.fingerprint_retained_draft(self.request())

    def test_current_source_tree_fingerprint_is_sanitized_and_changes_with_content(self):
        self.state._observe_project_commit = lambda _route: self.retained_head
        request = {
            "sessionId": self.session_id,
            "workspaceIdentity": self.workspace_identity,
            "projectId": MODULE.PROJECT_ID,
            "repository": MODULE.PROJECT_REPOSITORY,
            "branch": MODULE.PROJECT_BRANCH,
            "commit": self.retained_head,
            "manifestSha256": MODULE.PROJECT_MANIFEST_SHA256,
        }

        before = self.state.fingerprint_source_tree(request)
        (self.worktree / "untracked.txt").write_text("changed value\n", encoding="utf-8")
        after = self.state.fingerprint_source_tree(request)

        self.assertNotEqual(before["fingerprintSha256"], after["fingerprintSha256"])
        self.assertEqual(self.retained_head, after["headCommit"])
        self.assertFalse(after["valuesExposed"])
        serialized = json.dumps(after)
        self.assertNotIn("untracked.txt", serialized)
        self.assertNotIn("changed value", serialized)

        foreign = dict(request)
        foreign["repository"] = "https://github.com/foreign/atenea.git"
        with self.assertRaisesRegex(MODULE.ProtocolError, "not exact"):
            self.state.fingerprint_source_tree(foreign)

    def validation_request(self, validation_id=None):
        self.state._observe_project_commit = lambda _route: self.retained_head
        source_request = {
            "sessionId": self.session_id,
            "workspaceIdentity": self.workspace_identity,
            "projectId": MODULE.PROJECT_ID,
            "repository": MODULE.PROJECT_REPOSITORY,
            "branch": MODULE.PROJECT_BRANCH,
            "commit": self.retained_head,
            "manifestSha256": MODULE.PROJECT_MANIFEST_SHA256,
        }
        source = self.state.fingerprint_source_tree(source_request)
        return {
            **source_request,
            "validationId": validation_id or str(uuid.uuid4()),
            "operation": "BACKEND_TEST",
            "definitionRevision": "atenea-backend-test-v1",
            "sourceTreeFingerprintSha256": source["fingerprintSha256"],
        }

    def test_closed_validation_is_sanitized_idempotent_and_durable(self):
        request = self.validation_request()
        first = self.state.run_validation(request)
        second = self.state.run_validation(request)
        recovered = MODULE.WorkerState(
            Path(self.temporary.name) / "state",
            "ax42-01",
            project_config=self.config,
            project_runner=self.runner,
            project_config_uid=os.getuid(),
            privilege_command=(),
            project_validation_mediator=self.validation_mediator,
        )
        recovered._observe_project_commit = lambda _route: self.retained_head
        third = recovered.run_validation(request)

        self.assertEqual(first, second)
        self.assertEqual(first, third)
        self.assertEqual("SUCCEEDED", first["status"])
        self.assertFalse(first["valuesExposed"])
        self.assertEqual(1, self.validation_calls.read_text().count("call"))
        serialized = json.dumps(first)
        self.assertNotIn("command", serialized)
        self.assertNotIn("environment", serialized)
        self.assertNotIn("secret-shaped", serialized)

    def test_closed_validation_rejects_altered_authority_before_process(self):
        for key, value in (
            ("operation", "ARBITRARY_COMMAND"),
            ("definitionRevision", "caller-v1"),
            ("command", "docker run --privileged"),
        ):
            request = self.validation_request()
            request[key] = value
            with self.assertRaises(MODULE.ProtocolError):
                self.state.run_validation(request)
        foreign = self.validation_request()
        foreign["workspaceIdentity"] = "remote:ax42-01:work-session:" + str(uuid.uuid4())
        with self.assertRaises(MODULE.ProtocolError):
            self.state.run_validation(foreign)
        self.assertFalse(self.validation_calls.exists())

    def test_closed_validation_timeout_is_finite_sanitized_and_retained(self):
        request = self.validation_request()
        original_run = subprocess.run

        def bounded_timeout(command, *args, **kwargs):
            if str(self.validation_mediator) in command:
                raise subprocess.TimeoutExpired(command, timeout=900)
            return original_run(command, *args, **kwargs)

        with mock.patch.object(
                MODULE.subprocess,
                "run",
                side_effect=bounded_timeout,
        ):
            result = self.state.run_validation(request)

        self.assertEqual("BLOCKED", result["status"])
        self.assertIsNone(result["exitCode"])
        self.assertRegex(result["artifactManifestSha256"], r"^[0-9a-f]{64}$")
        self.assertFalse(result["valuesExposed"])
        self.assertEqual(result, self.state.run_validation(request))


class ProjectWorkerStateTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        root = Path(self.temporary.name)
        self.runner = root / "fake-project-runner"
        self.runner.write_text(
            """#!/usr/bin/env python3
import json
import sys
import time
request = json.load(sys.stdin)
message = request["workload"]["message"]
if message.startswith("sleep:"):
    time.sleep(float(message.split(":", 1)[1]))
print(json.dumps({
    "threadId": request["workload"]["threadId"] or "f9d68d92-71c6-4fa5-b77b-63863f8f2dc7",
    "turnId": request["executionId"],
    "finalAnswer": "bounded fake result",
    "outputSummary": "project-codex-v1 completed"
}))
""",
            encoding="utf-8",
        )
        self.runner.chmod(0o755)
        self.session_id = str(uuid.uuid4())
        self.workspace_identity = "remote:ax42-01:work-session:" + self.session_id
        self.config = root / "project.json"
        self.config.write_text(
            json.dumps({
                "schemaVersion": "project-codex-v1",
                "selectionEnabled": True,
                "executionEnabled": True,
                "projectId": "atenea",
                "repository": MODULE.PROJECT_REPOSITORY,
                "branch": MODULE.PROJECT_BRANCH,
                "commit": TEST_COMMIT,
                "manifestSha256": MODULE.PROJECT_MANIFEST_SHA256,
                "runner": str(self.runner),
                "workspaces": {
                    self.workspace_identity: {
                        "sessionId": self.session_id,
                        "worktree": "/srv/atenea/workspaces/sessions/" + self.session_id + "/atenea",
                        "allocationSha256": "a" * 64,
                        "canonicalCommit": TEST_COMMIT,
                    }
                },
            }),
            encoding="utf-8",
        )
        self.config.chmod(0o644)
        self.state = MODULE.WorkerState(
            root / "state",
            "test-worker",
            project_config=self.config,
            project_runner=self.runner,
            project_timeout=30,
            project_config_uid=os.getuid(),
            privilege_command=(),
        )
        self.state._observe_project_commit = lambda _route: TEST_COMMIT
        self.state.start()

    def tearDown(self):
        self.state.stop()
        self.temporary.cleanup()

    def request(self, message="hello", thread_id=None):
        return {
            "dispatchId": str(uuid.uuid4()),
            "sessionId": self.session_id,
            "workspaceIdentity": self.workspace_identity,
            "workloadClass": "NORMAL",
            "leaseGeneration": 1,
            "workload": {
                "kind": "project-codex-v1",
                "projectId": "atenea",
                "repository": MODULE.PROJECT_REPOSITORY,
                "branch": MODULE.PROJECT_BRANCH,
                "commit": TEST_COMMIT,
                "manifestSha256": MODULE.PROJECT_MANIFEST_SHA256,
                "message": message,
                "threadId": thread_id,
            },
        }

    def wait_terminal(self, dispatch_id, timeout=5):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            execution = self.state.get(dispatch_id)
            if execution["status"] in MODULE.TERMINAL:
                return execution
            time.sleep(0.02)
        self.fail("execution did not become terminal")

    def test_exact_project_dispatch_is_idempotent_and_preserves_thread(self):
        thread_id = str(uuid.uuid4())
        request = self.request(thread_id=thread_id)
        created, was_created = self.state.create(request)
        duplicate, was_created_again = self.state.create(json.loads(json.dumps(request)))
        self.assertTrue(was_created)
        self.assertFalse(was_created_again)
        self.assertEqual(created["executionId"], duplicate["executionId"])
        terminal = self.wait_terminal(request["dispatchId"])
        self.assertEqual("SUCCEEDED", terminal["status"])
        self.assertEqual(thread_id, terminal["result"]["threadId"])

    def test_disabled_foreign_ambiguous_and_arbitrary_requests_fail_closed(self):
        baseline = self.config.read_bytes()
        for mutate in (
            lambda request: request["workload"].__setitem__("projectId", "beautips"),
            lambda request: request["workload"].__setitem__("command", "id"),
            lambda request: request.__setitem__("workspaceIdentity", "remote:foreign"),
        ):
            request = self.request()
            mutate(request)
            with self.assertRaises(MODULE.ProtocolError):
                self.state.create(request)
            self.assertEqual(baseline, self.config.read_bytes())
        parsed = json.loads(baseline)
        parsed["executionEnabled"] = False
        self.config.write_text(json.dumps(parsed), encoding="utf-8")
        self.assertIn("project-codex-v1", self.state.health()["capabilities"])
        with self.assertRaisesRegex(MODULE.ProtocolError, "disabled"):
            self.state.create(self.request())

    def test_moved_worker_mirror_is_rejected_before_dispatch(self):
        self.state._observe_project_commit = lambda _route: "2" * 40

        with self.assertRaisesRegex(MODULE.ProtocolError, "moved before admission"):
            self.state.create(self.request())

    def test_beautips_route_is_independent_and_accepts_only_its_exact_workspace(self):
        self.state.stop()
        root = Path(self.temporary.name)
        atenea_config = json.loads(self.config.read_text(encoding="utf-8"))
        atenea_config["selectionEnabled"] = False
        atenea_config["executionEnabled"] = False
        self.config.write_text(json.dumps(atenea_config), encoding="utf-8")

        beautips_session = str(uuid.uuid4())
        beautips_workspace = "remote:ax42-01:work-session:" + beautips_session
        beautips_config = root / "beautips-project.json"
        beautips_config.write_text(
            json.dumps({
                "schemaVersion": "project-codex-v1",
                "selectionEnabled": True,
                "executionEnabled": True,
                "projectId": MODULE.BEAUTIPS_PROJECT_ID,
                "repository": MODULE.BEAUTIPS_PROJECT_REPOSITORY,
                "branch": MODULE.BEAUTIPS_PROJECT_BRANCH,
                "commit": MODULE.BEAUTIPS_PROJECT_COMMIT,
                "manifestSha256": MODULE.BEAUTIPS_PROJECT_MANIFEST_SHA256,
                "runner": str(self.runner),
                "workspaces": {
                    beautips_workspace: {
                        "sessionId": beautips_session,
                        "worktree": (
                            "/srv/atenea/workspaces/sessions/"
                            + beautips_session
                            + "/beautips"
                        ),
                        "allocationSha256": "b" * 64,
                    }
                },
            }),
            encoding="utf-8",
        )
        beautips_config.chmod(0o644)
        self.state = MODULE.WorkerState(
            root / "state-beautips",
            "test-worker",
            project_config=self.config,
            project_runner=self.runner,
            project_timeout=30,
            project_config_uid=os.getuid(),
            privilege_command=(),
            beautips_project_config=beautips_config,
            beautips_project_runner=self.runner,
        )
        self.state.start()
        self.assertIn("project-codex-v1", self.state.health()["capabilities"])

        exact = self.request()
        exact["sessionId"] = beautips_session
        exact["workspaceIdentity"] = beautips_workspace
        exact["workload"].update({
            "projectId": MODULE.BEAUTIPS_PROJECT_ID,
            "repository": MODULE.BEAUTIPS_PROJECT_REPOSITORY,
            "branch": MODULE.BEAUTIPS_PROJECT_BRANCH,
            "commit": MODULE.BEAUTIPS_PROJECT_COMMIT,
            "manifestSha256": MODULE.BEAUTIPS_PROJECT_MANIFEST_SHA256,
        })
        accepted, created = self.state.create(exact)
        self.assertTrue(created)
        self.assertEqual("SUCCEEDED", self.wait_terminal(accepted["dispatchId"])["status"])

        state_before = self.state.state_file.read_bytes()
        foreign_workspace = self.request()
        foreign_workspace["sessionId"] = str(uuid.uuid4())
        foreign_workspace["workspaceIdentity"] = (
            "remote:ax42-01:work-session:" + foreign_workspace["sessionId"]
        )
        foreign_workspace["workload"].update(exact["workload"])
        with self.assertRaisesRegex(MODULE.ProtocolError, "workspace identity"):
            self.state.create(foreign_workspace)
        self.assertEqual(state_before, self.state.state_file.read_bytes())

        with self.assertRaisesRegex(MODULE.ProtocolError, "disabled"):
            self.state.create(self.request())

    def test_cancel_terminates_only_exact_project_process(self):
        request = self.request(message="sleep:3")
        other = self.request(message="hello")
        execution, _ = self.state.create(request)
        self.state.create(other)
        deadline = time.monotonic() + 2
        while time.monotonic() < deadline:
            if self.state.get(request["dispatchId"])["status"] == "RUNNING":
                break
            time.sleep(0.02)
        self.state.cancel(request["dispatchId"], {"executionId": execution["executionId"]})
        terminal = self.wait_terminal(request["dispatchId"])
        other_terminal = self.wait_terminal(other["dispatchId"])
        self.assertEqual("CANCELLED", terminal["status"])
        self.assertEqual("SUCCEEDED", other_terminal["status"])

    def test_restart_reconciliation_does_not_duplicate_uncertain_turn(self):
        request = self.request(message="sleep:3")
        created, _ = self.state.create(request)
        deadline = time.monotonic() + 2
        while time.monotonic() < deadline:
            if self.state.get(request["dispatchId"])["status"] == "RUNNING":
                break
            time.sleep(0.02)
        self.state.stop()
        state_file = Path(self.temporary.name) / "state" / "executions.json"
        persisted = json.loads(state_file.read_text(encoding="utf-8"))
        persisted["executions"][request["dispatchId"]]["status"] = "RUNNING"
        persisted["executions"][request["dispatchId"]]["statusReason"] = "simulated uncertain process"
        state_file.write_text(json.dumps(persisted), encoding="utf-8")
        recovered = MODULE.WorkerState(
            Path(self.temporary.name) / "state",
            "test-worker",
            project_config=self.config,
            project_runner=self.runner,
            project_timeout=30,
            project_config_uid=os.getuid(),
            privilege_command=(),
        )
        recovered.start()
        self.state = recovered
        terminal = self.wait_terminal(request["dispatchId"])
        self.assertEqual(created["executionId"], terminal["executionId"])
        self.assertEqual("FAILED", terminal["status"])
        self.assertIn("refused to duplicate", terminal["statusReason"])


class WorkerHttpTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.state = MODULE.WorkerState(Path(self.temporary.name), "http-worker", 4, 2)
        self.server = MODULE.AgentRunServer(("127.0.0.1", 0), self.state, "t" * 64)
        self.state.start()
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.url = "http://127.0.0.1:" + str(self.server.server_port)

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.state.stop()
        self.thread.join(timeout=2)
        self.temporary.cleanup()

    def request(self, path, token=None):
        headers = {}
        if token is not None:
            headers["Authorization"] = "Bearer " + token
        return urllib.request.urlopen(
            urllib.request.Request(self.url + path, headers=headers),
            timeout=2,
        )

    def test_health_requires_authentication_and_exposes_capacity(self):
        with self.assertRaises(urllib.error.HTTPError) as denied:
            self.request("/v1/health")
        self.assertEqual(401, denied.exception.code)
        with self.request("/v1/health", "t" * 64) as accepted:
            health = json.load(accepted)
        self.assertEqual("agent-run-worker/v1", health["protocolVersion"])
        self.assertEqual(4, health["normalCapacity"])
        self.assertEqual(2, health["heavyCapacity"])


if __name__ == "__main__":
    unittest.main()
