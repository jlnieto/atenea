#!/usr/bin/env python3

from __future__ import annotations

import hashlib
import importlib.util
import json
import socket
import tempfile
import threading
import time
import unittest
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

MODULE_PATH = Path(__file__).with_name("session-preview-worker-v1.py")
SPEC = importlib.util.spec_from_file_location("session_preview_worker_v1", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)

PREVIEW_ID = "61000000-0000-4000-8000-000000000001"
RUNTIME_SESSION_ID = "61000000-0000-4000-8000-000000000002"
WORK_SESSION_ID = "61001"
PROJECT_ID = "synthetic-preview"
WORKER_ID = "ax42-01"
TOKEN = "phase6-test-token-" + "a" * 32


class FixtureHandler(BaseHTTPRequestHandler):
    def do_GET(self) -> None:
        encoded = f"fixture:{self.path}".encode()
        self.send_response(200)
        self.send_header("Content-Type", "text/plain")
        self.send_header("Content-Length", str(len(encoded)))
        self.end_headers()
        self.wfile.write(encoded)

    def log_message(self, _message: str, *_args: object) -> None:
        pass


class PreviewCoordinatorTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory(prefix="phase6-preview-worker-")
        self.base = Path(self.temporary.name)
        self.state_root = self.base / "state"
        self.workspace_root = self.base / "workspaces"
        self.ingress_start = self._contiguous_ports(4)
        self.fixture = ThreadingHTTPServer(("127.0.0.1", 0), FixtureHandler)
        self.fixture_thread = threading.Thread(target=self.fixture.serve_forever, daemon=True)
        self.fixture_thread.start()
        self.allocation_fingerprint = self._write_allocation(
            self.fixture.server_address[1], localhost=True
        )
        self.coordinator = MODULE.PreviewCoordinator(
            self.state_root,
            self.workspace_root,
            WORKER_ID,
            "127.0.0.1",
            self.ingress_start,
            self.ingress_start + 3,
            test_mode=True,
        )

    def tearDown(self) -> None:
        self.coordinator.close()
        self.fixture.shutdown()
        self.fixture.server_close()
        self.fixture_thread.join(timeout=2)
        self.temporary.cleanup()

    def test_activate_proxies_exact_allocation_without_runtime_port_disclosure(self) -> None:
        result, created = self.coordinator.activate(PREVIEW_ID, self._activate_request())

        self.assertTrue(created)
        self.assertEqual("READY", result["state"])
        self.assertEqual(2, result["lifecycleRevision"])
        self.assertTrue(result["privateUrl"].endswith("/ready"))
        self.assertTrue(result["localhostCompatible"])
        self.assertFalse(result["tunnel"]["credentialIncluded"])
        self.assertFalse(result["tunnel"]["runtimePortExposed"])
        self.assertNotIn(str(self.fixture.server_address[1]), json.dumps(result))
        with urllib.request.urlopen(result["privateUrl"], timeout=3) as response:
            self.assertEqual(b"fixture:/ready", response.read())

    def test_identical_activation_retry_returns_one_listener_and_record(self) -> None:
        first, first_created = self.coordinator.activate(PREVIEW_ID, self._activate_request())
        second, second_created = self.coordinator.activate(PREVIEW_ID, self._activate_request())

        self.assertTrue(first_created)
        self.assertFalse(second_created)
        self.assertEqual(first, second)
        self.assertEqual(1, len(self.coordinator.forwarders))
        self.assertEqual(
            1,
            len(list((self.state_root / "previews").glob("*/record.json"))),
        )

    def test_foreign_and_arbitrary_activation_inputs_fail_closed(self) -> None:
        foreign = self._activate_request()
        foreign["projectId"] = "foreign-project"
        with self.assertRaisesRegex(MODULE.ProtocolError, "ownership"):
            self.coordinator.activate(PREVIEW_ID, foreign)

        arbitrary = self._activate_request()
        arbitrary["upstreamHost"] = "127.0.0.1"
        with self.assertRaisesRegex(MODULE.ProtocolError, "fields"):
            self.coordinator.activate(PREVIEW_ID, arbitrary)

        mismatch = self._activate_request()
        mismatch["allocationFingerprint"] = "b" * 64
        with self.assertRaisesRegex(MODULE.ProtocolError, "fingerprint"):
            self.coordinator.activate(PREVIEW_ID, mismatch)

        self.assertEqual({}, self.coordinator.forwarders)
        self.assertEqual([], list((self.state_root / "previews").glob("*/record.json")))

    def test_stale_renew_and_foreign_stop_preserve_ready_projection(self) -> None:
        ready, _created = self.coordinator.activate(PREVIEW_ID, self._activate_request())
        stale = self._operation_request(expected_revision=1)
        with self.assertRaisesRegex(MODULE.ProtocolError, "revision"):
            self.coordinator.renew(PREVIEW_ID, stale)

        foreign = self._operation_request(expected_revision=2)
        foreign["workSessionId"] = "61002"
        with self.assertRaisesRegex(MODULE.ProtocolError, "ownership"):
            self.coordinator.stop(PREVIEW_ID, foreign)

        inspected = self.coordinator.inspect(PREVIEW_ID, self._query())
        self.assertEqual(ready["privateUrl"], inspected["privateUrl"])
        self.assertEqual("READY", inspected["state"])

    def test_stop_is_exact_and_idempotent_then_synthetic_delete_is_bounded(self) -> None:
        ready, _created = self.coordinator.activate(PREVIEW_ID, self._activate_request())
        stopped = self.coordinator.stop(
            PREVIEW_ID, self._operation_request(expected_revision=2)
        )
        repeated = self.coordinator.stop(
            PREVIEW_ID, self._operation_request(expected_revision=2)
        )

        self.assertEqual("STOPPED", stopped["state"])
        self.assertIsNone(stopped["privateUrl"])
        self.assertEqual(stopped, repeated)
        self.assertEqual({}, self.coordinator.forwarders)
        with self.assertRaises(Exception):
            urllib.request.urlopen(ready["privateUrl"], timeout=0.2)

        wrong_revision = self._operation_request(expected_revision=2)
        with self.assertRaisesRegex(MODULE.ProtocolError, "revision"):
            self.coordinator.delete_synthetic(PREVIEW_ID, wrong_revision)

        deleted = self.coordinator.delete_synthetic(
            PREVIEW_ID, self._operation_request(expected_revision=3)
        )
        self.assertTrue(deleted["deleted"])
        self.assertFalse((self.state_root / "previews" / PREVIEW_ID).exists())

    def test_unlabelled_candidate_cannot_be_deleted_or_modified(self) -> None:
        candidate = self.state_root / "previews" / PREVIEW_ID
        candidate.mkdir(parents=True)
        payload = candidate / "foreign-resource"
        payload.write_bytes(b"unlabelled-preview-like-resource")
        fingerprint = hashlib.sha256(payload.read_bytes()).hexdigest()

        with self.assertRaisesRegex(MODULE.ProtocolError, "does not exist"):
            self.coordinator.delete_synthetic(
                PREVIEW_ID, self._operation_request(expected_revision=3)
            )

        self.assertTrue(candidate.is_dir())
        self.assertEqual(fingerprint, hashlib.sha256(payload.read_bytes()).hexdigest())

    def test_partial_persisted_record_fails_closed_and_remains_unchanged(self) -> None:
        self.coordinator.activate(PREVIEW_ID, self._activate_request())
        self.coordinator.close()
        record_path = self.state_root / "previews" / PREVIEW_ID / "record.json"
        partial = json.loads(record_path.read_text())
        partial.pop("workerId")
        record_path.write_text(json.dumps(partial, sort_keys=True))
        fingerprint = hashlib.sha256(record_path.read_bytes()).hexdigest()

        with self.assertRaisesRegex(MODULE.ProtocolError, "schema"):
            MODULE.PreviewCoordinator(
                self.state_root,
                self.workspace_root,
                WORKER_ID,
                "127.0.0.1",
                self.ingress_start,
                self.ingress_start + 3,
                test_mode=True,
            )

        self.assertEqual(fingerprint, hashlib.sha256(record_path.read_bytes()).hexdigest())

    def test_foreign_non_synthetic_record_cannot_be_deleted_or_modified(self) -> None:
        self.coordinator.activate(PREVIEW_ID, self._activate_request())
        self.coordinator.stop(PREVIEW_ID, self._operation_request(expected_revision=2))
        record_path = self.state_root / "previews" / PREVIEW_ID / "record.json"
        foreign = json.loads(record_path.read_text())
        foreign["syntheticFixture"] = False
        record_path.write_text(json.dumps(foreign, sort_keys=True))
        fingerprint = hashlib.sha256(record_path.read_bytes()).hexdigest()

        with self.assertRaisesRegex(MODULE.ProtocolError, "ownership"):
            self.coordinator.delete_synthetic(
                PREVIEW_ID, self._operation_request(expected_revision=3)
            )

        self.assertEqual(fingerprint, hashlib.sha256(record_path.read_bytes()).hexdigest())

    def test_restart_reconciles_same_unexpired_projection(self) -> None:
        first, _created = self.coordinator.activate(PREVIEW_ID, self._activate_request())
        self.coordinator.close()

        self.coordinator = MODULE.PreviewCoordinator(
            self.state_root,
            self.workspace_root,
            WORKER_ID,
            "127.0.0.1",
            self.ingress_start,
            self.ingress_start + 3,
            test_mode=True,
        )
        after = self.coordinator.inspect(PREVIEW_ID, self._query())

        self.assertEqual(first["privateUrl"], after["privateUrl"])
        self.assertEqual(first["allocationIdentity"], after["allocationIdentity"])
        with urllib.request.urlopen(after["privateUrl"], timeout=3) as response:
            self.assertEqual(b"fixture:/ready", response.read())

    def test_sweeper_expires_listener_without_deleting_record(self) -> None:
        ready, _created = self.coordinator.activate(PREVIEW_ID, self._activate_request())
        record = self.coordinator._read(PREVIEW_ID)
        record["leaseExpiresAt"] = MODULE.timestamp(
            MODULE.utc_now() - MODULE.timedelta(seconds=1)
        )
        self.coordinator._write(record)

        deadline = time.monotonic() + 3
        while time.monotonic() < deadline:
            current = self.coordinator._read(PREVIEW_ID)
            if current["state"] == "EXPIRED":
                break
            time.sleep(0.05)

        self.assertEqual("EXPIRED", self.coordinator._read(PREVIEW_ID)["state"])
        self.assertTrue((self.state_root / "previews" / PREVIEW_ID / "record.json").is_file())
        self.assertEqual({}, self.coordinator.forwarders)
        with self.assertRaises(Exception):
            urllib.request.urlopen(ready["privateUrl"], timeout=0.2)

    def test_manifest_controls_localhost_compatibility(self) -> None:
        self.allocation_fingerprint = self._write_allocation(
            self.fixture.server_address[1], localhost=False
        )
        result, _created = self.coordinator.activate(PREVIEW_ID, self._activate_request())

        self.assertFalse(result["localhostCompatible"])
        self.assertIsNone(result["tunnel"])

    def test_ambiguous_allocation_preview_port_is_rejected(self) -> None:
        allocation_path = self._allocation_path()
        allocation = json.loads(allocation_path.read_text())
        allocation["allocatedPorts"].append(dict(allocation["allocatedPorts"][0]))
        allocation_path.write_text(json.dumps(allocation, sort_keys=True))
        self.allocation_fingerprint = hashlib.sha256(allocation_path.read_bytes()).hexdigest()

        with self.assertRaisesRegex(MODULE.ProtocolError, "one exact"):
            self.coordinator.activate(PREVIEW_ID, self._activate_request())

        self.assertEqual({}, self.coordinator.forwarders)

    def test_http_control_requires_auth_and_rejects_malformed_json(self) -> None:
        control_port = self._free_port()
        server = MODULE.PreviewControlServer(
            ("127.0.0.1", control_port), self.coordinator, TOKEN
        )
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            health = urllib.request.Request(f"http://127.0.0.1:{control_port}/v1/health")
            with self.assertRaises(urllib.error.HTTPError) as unauthorized:
                urllib.request.urlopen(health, timeout=3)
            self.assertEqual(401, unauthorized.exception.code)

            malformed = urllib.request.Request(
                f"http://127.0.0.1:{control_port}/v1/previews/{PREVIEW_ID}/activate",
                data=b"{",
                method="POST",
                headers={"Authorization": f"Bearer {TOKEN}", "Content-Type": "application/json"},
            )
            with self.assertRaises(urllib.error.HTTPError) as bad:
                urllib.request.urlopen(malformed, timeout=3)
            self.assertEqual(400, bad.exception.code)

            authorized = urllib.request.Request(
                f"http://127.0.0.1:{control_port}/v1/health",
                headers={"Authorization": f"Bearer {TOKEN}"},
            )
            with urllib.request.urlopen(authorized, timeout=3) as response:
                payload = json.loads(response.read())
            self.assertEqual(MODULE.PROTOCOL, payload["protocolVersion"])
            self.assertFalse(payload["publicSharing"])
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=2)

    def _write_allocation(self, upstream_port: int, localhost: bool) -> str:
        session_root = self.workspace_root / "sessions" / RUNTIME_SESSION_ID
        worktree = session_root / PROJECT_ID
        manifest_path = worktree / ".atenea" / "project-runtime-v1.json"
        manifest_path.parent.mkdir(parents=True, exist_ok=True)
        manifest = {
            "schemaVersion": 1,
            "project": {"id": PROJECT_ID},
            "preview": {
                "internalPort": "web",
                "path": "/ready",
                "publish": "private",
                "localhostCompatibilityRequired": localhost,
            },
        }
        manifest_path.write_text(json.dumps(manifest, sort_keys=True))
        allocation = {
            "schemaVersion": 1,
            "state": "allocated",
            "sessionId": RUNTIME_SESSION_ID,
            "projectId": PROJECT_ID,
            "runtimeId": f"ws-{RUNTIME_SESSION_ID.replace('-', '')}",
            "worktreePath": str(worktree),
            "manifestRelativePath": ".atenea/project-runtime-v1.json",
            "allocatedPorts": [
                {
                    "name": "web",
                    "internalPort": 8080,
                    "protocol": "http",
                    "bindAddress": "127.0.0.1",
                    "loopbackPort": upstream_port,
                }
            ],
        }
        allocation_path = session_root / "runtime-allocation-v1.json"
        allocation_path.write_text(json.dumps(allocation, sort_keys=True))
        return hashlib.sha256(allocation_path.read_bytes()).hexdigest()

    def _allocation_path(self) -> Path:
        return (
            self.workspace_root
            / "sessions"
            / RUNTIME_SESSION_ID
            / "runtime-allocation-v1.json"
        )

    def _activate_request(self) -> dict[str, object]:
        request = self._operation_request(expected_revision=1)
        request["runtimeSessionId"] = RUNTIME_SESSION_ID
        return request

    def _operation_request(self, expected_revision: int) -> dict[str, object]:
        return {
            "protocolVersion": MODULE.PROTOCOL,
            "workSessionId": WORK_SESSION_ID,
            "projectId": PROJECT_ID,
            "workerId": WORKER_ID,
            "allocationIdentity": f"ws-{RUNTIME_SESSION_ID.replace('-', '')}",
            "allocationFingerprint": self.allocation_fingerprint,
            "expectedRevision": expected_revision,
            "syntheticFixture": True,
        }

    def _query(self) -> dict[str, list[str]]:
        return {
            "workSessionId": [WORK_SESSION_ID],
            "projectId": [PROJECT_ID],
            "workerId": [WORKER_ID],
            "allocationIdentity": [f"ws-{RUNTIME_SESSION_ID.replace('-', '')}"],
            "allocationFingerprint": [self.allocation_fingerprint],
        }

    def _contiguous_ports(self, count: int) -> int:
        for start in range(39000, 45000 - count):
            sockets = []
            try:
                for port in range(start, start + count):
                    candidate = socket.socket()
                    candidate.bind(("127.0.0.1", port))
                    sockets.append(candidate)
                return start
            except OSError:
                pass
            finally:
                for candidate in sockets:
                    candidate.close()
        raise RuntimeError("no contiguous test port range is available")

    def _free_port(self) -> int:
        candidate = socket.socket()
        candidate.bind(("127.0.0.1", 0))
        port = candidate.getsockname()[1]
        candidate.close()
        return port


if __name__ == "__main__":
    unittest.main(verbosity=2)
