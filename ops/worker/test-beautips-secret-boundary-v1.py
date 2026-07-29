#!/usr/bin/env python3

from __future__ import annotations

import hashlib
import importlib.util
import json
import os
import stat
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest import mock

SCRIPT = Path(__file__).with_name("beautips-secret-boundary-v1.py")
SPEC = importlib.util.spec_from_file_location("beautips_secret_boundary_v1", SCRIPT)
assert SPEC and SPEC.loader
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)

SESSION = "018f47a2-6b0c-7a31-9c2d-4f5a6b7c8db1"
RUNTIME = "ws-" + SESSION.replace("-", "")
SOURCE = Path(
    os.environ.get("ATENEA_BEAUTIPS_SOURCE", "/home/jose/IdeaProjects/beautips")
)
MARKER = "DO_NOT_READ_MANUAL_OR_WHATSAPP_VALUE"


class BeautipsSecretBoundaryTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory(
            prefix="beautips-secret-boundary.", dir="/tmp"
        )
        self.root = Path(self.temporary.name)
        self.session_root = self.root / "workspaces" / "sessions" / SESSION
        self.worktree = self.session_root / "beautips"
        self.runtime_root = self.session_root / "runtime" / RUNTIME
        self.cache = self.root / "caches" / "sessions" / SESSION
        self.session_root.mkdir(parents=True)
        self.runtime_root.mkdir(parents=True)
        self.cache.mkdir(parents=True)
        subprocess.run(
            ["git", "clone", "--quiet", "--no-local", str(SOURCE), str(self.worktree)],
            check=True,
            timeout=30,
        )
        subprocess.run(
            [
                "git", "-C", str(self.worktree), "remote", "set-url", "origin",
                "https://github.com/jlnieto/beautips.git",
            ],
            check=True,
            timeout=10,
        )
        allocation = {
            "schemaVersion": 1,
            "state": "allocated",
            "sessionId": SESSION,
            "projectId": "beautips",
            "workloadClass": "normal",
            "slot": "slot2",
            "runtimeId": RUNTIME,
            "runtimeRoot": str(self.runtime_root),
            "worktreePath": str(self.worktree),
            "manifestRelativePath": "ops/atenea-runtime.json",
            "cacheRoot": str(self.cache),
            "runtimeNames": {
                "composeProject": RUNTIME + "-compose",
                "network": RUNTIME + "-network",
                "volumePrefix": RUNTIME + "-volume",
                "processUnit": "atenea-" + RUNTIME + ".service",
                "tomcatBase": str(self.runtime_root / "tomcat"),
            },
            "allocatedPorts": [
                {
                    "name": "web",
                    "internalPort": 8080,
                    "protocol": "http",
                    "bindAddress": "127.0.0.1",
                    "loopbackPort": 28400,
                },
                {
                    "name": "postgres",
                    "internalPort": 5432,
                    "protocol": "tcp",
                    "bindAddress": "127.0.0.1",
                    "loopbackPort": 28401,
                },
                {
                    "name": "redis",
                    "internalPort": 6379,
                    "protocol": "tcp",
                    "bindAddress": "127.0.0.1",
                    "loopbackPort": 28402,
                },
            ],
        }
        (self.session_root / "runtime-allocation-v1.json").write_text(
            json.dumps(allocation), encoding="utf-8"
        )
        self.environment = mock.patch.dict(
            os.environ,
            {
                "ATENEA_BEAUTIPS_MEDIATOR_TEST_MODE": "1",
                "ATENEA_BEAUTIPS_MEDIATOR_TEST_ROOT": str(self.root),
                "BEAUTIPS_WHATSAPP_APP_SECRET": MARKER,
                "BEAUTIPS_MANUAL_ENV_FILE": MARKER,
            },
            clear=False,
        )
        self.environment.start()

    def tearDown(self) -> None:
        self.environment.stop()
        self.temporary.cleanup()

    @property
    def secret_root(self) -> Path:
        return self.runtime_root / "secrets"

    def fingerprints(self) -> dict[str, str]:
        return {
            item.name: hashlib.sha256(item.read_bytes()).hexdigest()
            for item in self.secret_root.iterdir()
        }

    def test_prepare_is_exact_idempotent_and_never_exposes_values(self) -> None:
        first = MODULE.prepare(SESSION)
        before = self.fingerprints()
        second = MODULE.prepare(SESSION)
        after = self.fingerprints()
        self.assertEqual(first, second)
        self.assertEqual(before, after)
        self.assertFalse(first["valuesExposed"])
        self.assertEqual(sorted(MODULE.SECRET_GENERATORS), first["names"])
        self.assertNotIn(MARKER, json.dumps(first))
        self.assertEqual(0o700, stat.S_IMODE(self.secret_root.stat().st_mode))
        for item in self.secret_root.iterdir():
            self.assertEqual(0o600, stat.S_IMODE(item.stat().st_mode))
            self.assertNotIn(MARKER.encode(), item.read_bytes())
        metadata = json.loads(
            (self.secret_root / MODULE.METADATA_NAME).read_text(encoding="utf-8")
        )
        self.assertTrue(metadata["syntheticOnly"])
        self.assertFalse(metadata["manualEnvAccepted"])
        self.assertFalse(metadata["whatsAppCredentialsAccepted"])
        self.assertNotIn("value", json.dumps(metadata).lower())

    def test_manual_whatsapp_unknown_and_symlink_entries_fail_closed(self) -> None:
        MODULE.prepare(SESSION)
        for name in (".env", "BEAUTIPS_WHATSAPP_APP_SECRET", "foreign"):
            with self.subTest(name=name):
                path = self.secret_root / name
                path.write_text(MARKER, encoding="utf-8")
                os.chmod(path, 0o600)
                with self.assertRaises(MODULE.SecretRejected):
                    MODULE.inspect(SESSION)
                path.unlink()
        link = self.secret_root / "foreign-link"
        link.symlink_to(self.secret_root / "BEAUTIPS_SYNTHETIC_POSTGRES_PASSWORD")
        with self.assertRaises(MODULE.SecretRejected):
            MODULE.inspect(SESSION)

    def test_missing_partial_or_unsafe_boundary_is_rejected(self) -> None:
        with self.assertRaises(MODULE.SecretRejected):
            MODULE.inspect(SESSION)
        MODULE.prepare(SESSION)
        target = self.secret_root / "BEAUTIPS_SYNTHETIC_SMOKE_SEAL_CODE"
        target.unlink()
        with self.assertRaises(MODULE.SecretRejected):
            MODULE.inspect(SESSION)
        target.write_text("12345678\\n", encoding="utf-8")
        os.chmod(target, 0o644)
        with self.assertRaises(MODULE.SecretRejected):
            MODULE.inspect(SESSION)


if __name__ == "__main__":
    unittest.main()
