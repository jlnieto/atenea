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

from jsonschema import Draft202012Validator, FormatChecker

SCRIPT = Path(__file__).with_name("beautips-operation-mediator-v1.py")
SPEC = importlib.util.spec_from_file_location("beautips_mediator", SCRIPT)
assert SPEC and SPEC.loader
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)

SESSION = "018f47a2-6b0c-7a31-9c2d-4f5a6b7c8db1"
RUNTIME = "ws-" + SESSION.replace("-", "")
BEAUTIPS_SOURCE = Path(
    os.environ.get("ATENEA_BEAUTIPS_SOURCE", "/home/jose/IdeaProjects/beautips")
)


class BeautipsOperationMediatorTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory(
            prefix="beautips-operation-mediator.", dir="/tmp"
        )
        self.root = Path(self.temporary.name)
        self.session_root = self.root / "workspaces" / "sessions" / SESSION
        self.worktree = self.session_root / "beautips"
        self.cache = self.root / "caches" / "sessions" / SESSION
        self.session_root.mkdir(parents=True)
        self.cache.mkdir(parents=True)
        subprocess.run(
            ["git", "clone", "--quiet", "--no-local", str(BEAUTIPS_SOURCE), str(self.worktree)],
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
        self.allocation = {
            "schemaVersion": 1,
            "state": "allocated",
            "sessionId": SESSION,
            "projectId": "beautips",
            "workloadClass": "normal",
            "slot": "slot2",
            "runtimeId": RUNTIME,
            "worktreePath": str(self.worktree),
            "manifestRelativePath": "ops/atenea-runtime.json",
            "cacheRoot": str(self.cache),
            "runtimeNames": {
                "composeProject": RUNTIME + "-compose",
                "network": RUNTIME + "-network",
                "volumePrefix": RUNTIME + "-volume",
                "processUnit": "atenea-" + RUNTIME + ".service",
                "tomcatBase": str(self.session_root / "runtime" / RUNTIME / "tomcat"),
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
        self.allocation_path = self.session_root / "runtime-allocation-v1.json"
        self.write_allocation()
        self.environment = mock.patch.dict(
            os.environ,
            {
                "ATENEA_BEAUTIPS_MEDIATOR_TEST_MODE": "1",
                "ATENEA_BEAUTIPS_MEDIATOR_TEST_ROOT": str(self.root),
            },
            clear=False,
        )
        self.environment.start()

    def tearDown(self) -> None:
        self.environment.stop()
        self.temporary.cleanup()

    def write_allocation(self) -> None:
        self.allocation_path.write_text(
            json.dumps(self.allocation), encoding="utf-8"
        )

    def test_git_safe_directory_is_scoped_to_exact_validated_worktree(self) -> None:
        completed = subprocess.CompletedProcess(
            args=[], returncode=0, stdout="expected\n", stderr=""
        )
        with mock.patch.object(MODULE.subprocess, "run", return_value=completed) as run:
            self.assertEqual(
                "expected",
                MODULE.checked(["git", "rev-parse", "HEAD"], self.worktree),
            )
        command = run.call_args.args[0]
        self.assertEqual(
            [
                "git",
                "-c",
                f"safe.directory={self.worktree}",
                "rev-parse",
                "HEAD",
            ],
            command,
        )
        with self.assertRaises(MODULE.Rejected):
            MODULE.checked(["sh", "-c", "true"], self.worktree)

    def test_all_ten_operations_produce_closed_non_executable_plans(self) -> None:
        operations = json.loads(
            SCRIPT.with_name("beautips-runtime-operations-v1.json").read_text()
        )["operations"]
        schema = json.loads(
            (
                SCRIPT.parents[2]
                / "runtime-contract"
                / "beautips-operation-plan-v1.schema.json"
            ).read_text()
        )
        validator = Draft202012Validator(schema, format_checker=FormatChecker())
        plans = {
            name: MODULE.plan(SESSION, name)
            for name in operations
        }
        self.assertEqual(10, len(plans))
        for name, plan in plans.items():
            validator.validate(plan)
            self.assertEqual("beautips-operation-plan-v1", plan["schemaVersion"])
            self.assertFalse(plan["executionEnabled"])
            self.assertEqual(name, plan["operation"])
            self.assertEqual(SESSION, plan["sessionId"])
            self.assertEqual("beautips", plan["projectId"])
            self.assertEqual("slot2", plan["slot"])
            self.assertEqual(str(self.worktree), plan["worktreePath"])
            self.assertNotIn("PASSWORD", json.dumps(plan["environment"]))
            self.assertGreater(plan["timeoutSeconds"], 0)
            self.assertTrue(plan["argv"])
        self.assertIn("--volumes", plans["runtime-cleanup"]["argv"])
        self.assertIn("--rmi", plans["runtime-cleanup"]["argv"])
        self.assertIn(
            "-Dfrontend.build.skip=true",
            plans["maven-test"]["argv"],
        )
        self.assertIn(
            "--cache /workspace/.npm",
            plans["node-build"]["argv"][-1],
        )
        self.assertEqual(
            "true",
            plans["functional-smoke"]["environment"]["BEAUTIPS_SMOKE_MANAGED_MODE"],
        )

    def test_unknown_operation_and_noncanonical_session_are_rejected(self) -> None:
        with self.assertRaises(MODULE.Rejected):
            MODULE.plan(SESSION, "shell")
        with self.assertRaises(MODULE.Rejected):
            MODULE.plan(SESSION.upper(), "runtime-health")

    def test_foreign_slot_port_path_and_project_are_rejected(self) -> None:
        mutations = (
            ("slot", "slot1"),
            ("projectId", "foreign"),
            ("worktreePath", str(self.root / "foreign")),
        )
        for key, value in mutations:
            with self.subTest(key=key):
                original = self.allocation[key]
                self.allocation[key] = value
                self.write_allocation()
                with self.assertRaises(MODULE.Rejected):
                    MODULE.plan(SESSION, "runtime-health")
                self.allocation[key] = original
        self.allocation["allocatedPorts"][0]["loopbackPort"] = 28401
        self.write_allocation()
        with self.assertRaises(MODULE.Rejected):
            MODULE.plan(SESSION, "runtime-health")

    def test_foreign_git_remote_manifest_and_compose_are_rejected(self) -> None:
        subprocess.run(
            ["git", "-C", str(self.worktree), "remote", "set-url", "origin",
             "https://github.com/example/beautips.git"],
            check=True,
            timeout=10,
        )
        with self.assertRaises(MODULE.Rejected):
            MODULE.plan(SESSION, "runtime-health")
        subprocess.run(
            ["git", "-C", str(self.worktree), "remote", "set-url", "origin",
             "https://github.com/jlnieto/beautips.git"],
            check=True,
            timeout=10,
        )
        manifest = self.worktree / "ops/atenea-runtime.json"
        original = manifest.read_bytes()
        manifest.write_bytes(original + b"\\n")
        with self.assertRaises(MODULE.Rejected):
            MODULE.plan(SESSION, "runtime-health")
        manifest.write_bytes(original)
        compose = self.worktree / "ops/docker-compose.atenea.yml"
        original = compose.read_bytes()
        compose.write_bytes(original + b"\\n")
        with self.assertRaises(MODULE.Rejected):
            MODULE.plan(SESSION, "runtime-health")


if __name__ == "__main__":
    unittest.main()
