#!/usr/bin/env python3

import json
import tempfile
import threading
import time
import unittest
import uuid
import os
import urllib.error
import urllib.request
from pathlib import Path

from importlib.machinery import SourceFileLoader

MODULE = SourceFileLoader(
    "agent_run_worker_v1",
    str(Path(__file__).with_name("agent-run-worker-v1.py")),
).load_module()


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
                "commit": MODULE.PROJECT_COMMIT,
                "manifestSha256": MODULE.PROJECT_MANIFEST_SHA256,
                "runner": str(self.runner),
                "workspaces": {
                    self.workspace_identity: {
                        "sessionId": self.session_id,
                        "worktree": "/srv/atenea/workspaces/sessions/" + self.session_id + "/atenea",
                        "allocationSha256": "a" * 64,
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
                "commit": MODULE.PROJECT_COMMIT,
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
