#!/usr/bin/env python3

from __future__ import annotations

import importlib.util
import json
import os
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest import mock

SCRIPT = Path(__file__).with_name("beautips-project-codex-runner-v1.py")
SPEC = importlib.util.spec_from_file_location("beautips_project_codex_v1", SCRIPT)
assert SPEC and SPEC.loader
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)
BASE = MODULE.BASE

SESSION = "018f47a2-6b0c-7a31-9c2d-4f5a6b7c8db1"
EXECUTION = "028f47a2-6b0c-7a31-9c2d-4f5a6b7c8db2"
WORKSPACE_IDENTITY = f"remote:ax42-01:work-session:{SESSION}"
SOURCE = Path(
    os.environ.get("ATENEA_BEAUTIPS_SOURCE", "/home/jose/IdeaProjects/beautips")
)


class BeautipsProjectCodexRunnerTest(unittest.TestCase):
    def config(self, worktree: Path, allocation_sha: str = "a" * 64) -> dict:
        return {
            "schemaVersion": "project-codex-v1",
            "selectionEnabled": True,
            "executionEnabled": True,
            "projectId": "beautips",
            "repository": "https://github.com/jlnieto/beautips.git",
            "branch": "main",
            "commit": "e9e0b3c319c518363d4135f5378ebbddced96dfb",
            "manifestSha256": (
                "365f1c66c51c9018c2c6f48deddbaa619b4588cae2dd463dcd916cde884e2e82"
            ),
            "runner": str(SCRIPT.resolve()),
            "workspaces": {
                WORKSPACE_IDENTITY: {
                    "sessionId": SESSION,
                    "worktree": str(worktree),
                    "allocationSha256": allocation_sha,
                }
            },
        }

    def request(self) -> dict:
        return {
            "dispatchId": "038f47a2-6b0c-7a31-9c2d-4f5a6b7c8db3",
            "executionId": EXECUTION,
            "sessionId": SESSION,
            "workspaceIdentity": WORKSPACE_IDENTITY,
            "workload": {
                "kind": "project-codex-v1",
                "projectId": "beautips",
                "repository": "https://github.com/jlnieto/beautips.git",
                "branch": "main",
                "commit": "e9e0b3c319c518363d4135f5378ebbddced96dfb",
                "manifestSha256": (
                    "365f1c66c51c9018c2c6f48deddbaa619b4588cae2dd463dcd916cde884e2e82"
                ),
                "instructionBundleRevision": BASE.INSTRUCTION_BUNDLE_REVISION,
                "instructionBundleSha256": BASE.INSTRUCTION_BUNDLE_SHA256,
                "platformInstructionSha256": BASE.PLATFORM_INSTRUCTION_SHA256,
                "projectInstructionPath": BASE.PROJECT_INSTRUCTION_PATH,
                "projectInstructionSha256": BASE.PROJECT_INSTRUCTION_SHA256,
                "message": "Add one deterministic acceptance note.",
                "threadId": None,
            },
        }

    def test_adapter_changes_only_exact_project_identity(self) -> None:
        self.assertEqual("beautips", BASE.PROJECT_ID)
        self.assertEqual("https://github.com/jlnieto/beautips.git", BASE.REPOSITORY)
        self.assertEqual("main", BASE.BRANCH)
        self.assertEqual(
            "e9e0b3c319c518363d4135f5378ebbddced96dfb", BASE.BASE_COMMIT
        )
        self.assertEqual(
            Path("/srv/atenea/repositories/beautips.git"), BASE.GIT_COMMON_DIR
        )
        self.assertEqual(
            "0e06aa861b11e324610f3a7cd7aef1bff3c2712d7b838a052bb5748542c8e1c7",
            BASE.PROJECT_INSTRUCTION_SHA256,
        )
        self.assertEqual(
            "6e5affe84ca7e300c1c3f0907056013820999699d84fd0e491add924ad685b60",
            BASE.INSTRUCTION_BUNDLE_SHA256,
        )
        self.assertEqual(str(SCRIPT.resolve()), BASE.__file__)

    def test_config_and_request_are_beautips_exact(self) -> None:
        worktree = Path("/srv/atenea/workspaces/sessions") / SESSION / "beautips"
        config = self.config(worktree)
        BASE.validate_config(config, SCRIPT.resolve())
        with mock.patch.object(Path, "is_dir", return_value=True), mock.patch.object(
            Path, "is_symlink", return_value=False
        ):
            workload, accepted = BASE.validate_request(self.request(), config)
        self.assertEqual(worktree, accepted)
        self.assertEqual("beautips", workload["projectId"])
        image_request = self.request()
        image_request["workload"].update({
            "kind": BASE.IMAGE_CAPABILITY,
            "modelId": BASE.CODEX_MODEL,
            "reasoningEffort": "high",
            "catalogRevision": BASE.CODEX_CATALOG_REVISION,
            "codexVersion": BASE.CODEX_VERSION,
            "attachments": [{
                "attachmentId": "11111111-1111-4111-8111-111111111111",
                "contentType": "image/png",
                "sizeBytes": 8,
                "sha256": "a" * 64,
            }],
        })
        with mock.patch.object(Path, "is_dir", return_value=True), mock.patch.object(
            Path, "is_symlink", return_value=False
        ), self.assertRaises(SystemExit):
            BASE.validate_request(image_request, config)
        for key, foreign in (
            ("projectId", "atenea"),
            ("repository", "https://github.com/jlnieto/atenea.git"),
            ("branch", "foreign"),
            ("commit", "0" * 40),
            ("manifestSha256", "1" * 64),
        ):
            candidate = self.request()
            candidate["workload"][key] = foreign
            with self.assertRaises(SystemExit):
                BASE.validate_request(candidate, config)

    def test_real_git_manifest_and_allocation_fingerprint(self) -> None:
        with tempfile.TemporaryDirectory(
            prefix="beautips-codex-runner.", dir="/tmp"
        ) as temporary:
            root = Path(temporary)
            worktree = root / "workspaces" / "sessions" / SESSION / "beautips"
            worktree.parent.mkdir(parents=True)
            subprocess.run(
                ["git", "clone", "--quiet", "--no-local", str(SOURCE), str(worktree)],
                check=True,
                timeout=30,
            )
            subprocess.run(
                [
                    "git", "-C", str(worktree), "remote", "set-url", "origin",
                    "https://github.com/jlnieto/beautips.git",
                ],
                check=True,
                timeout=10,
            )
            allocation = worktree.parent / "runtime-allocation-v1.json"
            allocation.write_text('{"owned":true}\\n', encoding="utf-8")
            import hashlib

            allocation_sha = hashlib.sha256(allocation.read_bytes()).hexdigest()
            config = self.config(worktree, allocation_sha)
            common = Path(
                subprocess.run(
                    ["git", "-C", str(worktree), "rev-parse", "--git-common-dir"],
                    text=True,
                    stdout=subprocess.PIPE,
                    check=True,
                    timeout=10,
                ).stdout.strip()
            )
            if not common.is_absolute():
                common = (worktree / common).resolve()
            original_checked = BASE.checked

            def checked(command: list[str], cwd: Path) -> str:
                if command == ["git", "rev-parse", "--git-common-dir"]:
                    return str(common)
                return original_checked(command, cwd)

            with mock.patch.object(BASE, "GIT_COMMON_DIR", common), mock.patch.object(
                BASE, "checked", side_effect=checked
            ):
                accepted = BASE.validate_worktree(
                    worktree, config["workspaces"][WORKSPACE_IDENTITY]
                )
            self.assertEqual(common, accepted)

    def test_sandbox_retains_accepted_bounds_and_exact_mounts(self) -> None:
        worktree = Path("/srv/atenea/workspaces/sessions") / SESSION / "beautips"
        common = Path("/srv/atenea/repositories/beautips.git")
        result_root = worktree.parent / ".result"
        command = BASE.sandbox_command(
            self.request()["workload"],
            worktree,
            common,
            result_root / "final.txt",
            result_root / "resolv.conf",
            result_root / "empty-instructions",
            "reviewed Beautips instructions",
            EXECUTION,
        )
        joined = "\\n".join(command)
        self.assertIn("/usr/bin/systemd-run", command)
        self.assertIn("/usr/bin/bwrap", command)
        self.assertIn("NoNewPrivileges=yes", command)
        self.assertIn("IPAddressDeny=100.64.0.0/10", command)
        self.assertIn(str(worktree), command)
        self.assertIn(str(common), command)
        self.assertNotIn("/srv/atenea/workspaces/manual/beautips", joined)
        self.assertNotIn("/run/user/1101/docker.sock", joined)
        self.assertNotIn("auth.json", joined)
        self.assertEqual("-", command[-1])


if __name__ == "__main__":
    unittest.main()
