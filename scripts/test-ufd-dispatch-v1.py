#!/usr/bin/env python3
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

# Controller regression imports must not dirty the immutable UFD checkout.
sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location("controller", Path(__file__).with_name("ufd-dispatch-v1.py"))
controller = importlib.util.module_from_spec(spec)
spec.loader.exec_module(controller)
HEAD = "1" * 40
AUTHORITY = "2" * 40
BRANCH = "atenea/change-59315b6e-59bc-4884-9def-356e1ca86ef4"


def payload(head=HEAD, authority=AUTHORITY, branch=BRANCH):
    return {"headSha": head, "headBranch": branch, "authoritySha": authority,
            "requestId": controller.request_id(head, branch)}


class ControllerTest(unittest.TestCase):
    def check(self, request, **changes):
        context = {"repository": controller.REPOSITORY, "ref": "refs/heads/main", "authority": AUTHORITY}
        context.update(changes)
        return controller.closed_request({"action": controller.EVENT, "client_payload": request}, **context)

    def test_exact_closed_request_and_stable_identity(self):
        request = payload()
        self.assertEqual(request, self.check(request))
        # Java UUID.nameUUIDFromBytes compatibility, not Python namespace UUID3.
        self.assertEqual("7ff32e5c-9a66-3808-99e6-492123439f9d", controller.request_id(HEAD, BRANCH))

    def test_rejects_paths_commands_versions_other_branches_and_bad_sha(self):
        for field in ("path", "command", "version", "repository"):
            with self.subTest(field=field), self.assertRaisesRegex(RuntimeError, "closed-request-required"):
                self.check({**payload(), field: "arbitrary"})
        for branch in ("main", "foreign/change-test", BRANCH + ";echo bad", BRANCH + "\n", "atenea/change-not-a-uuid"):
            with self.subTest(branch=branch), self.assertRaises(RuntimeError):
                self.check(payload(branch=branch))
        for head in ("main", "1" * 39, "1" * 40 + "\n", "$(echo bad)"):
            with self.subTest(head=head), self.assertRaises(RuntimeError):
                self.check(payload(head=head))

    def test_rejects_forged_request_foreign_controller_or_moved_main(self):
        with self.assertRaisesRegex(RuntimeError, "request-identity-invalid"):
            self.check({**payload(), "requestId": "00000000-0000-4000-8000-000000000001"})
        for changes in ({"repository": "foreign/atenea"}, {"ref": "refs/heads/feature"}, {"authority": "3" * 40}):
            with self.subTest(changes=changes), self.assertRaises(RuntimeError):
                self.check(payload(), **changes)

    def test_remote_observation_binds_branch_and_commit_and_fixed_repo(self):
        response = {"ref": "refs/heads/" + BRANCH, "object": {"type": "commit", "sha": HEAD}}
        with patch.object(controller.subprocess, "check_output", return_value=json.dumps(response).encode()) as call:
            controller.remote_head(payload())
            self.assertEqual(["gh", "api", "repos/jlnieto/atenea/git/ref/heads/" + BRANCH], call.call_args.args[0])
        response["object"]["sha"] = "3" * 40
        with patch.object(controller.subprocess, "check_output", return_value=json.dumps(response).encode()):
            with self.assertRaisesRegex(RuntimeError, "published-head-moved"):
                controller.remote_head(payload())

    def test_historical_head_keeps_exact_harness_policy_and_engine(self):
        with tempfile.TemporaryDirectory(prefix="atenea-ufd-controller-test-") as name:
            root = Path(name)
            def git(*args):
                return subprocess.check_output(["git", "-C", str(root), *args], stderr=subprocess.DEVNULL).decode().strip()
            git("init", "-b", "main")
            git("config", "user.email", "test@atenea.test")
            git("config", "user.name", "Ephemeral test")
            for path in (*controller.HARNESS, ".delivery/policy.json"):
                file = root / path
                file.parent.mkdir(parents=True, exist_ok=True)
                file.write_text("committed original harness\n")
            git("add", "."); git("commit", "-m", "base with engine but no workflow")
            git("checkout", "-b", BRANCH)
            (root / "feature.txt").write_text("ticket functional code\n")
            git("add", "."); git("commit", "-m", "ticket")
            head = git("rev-parse", "HEAD")
            git("checkout", "main")
            (root / "main.txt").write_text("new main controller\n")
            git("add", "."); git("commit", "-m", "main advances independently")
            authority = git("rev-parse", "HEAD")
            git("checkout", BRANCH)
            expected = git("merge-base", "HEAD", authority)
            self.assertEqual(expected, controller.verify_source(root, payload(head, authority)))
            (root / "scripts/validate-change").write_text("weakened harness\n")
            with self.assertRaisesRegex(RuntimeError, "checkout-not-clean"):
                controller.verify_source(root, payload(head, authority))
            git("add", "."); git("commit", "-m", "tamper")
            with self.assertRaisesRegex(RuntimeError, "legacy-harness-changed"):
                controller.verify_source(root, payload(git("rev-parse", "HEAD"), authority))


if __name__ == "__main__":
    unittest.main()
