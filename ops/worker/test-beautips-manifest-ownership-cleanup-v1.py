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

import yaml
from jsonschema import Draft202012Validator, FormatChecker

ROOT = Path(__file__).resolve().parents[2]
SOURCE = Path(
    os.environ.get("ATENEA_BEAUTIPS_SOURCE", "/home/jose/IdeaProjects/beautips")
)
MANIFEST = SOURCE / "ops/atenea-runtime.json"
COMPOSE = SOURCE / "ops/docker-compose.atenea.yml"
MEDIATOR_PATH = Path(__file__).with_name("beautips-operation-mediator-v1.py")
SPEC = importlib.util.spec_from_file_location("beautips_mediator_contract", MEDIATOR_PATH)
assert SPEC and SPEC.loader
MEDIATOR = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MEDIATOR)

SESSION = "018f47a2-6b0c-7a31-9c2d-4f5a6b7c8db1"
RUNTIME = "ws-" + SESSION.replace("-", "")
ENGINE = "atenea-runtime-engine-v1"


class BeautipsManifestOwnershipCleanupTest(unittest.TestCase):
    def test_manifest_is_schema_valid_and_cleanup_is_exact(self) -> None:
        manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
        schema = json.loads(
            (ROOT / "runtime-contract/project-runtime-v1.schema.json").read_text(
                encoding="utf-8"
            )
        )
        Draft202012Validator(schema, format_checker=FormatChecker()).validate(manifest)
        self.assertEqual("beautips", manifest["project"]["id"])
        self.assertEqual(
            ["docker", "compose", "-f", "ops/docker-compose.atenea.yml", "down",
             "--volumes", "--remove-orphans", "--rmi", "local"],
            manifest["lifecycle"]["stop"]["argv"],
        )
        self.assertFalse(manifest["preview"]["localhostCompatibilityRequired"])
        self.assertEqual("private", manifest["preview"]["publish"])

    def test_compose_requires_allocation_names_and_complete_ownership(self) -> None:
        compose = yaml.safe_load(COMPOSE.read_text(encoding="utf-8"))
        self.assertEqual({"app", "postgres", "redis"}, set(compose["services"]))
        expected_labels = {
            "com.atenea.engine": "${ATENEA_ENGINE_LABEL:?required}",
            "com.atenea.session": "${ATENEA_SESSION_ID:?required}",
            "com.atenea.runtime": "${ATENEA_RUNTIME_ID:?required}",
            "com.atenea.project": "beautips",
        }
        expected_ports = {
            "app": "127.0.0.1:${ATENEA_WEB_PORT:?required}:8080",
            "postgres": "127.0.0.1:${ATENEA_POSTGRES_PORT:?required}:5432",
            "redis": "127.0.0.1:${ATENEA_REDIS_PORT:?required}:6379",
        }
        for name, service in compose["services"].items():
            self.assertEqual("no", service["restart"])
            self.assertNotIn("container_name", service)
            self.assertEqual(expected_ports[name], service["ports"][0])
            for key, value in expected_labels.items():
                self.assertEqual(value, service["labels"][key])
            self.assertEqual(name, service["labels"]["com.atenea.service"])
        network = compose["networks"]["runtime"]
        self.assertTrue(network["internal"])
        self.assertEqual("${ATENEA_NETWORK_NAME:?required}", network["name"])
        for key, value in expected_labels.items():
            self.assertEqual(value, network["labels"][key])
        expected_volumes = {
            "postgres-data": "postgres",
            "redis-data": "redis",
            "assets-data": "assets",
            "imports-data": "imports",
        }
        self.assertEqual(set(expected_volumes), set(compose["volumes"]))
        for name, purpose in expected_volumes.items():
            volume = compose["volumes"][name]
            self.assertEqual(
                f"${{ATENEA_VOLUME_PREFIX:?required}}-{name}", volume["name"]
            )
            for key, value in expected_labels.items():
                self.assertEqual(value, volume["labels"][key])
            self.assertEqual(purpose, volume["labels"]["com.atenea.service"])
        rendered = json.dumps(compose, sort_keys=True)
        self.assertNotIn("/srv/atenea/workspaces/manual/beautips", rendered)
        self.assertNotIn("env_file", rendered)

    def test_mediator_plan_is_idempotent_and_cleanup_is_allocation_scoped(self) -> None:
        with tempfile.TemporaryDirectory(
            prefix="beautips-contract.", dir="/tmp"
        ) as temporary:
            root = Path(temporary)
            session_root = root / "workspaces" / "sessions" / SESSION
            worktree = session_root / "beautips"
            cache = root / "caches" / "sessions" / SESSION
            worktree.parent.mkdir(parents=True)
            cache.mkdir(parents=True)
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
            allocation = {
                "schemaVersion": 1,
                "state": "allocated",
                "sessionId": SESSION,
                "projectId": "beautips",
                "workloadClass": "normal",
                "slot": "slot2",
                "runtimeId": RUNTIME,
                "worktreePath": str(worktree),
                "manifestRelativePath": "ops/atenea-runtime.json",
                "cacheRoot": str(cache),
                "runtimeNames": {
                    "composeProject": RUNTIME + "-compose",
                    "network": RUNTIME + "-network",
                    "volumePrefix": RUNTIME + "-volume",
                    "processUnit": "atenea-" + RUNTIME + ".service",
                    "tomcatBase": str(session_root / "runtime" / RUNTIME / "tomcat"),
                },
                "allocatedPorts": [
                    {
                        "name": "web", "internalPort": 8080, "protocol": "http",
                        "bindAddress": "127.0.0.1", "loopbackPort": 28400,
                    },
                    {
                        "name": "postgres", "internalPort": 5432, "protocol": "tcp",
                        "bindAddress": "127.0.0.1", "loopbackPort": 28401,
                    },
                    {
                        "name": "redis", "internalPort": 6379, "protocol": "tcp",
                        "bindAddress": "127.0.0.1", "loopbackPort": 28402,
                    },
                ],
            }
            (session_root / "runtime-allocation-v1.json").write_text(
                json.dumps(allocation), encoding="utf-8"
            )
            environment = {
                "ATENEA_BEAUTIPS_MEDIATOR_TEST_MODE": "1",
                "ATENEA_BEAUTIPS_MEDIATOR_TEST_ROOT": str(root),
            }
            with mock.patch.dict(os.environ, environment, clear=False):
                first = MEDIATOR.plan(SESSION, "runtime-cleanup")
                second = MEDIATOR.plan(SESSION, "runtime-cleanup")
            self.assertEqual(first, second)
            self.assertEqual(
                [
                    "docker", "--host",
                    "unix:///run/atenea-runtime/slot2/docker.sock",
                    "compose", "--project-name", RUNTIME + "-compose", "--file",
                    str(worktree / "ops/docker-compose.atenea.yml"),
                    "down", "--volumes", "--remove-orphans", "--rmi", "local",
                ],
                first["argv"],
            )
            self.assertEqual(ENGINE, first["environment"]["ATENEA_ENGINE_LABEL"])
            self.assertEqual(SESSION, first["environment"]["ATENEA_SESSION_ID"])
            self.assertEqual(RUNTIME, first["environment"]["ATENEA_RUNTIME_ID"])
            self.assertFalse(first["executionEnabled"])


if __name__ == "__main__":
    unittest.main()
