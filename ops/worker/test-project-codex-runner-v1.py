#!/usr/bin/env python3

import hashlib
import json
import subprocess
import tempfile
import unittest
import uuid
from importlib.machinery import SourceFileLoader
from pathlib import Path
from unittest.mock import patch

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
TEST_COMMIT = "1" * 40


class ProjectCodexContractTest(unittest.TestCase):
    def workload(self, thread_id=None):
        return {
            "kind": "project-codex-v1",
            "projectId": "atenea",
            "repository": MODULE.REPOSITORY,
            "branch": MODULE.BRANCH,
            "commit": TEST_COMMIT,
            "manifestSha256": MODULE.MANIFEST_SHA256,
            "instructionBundleRevision": MODULE.INSTRUCTION_BUNDLE_REVISION,
            "instructionBundleSha256": MODULE.INSTRUCTION_BUNDLE_SHA256,
            "platformInstructionSha256": MODULE.PLATFORM_INSTRUCTION_SHA256,
            "projectInstructionPath": MODULE.PROJECT_INSTRUCTION_PATH,
            "projectInstructionSha256": MODULE.PROJECT_INSTRUCTION_SHA256,
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

    def test_codex_failure_classification_is_closed_and_sanitized(self):
        cases = (
            ("database is locked at /secret/path", "Codex execution failed: thread persistence unavailable"),
            ("permission denied: token-value", "Codex execution failed: filesystem boundary"),
            ("bwrap: can't find source path token-value", "Codex execution failed: filesystem boundary"),
            ("totally novel token-value", "Codex execution failed: unclassified"),
        )
        for stderr, expected in cases:
            reason = MODULE.codex_failure_reason(stderr)
            self.assertEqual(expected, reason)
            self.assertNotIn("token-value", reason)
            self.assertNotIn("/secret/path", reason)

    def test_internal_failure_classification_retains_only_allowlisted_type(self):
        self.assertEqual(
            "Project runner internal exception: PermissionError",
            MODULE.internal_failure_reason(PermissionError("token-value")),
        )
        self.assertEqual(
            "Project runner internal exception: Other",
            MODULE.internal_failure_reason(RuntimeError("token-value")),
        )

    def test_sandbox_command_has_only_derived_mounts_and_prompt_stays_on_stdin(self):
        session_id = str(uuid.uuid4())
        worktree = Path("/srv/atenea/workspaces/sessions") / session_id / "atenea"
        common = MODULE.GIT_COMMON_DIR
        final = Path("/tmp/atenea-codex-result-test/final.txt")
        resolv = Path("/tmp/atenea-codex-result-test/resolv.conf")
        instruction_mask = Path("/tmp/atenea-codex-result-test/empty-instructions")
        execution_id = str(uuid.uuid4())
        workload = self.workload()
        workload["message"] = "SECRET_PROMPT_MUST_NOT_APPEAR_IN_ARGV"
        command = MODULE.sandbox_command(
            workload, worktree, common, final, resolv, instruction_mask,
            "reviewed instructions", execution_id
        )
        joined = "\n".join(command)
        self.assertNotIn(workload["message"], joined)
        self.assertIn(str(worktree), command)
        self.assertIn(str(common), command)
        self.assertIn("/srv/atenea/repositories", command)
        self.assertIn("/home/jose/.codex", command)
        self.assertIn("developer_instructions=\"reviewed instructions\"", command)
        self.assertEqual(3, command.count(str(instruction_mask)))
        self.assertIn(str(worktree / "AGENTS.md"), command)
        self.assertIn("Group=atenea", command)
        self.assertIn("danger-full-access", command)
        self.assertNotIn("workspace-write", command)
        self.assertIn("GIT_CONFIG_COUNT", command)
        self.assertIn("GIT_CONFIG_KEY_0", command)
        self.assertIn("safe.directory", command)
        git_value_index = command.index("GIT_CONFIG_VALUE_0")
        self.assertEqual(str(worktree), command[git_value_index + 1])
        self.assertNotIn("ProtectKernelTunables=yes", command)
        self.assertNotIn("ProtectKernelLogs=yes", command)
        self.assertIn("ProtectKernelModules=yes", command)
        self.assertIn("ProtectControlGroups=yes", command)
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
            value for value in command
            if value not in {
                str(worktree),
                str(worktree.parent),
                str(worktree / "AGENTS.md"),
            }
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
            Path("/tmp/atenea-codex-result-test/empty-instructions"),
            "reviewed instructions",
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

    def test_dynamic_commit_must_match_root_owned_configuration(self):
        config = {"commit": TEST_COMMIT, "workspaces": {}}
        request = {
            "dispatchId": str(uuid.uuid4()),
            "executionId": str(uuid.uuid4()),
            "sessionId": str(uuid.uuid4()),
            "workspaceIdentity": "remote:ax42-01:work-session:" + str(uuid.uuid4()),
            "workload": self.workload(),
        }
        request["workload"]["commit"] = "2" * 40

        with self.assertRaises(SystemExit):
            MODULE.validate_request(request, config)

    def test_reviewed_instruction_bundle_is_exact_and_ambient_sources_fail_closed(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            worktree = root / "worktree"
            platform = root / "platform.md"
            worktree.mkdir()
            platform.write_text("platform contract\n", encoding="utf-8")
            platform.chmod(0o644)
            agents = worktree / "AGENTS.md"
            agents.write_text("repository contract\n", encoding="utf-8")
            subprocess.run(["git", "init", "-q"], cwd=worktree, check=True)
            subprocess.run(["git", "config", "user.name", "Contract test"], cwd=worktree, check=True)
            subprocess.run(
                ["git", "config", "user.email", "contract@atenea.invalid"],
                cwd=worktree,
                check=True,
            )
            subprocess.run(["git", "add", "AGENTS.md"], cwd=worktree, check=True)
            subprocess.run(["git", "commit", "-q", "-m", "instructions"], cwd=worktree, check=True)
            old = (
                MODULE.PLATFORM_INSTRUCTION_PATH,
                MODULE.PLATFORM_INSTRUCTION_UID,
                MODULE.PLATFORM_INSTRUCTION_SHA256,
                MODULE.PROJECT_INSTRUCTION_SHA256,
                MODULE.INSTRUCTION_BUNDLE_SHA256,
            )
            platform_bytes = platform.read_bytes()
            project_bytes = agents.read_bytes()
            MODULE.PLATFORM_INSTRUCTION_PATH = platform
            MODULE.PLATFORM_INSTRUCTION_UID = platform.stat().st_uid
            MODULE.PLATFORM_INSTRUCTION_SHA256 = hashlib.sha256(platform_bytes).hexdigest()
            MODULE.PROJECT_INSTRUCTION_SHA256 = hashlib.sha256(project_bytes).hexdigest()
            MODULE.INSTRUCTION_BUNDLE_SHA256 = hashlib.sha256(
                MODULE.INSTRUCTION_BUNDLE_REVISION.encode("ascii")
                + b"\0" + platform_bytes + b"\0" + project_bytes
            ).hexdigest()
            try:
                bundle = MODULE.validate_instruction_bundle(worktree)
                self.assertIn("platform contract", bundle)
                self.assertIn("repository contract", bundle)

                (worktree / "AGENTS.override.md").write_text("ambient\n", encoding="utf-8")
                with self.assertRaises(SystemExit):
                    MODULE.validate_instruction_bundle(worktree)
                (worktree / "AGENTS.override.md").unlink()

                (worktree / ".codex").mkdir()
                with self.assertRaises(SystemExit):
                    MODULE.validate_instruction_bundle(worktree)
                (worktree / ".codex").rmdir()

                agents.write_text("changed contract\n", encoding="utf-8")
                with self.assertRaises(SystemExit):
                    MODULE.validate_instruction_bundle(worktree)
            finally:
                (
                    MODULE.PLATFORM_INSTRUCTION_PATH,
                    MODULE.PLATFORM_INSTRUCTION_UID,
                    MODULE.PLATFORM_INSTRUCTION_SHA256,
                    MODULE.PROJECT_INSTRUCTION_SHA256,
                    MODULE.INSTRUCTION_BUNDLE_SHA256,
                ) = old

    def test_exact_head_cleanliness_and_mirror_move_fail_closed(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            worktree = root / "atenea"
            common = root / "atenea.git"
            worktree.mkdir()
            common.mkdir()
            manifest = worktree / "ops" / "atenea-runtime.json"
            manifest.parent.mkdir()
            manifest.write_bytes(b"manifest")
            allocation = worktree.parent / "runtime-allocation-v1.json"
            allocation.write_bytes(b"allocation")
            old_common = MODULE.GIT_COMMON_DIR
            old_manifest = MODULE.MANIFEST_SHA256
            MODULE.GIT_COMMON_DIR = common
            MODULE.MANIFEST_SHA256 = hashlib.sha256(manifest.read_bytes()).hexdigest()
            record = {
                "sessionId": str(uuid.uuid4()),
                "worktree": str(worktree),
                "allocationSha256": hashlib.sha256(allocation.read_bytes()).hexdigest(),
                "canonicalCommit": TEST_COMMIT,
            }

            def observed(command, _cwd):
                joined = " ".join(command)
                if "--show-toplevel" in joined:
                    return str(worktree)
                if "remote get-url" in joined:
                    return MODULE.REPOSITORY
                if "--git-common-dir" in joined:
                    return str(common)
                if "refs/remotes/origin/" in joined:
                    return TEST_COMMIT
                if "HEAD^{commit}" in joined:
                    return TEST_COMMIT
                if "status --porcelain" in joined:
                    return ""
                raise AssertionError(joined)

            try:
                with patch.object(MODULE, "checked", side_effect=observed):
                    self.assertEqual(common, MODULE.validate_worktree(worktree, record))

                for changed_fragment, changed_value in (
                    ("status --porcelain", "?? draft.txt"),
                    ("HEAD^{commit}", "2" * 40),
                    ("refs/remotes/origin/", "2" * 40),
                ):
                    def changed(command, cwd, fragment=changed_fragment, value=changed_value):
                        joined = " ".join(command)
                        if fragment in joined:
                            return value
                        return observed(command, cwd)

                    with patch.object(MODULE, "checked", side_effect=changed):
                        with self.assertRaises(SystemExit):
                            MODULE.validate_worktree(worktree, record)
            finally:
                MODULE.GIT_COMMON_DIR = old_common
                MODULE.MANIFEST_SHA256 = old_manifest


if __name__ == "__main__":
    unittest.main()
