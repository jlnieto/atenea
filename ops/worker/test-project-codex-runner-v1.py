#!/usr/bin/env python3

import json
import unittest
import uuid
from importlib.machinery import SourceFileLoader
from pathlib import Path

try:
    from jsonschema import Draft202012Validator, FormatChecker
except ModuleNotFoundError:
    Draft202012Validator = None
    FormatChecker = None

ROOT = Path(__file__).resolve().parents[2]
MODULE = SourceFileLoader(
    "project_codex_runner_v1",
    str(Path(__file__).with_name("project-codex-runner-v1.py")),
).load_module()


class ProjectCodexContractTest(unittest.TestCase):
    def workload(self, thread_id=None):
        return {
            "kind": "project-codex-v1",
            "projectId": "atenea",
            "repository": MODULE.REPOSITORY,
            "branch": MODULE.BRANCH,
            "commit": MODULE.BASE_COMMIT,
            "manifestSha256": MODULE.MANIFEST_SHA256,
            "message": "Update only the accepted documentation fixture.",
            "threadId": thread_id,
        }

    def test_request_and_result_schemas_accept_exact_envelopes(self):
        if Draft202012Validator is None:
            self.skipTest("jsonschema is not installed on this worker")
        request_schema = json.loads(
            (ROOT / "runtime-contract/agent-run-project-codex-v1.request.schema.json").read_text()
        )
        result_schema = json.loads(
            (ROOT / "runtime-contract/agent-run-project-codex-v1.result.schema.json").read_text()
        )
        request = {
            "dispatchId": str(uuid.uuid4()),
            "sessionId": str(uuid.uuid4()),
            "workspaceIdentity": "remote:ax42-01:work-session:" + str(uuid.uuid4()),
            "workloadClass": "NORMAL",
            "leaseGeneration": 1,
            "workload": self.workload(),
        }
        result = {
            "threadId": str(uuid.uuid4()),
            "turnId": str(uuid.uuid4()),
            "finalAnswer": "Done.",
            "outputSummary": "project-codex-v1 completed",
        }
        Draft202012Validator(request_schema, format_checker=FormatChecker()).validate(request)
        Draft202012Validator(result_schema, format_checker=FormatChecker()).validate(result)

    def test_sandbox_command_has_only_derived_mounts_and_prompt_stays_on_stdin(self):
        session_id = str(uuid.uuid4())
        worktree = Path("/srv/atenea/workspaces/sessions") / session_id / "atenea"
        common = MODULE.GIT_COMMON_DIR
        final = Path("/tmp/atenea-codex-result-test/final.txt")
        resolv = Path("/tmp/atenea-codex-result-test/resolv.conf")
        execution_id = str(uuid.uuid4())
        workload = self.workload()
        workload["message"] = "SECRET_PROMPT_MUST_NOT_APPEAR_IN_ARGV"
        command = MODULE.sandbox_command(
            workload, worktree, common, final, resolv, execution_id
        )
        joined = "\n".join(command)
        self.assertNotIn(workload["message"], joined)
        self.assertIn(str(worktree), command)
        self.assertIn(str(common), command)
        self.assertIn("/home/jose/.codex", command)
        self.assertIn("GIT_CONFIG_COUNT", command)
        self.assertIn("GIT_CONFIG_KEY_0", command)
        self.assertIn("safe.directory", command)
        git_value_index = command.index("GIT_CONFIG_VALUE_0")
        self.assertEqual(str(worktree), command[git_value_index + 1])
        self.assertNotIn("/var/run/docker.sock", joined)
        for denied in (
            "IPAddressDeny=127.0.0.0/8",
            "IPAddressDeny=10.0.0.0/8",
            "IPAddressDeny=100.64.0.0/10",
            "IPAddressDeny=172.16.0.0/12",
            "IPAddressDeny=192.168.0.0/16",
            "IPAddressDeny=fc00::/7",
        ):
            self.assertIn(denied, command)
        self.assertNotIn("/srv/atenea/workspaces/sessions/", "\n".join(
            value for value in command if value not in {str(worktree), str(worktree.parent)}
        ))
        self.assertEqual("-", command[-1])

    def test_resume_uses_only_exact_uuid_and_stdin(self):
        thread_id = str(uuid.uuid4())
        command = MODULE.sandbox_command(
            self.workload(thread_id),
            Path("/srv/atenea/workspaces/sessions/11111111-1111-4111-8111-111111111111/atenea"),
            MODULE.GIT_COMMON_DIR,
            Path("/tmp/atenea-codex-result-test/final.txt"),
            Path("/tmp/atenea-codex-result-test/resolv.conf"),
            str(uuid.uuid4()),
        )
        self.assertEqual(["resume", thread_id, "-"], command[-3:])

    def test_schemas_reject_commands_paths_endpoints_and_environment(self):
        if Draft202012Validator is None:
            self.skipTest("jsonschema is not installed on this worker")
        schema = json.loads(
            (ROOT / "runtime-contract/agent-run-project-codex-v1.request.schema.json").read_text()
        )
        validator = Draft202012Validator(schema, format_checker=FormatChecker())
        request = {
            "dispatchId": str(uuid.uuid4()),
            "sessionId": str(uuid.uuid4()),
            "workspaceIdentity": "remote:ax42-01:work-session:" + str(uuid.uuid4()),
            "workloadClass": "NORMAL",
            "leaseGeneration": 1,
            "workload": self.workload(),
        }
        for field, value in (
            ("command", "id"),
            ("path", "/tmp/foreign"),
            ("endpoint", "http://127.0.0.1:1"),
            ("environment", {"TOKEN": "forbidden"}),
        ):
            candidate = json.loads(json.dumps(request))
            candidate["workload"][field] = value
            self.assertTrue(list(validator.iter_errors(candidate)), field)


if __name__ == "__main__":
    unittest.main()
