#!/usr/bin/env python3

from __future__ import annotations

import json
import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path


PROGRAM = Path(__file__).with_name("external-backup-v1.py")


class ExternalBackupContractTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory(prefix="atenea-backup-test.")
        self.root = Path(self.temporary.name)
        self.state = self.root / "state"
        self.staging = self.root / "staging"
        self.lock = self.root / "backup.lock"
        for path in (
            self.root / "srv/atenea/worker/state-v1",
            self.root / "srv/atenea/attachments-v1/session-a",
            self.root / "srv/atenea/artifacts/evidence",
            self.root / "etc/atenea-worker/gates",
        ):
            path.mkdir(parents=True)
        (self.root / "srv/atenea/worker/state-v1/record.json").write_text(
            '{"state":"retained"}\n', encoding="utf-8"
        )
        (self.root / "srv/atenea/attachments-v1/session-a/image.png").write_bytes(
            b"synthetic-image"
        )
        (self.root / "srv/atenea/artifacts/evidence/SHA256SUMS").write_text(
            "synthetic\n", encoding="utf-8"
        )
        (self.root / "etc/atenea-worker/project.json").write_text(
            '{"selectionEnabled":false}\n', encoding="utf-8"
        )
        (self.root / "etc/atenea-worker/feature.enabled").write_text(
            "disabled\n", encoding="utf-8"
        )
        (self.root / "etc/atenea-worker/gates/backup.pending").write_text(
            "pending\n", encoding="utf-8"
        )
        self.environment = os.environ.copy()
        self.environment["ATENEA_BACKUP_TEST_MODE"] = "1"

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def run_program(self, operation: str, *extra: str) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [
                os.fspath(PROGRAM),
                operation,
                "--root-prefix",
                os.fspath(self.root),
                "--state-root",
                os.fspath(self.state),
                "--staging-root",
                os.fspath(self.staging),
                "--lock",
                os.fspath(self.lock),
                *extra,
            ],
            text=True,
            capture_output=True,
            env=self.environment,
            timeout=20,
        )

    def test_manifest_is_deterministic_and_contains_only_approved_files(self) -> None:
        first = self.run_program("manifest")
        second = self.run_program("manifest")
        self.assertEqual(first.returncode, 0, first.stderr)
        self.assertEqual(second.returncode, 0, second.stderr)
        first_value = json.loads(first.stdout)
        second_value = json.loads(second.stdout)
        self.assertEqual(first_value, second_value)
        paths = {entry["path"] for entry in first_value["files"]}
        self.assertIn("/srv/atenea/worker/state-v1/record.json", paths)
        self.assertIn("/srv/atenea/attachments-v1/session-a/image.png", paths)
        self.assertIn("/etc/atenea-worker/project.json", paths)
        self.assertEqual(first_value["policyTag"], "atenea-authoritative-v1")

    def test_prohibited_boundaries_are_not_selected(self) -> None:
        excluded = (
            self.root / "srv/atenea/worker/context-v1/.codex",
            self.root / "srv/atenea/worker/toolchain-v1/bin",
            self.root / "srv/atenea/worker/workspace-v1/repository/.git",
            self.root / "srv/atenea/worker/workspace-locks",
            self.root / "srv/atenea/worker/caches/dependency",
            self.root / "srv/atenea/repositories/beautips.git",
            self.root / "srv/atenea/workspaces/sessions/session/runtime",
            self.root / "home/jose/.codex",
            self.root / "root/.codex",
            self.root / "etc/atenea-worker/manual-sessions",
        )
        for path in excluded:
            path.mkdir(parents=True, exist_ok=True)
        (excluded[0] / "SKILL.md").write_text("versioned\n", encoding="utf-8")
        (excluded[1] / "java").write_text("versioned\n", encoding="utf-8")
        (excluded[2] / "config").write_text("git\n", encoding="utf-8")
        (excluded[3] / "runtime.lock").write_text("lock\n", encoding="utf-8")
        (excluded[4] / "cached.jar").write_text("cache\n", encoding="utf-8")
        (excluded[5] / "HEAD").write_text("ref: main\n", encoding="utf-8")
        (excluded[6] / "runtime.json").write_text("{}\n", encoding="utf-8")
        (excluded[7] / "auth.json").write_text("not-auth\n", encoding="utf-8")
        (excluded[8] / "history.jsonl").write_text("not-history\n", encoding="utf-8")
        (excluded[9] / "beautips.env").write_text("not-a-secret\n", encoding="utf-8")
        (self.root / "etc/atenea-worker/service.token").write_text(
            "not-a-token\n", encoding="utf-8"
        )
        result = self.run_program("manifest")
        self.assertEqual(result.returncode, 0, result.stderr)
        joined = json.dumps(json.loads(result.stdout))
        for value in (
            "workspace-v1",
            "workspace-locks",
            "context-v1",
            "toolchain-v1",
            "cached.jar",
            "repositories",
            "workspaces",
            ".codex",
            "auth.json",
            "history.jsonl",
            "manual-sessions",
            ".token",
        ):
            self.assertNotIn(value, joined)

    def test_prohibited_file_below_authoritative_root_is_not_selected(self) -> None:
        (self.root / "srv/atenea/artifacts/evidence/session.env").write_text(
            "not-a-secret\n", encoding="utf-8"
        )
        result = self.run_program("manifest")
        self.assertEqual(result.returncode, 0, result.stderr)
        joined = json.dumps(json.loads(result.stdout))
        self.assertNotIn("session.env", joined)
        self.assertNotIn("not-a-secret", joined)

    def test_symlink_and_special_file_fail_closed(self) -> None:
        link = self.root / "srv/atenea/artifacts/evidence/foreign-link"
        link.symlink_to("/etc/passwd")
        result = self.run_program("manifest")
        self.assertEqual(result.returncode, 65)
        self.assertIn("symbolic link rejected", result.stderr)
        link.unlink()
        fifo = self.root / "srv/atenea/artifacts/evidence/foreign-fifo"
        os.mkfifo(fifo)
        result = self.run_program("manifest")
        self.assertEqual(result.returncode, 65)
        self.assertIn("special file rejected", result.stderr)

    def test_missing_repository_inputs_fail_before_state_mutation(self) -> None:
        result = self.run_program("backup")
        self.assertEqual(result.returncode, 65)
        self.assertIn("repository inputs are not installed", result.stderr)
        self.assertFalse((self.state / "last-backup.json").exists())

    def test_nonempty_and_outside_restore_targets_are_rejected(self) -> None:
        password = self.root / "password"
        password.write_text("synthetic-password\n", encoding="utf-8")
        password.chmod(0o600)
        self.environment["RESTIC_PASSWORD_FILE"] = os.fspath(password)
        self.environment["RESTIC_REPOSITORY"] = os.fspath(self.root / "repository")
        manifest = {
            "schemaVersion": 1,
            "workerId": "ax42-01",
            "policyTag": "atenea-authoritative-v1",
            "manifestSha256": "0" * 64,
            "files": [],
        }
        self.state.mkdir()
        (self.state / "latest-source-manifest.json").write_text(
            json.dumps(manifest), encoding="utf-8"
        )
        outside = self.root / "outside"
        result = self.run_program("restore", "--snapshot", "a" * 64, "--target", str(outside))
        self.assertEqual(result.returncode, 65)
        self.assertIn("beneath restore-tests", result.stderr)
        nonempty = self.staging / "restore-tests/nonempty"
        nonempty.mkdir(parents=True)
        (nonempty / "existing").write_text("preserve\n", encoding="utf-8")
        result = self.run_program(
            "restore", "--snapshot", "a" * 64, "--target", str(nonempty)
        )
        self.assertEqual(result.returncode, 65)
        self.assertTrue((nonempty / "existing").exists())

    @unittest.skipUnless(
        shutil.which("restic") and os.environ.get("RESTIC_REAL_INTEGRATION") == "1",
        "real restic integration is explicitly enabled",
    )
    def test_real_encrypted_repository_backup_check_retention_and_restore(self) -> None:
        password = self.root / "repository-password"
        password.write_text("deterministic-synthetic-password\n", encoding="utf-8")
        password.chmod(0o600)
        repository = self.root / "repository"
        self.environment["RESTIC_PASSWORD_FILE"] = os.fspath(password)
        self.environment["RESTIC_REPOSITORY"] = os.fspath(repository)

        initialized = self.run_program("init")
        self.assertEqual(initialized.returncode, 0, initialized.stderr)
        first_backup = self.run_program("backup")
        self.assertEqual(first_backup.returncode, 0, first_backup.stderr)
        backup_record = json.loads(first_backup.stdout)
        self.assertRegex(backup_record["snapshotId"], r"^[0-9a-f]{64}$")

        checked = self.run_program("check")
        self.assertEqual(checked.returncode, 0, checked.stderr)
        self.assertEqual(json.loads(checked.stdout)["snapshotId"], backup_record["snapshotId"])

        check_path = self.state / "last-check.json"
        original_check = check_path.read_text(encoding="utf-8")
        ambiguous = json.loads(original_check)
        ambiguous["snapshotId"] = "b" * 64
        check_path.write_text(json.dumps(ambiguous), encoding="utf-8")
        rejected_retention = self.run_program("retain")
        self.assertEqual(rejected_retention.returncode, 65)
        self.assertIn("ambiguous", rejected_retention.stderr)
        check_path.write_text(original_check, encoding="utf-8")

        second_backup = self.run_program("backup")
        self.assertEqual(second_backup.returncode, 0, second_backup.stderr)
        second_record = json.loads(second_backup.stdout)
        self.assertNotEqual(second_record["snapshotId"], backup_record["snapshotId"])
        checked = self.run_program("check")
        self.assertEqual(checked.returncode, 0, checked.stderr)
        self.assertEqual(json.loads(checked.stdout)["snapshotId"], second_record["snapshotId"])
        retained = self.run_program("retain")
        self.assertEqual(retained.returncode, 0, retained.stderr)
        self.assertEqual(json.loads(retained.stdout)["mode"], "dry-run")

        target = self.staging / "restore-tests" / "accepted-restore"
        restored = self.run_program(
            "restore",
            "--snapshot",
            second_record["snapshotId"],
            "--target",
            os.fspath(target),
        )
        self.assertEqual(restored.returncode, 0, restored.stderr)
        restore_record = json.loads(restored.stdout)
        self.assertEqual(
            restore_record["manifestSha256"], backup_record["manifestSha256"]
        )
        self.assertTrue(
            (target / "srv/atenea/worker/state-v1/record.json").is_file()
        )


if __name__ == "__main__":
    unittest.main()
