#!/usr/bin/env python3

import hashlib
import io
import json
import tempfile
import threading
import unittest
import urllib.error
import urllib.request
import uuid
from importlib.machinery import SourceFileLoader
from pathlib import Path

MODULE = SourceFileLoader(
    "worksession_attachment_worker_v1",
    str(Path(__file__).with_name("worksession-attachment-worker-v1.py")),
).load_module()


class AttachmentStoreTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name) / "attachments"
        self.store = MODULE.AttachmentStore(self.root, "test-worker")
        self.session_id = "12"
        self.attachment_id = str(uuid.uuid4())
        self.content = b"\x89PNG\r\n\x1a\nsynthetic-image"

    def tearDown(self):
        self.temporary.cleanup()

    def metadata(self, content=None, **overrides):
        content = self.content if content is None else content
        value = {
            "source": "OPERATOR_UPLOAD",
            "kind": "IMAGE",
            "contentType": "image/png",
            "retentionClass": "SESSION",
            "sha256": hashlib.sha256(content).hexdigest(),
            "syntheticFixture": True,
            "createdAt": "2026-07-28T23:00:00Z",
        }
        value.update(overrides)
        return value

    def put(self, content=None, metadata=None):
        content = self.content if content is None else content
        metadata = self.metadata(content) if metadata is None else metadata
        return self.store.put(
            self.session_id,
            self.attachment_id,
            metadata,
            io.BytesIO(content),
            len(content),
        )

    def test_create_is_atomic_scoped_and_idempotent(self):
        first, created = self.put()
        second, created_again = self.put(metadata=self.metadata(createdAt="2026-07-29T00:00:00Z"))

        self.assertTrue(created)
        self.assertFalse(created_again)
        self.assertEqual(first, second)
        self.assertEqual(self.session_id, first["sessionId"])
        self.assertEqual(self.attachment_id, first["attachmentId"])
        self.assertEqual(
            f"work-sessions/{self.session_id}/{self.attachment_id}/content",
            first["storageIdentity"],
        )
        self.assertNotIn(str(self.root), json.dumps(first))
        metadata = self.store.metadata(self.session_id, self.attachment_id)
        stored_metadata, path = self.store.content(self.session_id, self.attachment_id)
        self.assertEqual(first, metadata)
        self.assertEqual(first, stored_metadata)
        self.assertEqual(self.content, path.read_bytes())

    def test_conflicting_identity_reuse_changes_nothing(self):
        first, _ = self.put()
        conflicting = self.metadata(kind="FILE")

        with self.assertRaisesRegex(MODULE.ProtocolError, "different content or metadata"):
            self.store.put(
                self.session_id,
                self.attachment_id,
                conflicting,
                io.BytesIO(self.content),
                len(self.content),
            )

        self.assertEqual(first, self.store.metadata(self.session_id, self.attachment_id))
        _, path = self.store.content(self.session_id, self.attachment_id)
        self.assertEqual(self.content, path.read_bytes())

    def test_integrity_or_type_mismatch_leaves_no_attachment_residue(self):
        mismatch = self.metadata(sha256="0" * 64)
        with self.assertRaisesRegex(MODULE.ProtocolError, "SHA-256"):
            self.put(metadata=mismatch)
        self.assertEqual([], list((self.root / "work-sessions").rglob("content")))
        self.assertEqual([], list((self.root / ".incoming").iterdir()))

        plain = b"not a png"
        with self.assertRaisesRegex(MODULE.ProtocolError, "declared allowed content type"):
            self.put(content=plain, metadata=self.metadata(plain))
        self.assertEqual([], list((self.root / "work-sessions").rglob("content")))
        self.assertEqual([], list((self.root / ".incoming").iterdir()))

    def test_file_and_session_limits_fail_before_retention(self):
        limited = MODULE.AttachmentStore(
            Path(self.temporary.name) / "limited",
            "test-worker",
            max_file_bytes=16,
            max_session_bytes=23,
        )
        content = b"\x89PNG\r\n\x1a\n123456789"
        with self.assertRaisesRegex(MODULE.ProtocolError, "16 MiB"):
            limited.put(
                self.session_id,
                self.attachment_id,
                self.metadata(content),
                io.BytesIO(content),
                len(content),
            )

        first_content = b"\x89PNG\r\n\x1a\n1234"
        limited.put(
            self.session_id,
            self.attachment_id,
            self.metadata(first_content),
            io.BytesIO(first_content),
            len(first_content),
        )
        with self.assertRaisesRegex(MODULE.ProtocolError, "quota"):
            limited.put(
                self.session_id,
                str(uuid.uuid4()),
                self.metadata(first_content),
                io.BytesIO(first_content),
                len(first_content),
            )

    def test_cross_session_and_traversal_identities_are_rejected(self):
        self.put()
        with self.assertRaisesRegex(MODULE.ProtocolError, "does not exist"):
            self.store.content("13", self.attachment_id)
        with self.assertRaisesRegex(MODULE.ProtocolError, "positive decimal or canonical UUID"):
            self.store.content("../foreign", self.attachment_id)
        self.assertEqual(self.content, self.store.content(self.session_id, self.attachment_id)[1].read_bytes())

    def test_uuid_work_session_identity_is_scoped_and_retained(self):
        self.session_id = str(uuid.uuid4())

        stored, created = self.put()

        self.assertTrue(created)
        self.assertEqual(self.session_id, stored["sessionId"])
        self.assertEqual(
            self.content,
            self.store.content(self.session_id, self.attachment_id)[1].read_bytes(),
        )

    def test_restart_preserves_identical_content(self):
        first, _ = self.put()
        restarted = MODULE.AttachmentStore(self.root, "test-worker")
        metadata, content_path = restarted.content(self.session_id, self.attachment_id)
        self.assertEqual(first, metadata)
        self.assertEqual(hashlib.sha256(self.content).hexdigest(), hashlib.sha256(content_path.read_bytes()).hexdigest())

    def test_only_exact_synthetic_fixture_can_be_deleted(self):
        retained_id = str(uuid.uuid4())
        retained = self.metadata(syntheticFixture=False)
        self.store.put(
            self.session_id,
            retained_id,
            retained,
            io.BytesIO(self.content),
            len(self.content),
        )
        with self.assertRaisesRegex(MODULE.ProtocolError, "only exact synthetic"):
            self.store.delete_synthetic(self.session_id, retained_id)
        self.assertEqual(self.content, self.store.content(self.session_id, retained_id)[1].read_bytes())

        self.put()
        deleted = self.store.delete_synthetic(self.session_id, self.attachment_id)
        self.assertTrue(deleted["deleted"])
        with self.assertRaisesRegex(MODULE.ProtocolError, "does not exist"):
            self.store.content(self.session_id, self.attachment_id)
        self.assertEqual(self.content, self.store.content(self.session_id, retained_id)[1].read_bytes())


class AttachmentHttpTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.store = MODULE.AttachmentStore(Path(self.temporary.name) / "attachments", "http-worker")
        self.server = MODULE.AttachmentServer(("127.0.0.1", 0), self.store, "t" * 64)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.base = f"http://127.0.0.1:{self.server.server_port}"
        self.session_id = "12"
        self.attachment_id = str(uuid.uuid4())
        self.content = b"\x89PNG\r\n\x1a\nhttp-image"

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)
        self.temporary.cleanup()

    def request(self, method, path, *, token=None, content=None, extra_headers=None):
        headers = dict(extra_headers or {})
        if token is not None:
            headers["Authorization"] = "Bearer " + token
        return urllib.request.urlopen(
            urllib.request.Request(
                self.base + path,
                data=content,
                headers=headers,
                method=method,
            ),
            timeout=2,
        )

    def upload_headers(self, **overrides):
        value = {
            "Content-Type": "image/png",
            "X-Atenea-Source": "OPERATOR_UPLOAD",
            "X-Atenea-Kind": "IMAGE",
            "X-Atenea-Retention-Class": "SESSION",
            "X-Atenea-Sha256": hashlib.sha256(self.content).hexdigest(),
            "X-Atenea-Synthetic-Fixture": "true",
            "X-Atenea-Created-At": "2026-07-28T23:00:00Z",
        }
        value.update(overrides)
        return value

    def path(self, operation="content"):
        return (
            f"/v1/work-sessions/{self.session_id}/attachments/"
            f"{self.attachment_id}/{operation}"
        )

    def test_health_and_all_content_routes_require_authentication(self):
        for method, path in (
            ("GET", "/v1/health"),
            ("GET", "/v1/capabilities/real-project-attachment"),
            ("PUT", self.path()),
            ("GET", self.path()),
            ("DELETE", self.path()),
        ):
            with self.assertRaises(urllib.error.HTTPError) as denied:
                self.request(method, path, content=self.content if method == "PUT" else None)
            self.assertEqual(401, denied.exception.code)

        with self.request("GET", "/v1/health", token="t" * 64) as response:
            health = json.load(response)
        self.assertEqual(MODULE.PROTOCOL, health["protocolVersion"])
        self.assertEqual(MODULE.MAX_FILE_BYTES, health["maxFileBytes"])
        self.assertEqual(
            {
                "protocolVersion",
                "workerId",
                "healthy",
                "maxFileBytes",
                "maxSessionBytes",
                "contentTypes",
                "serverTime",
            },
            set(health),
        )

    def test_real_project_capability_is_separate_closed_and_authenticated(self):
        with self.request(
            "GET",
            "/v1/capabilities/real-project-attachment",
            token="t" * 64,
        ) as response:
            capability = json.load(response)

        self.assertEqual(
            {
                "protocolVersion",
                "workerId",
                "healthy",
                "projectIdentities",
                "storageScopes",
                "serverTime",
            },
            set(capability),
        )
        self.assertEqual(MODULE.REAL_PROJECT_PROTOCOL, capability["protocolVersion"])
        self.assertEqual("http-worker", capability["workerId"])
        self.assertTrue(capability["healthy"])
        self.assertEqual(["atenea"], capability["projectIdentities"])
        self.assertEqual(["REAL_SESSION"], capability["storageScopes"])

    def test_authenticated_upload_metadata_and_download_are_exact(self):
        with self.request(
            "PUT",
            self.path(),
            token="t" * 64,
            content=self.content,
            extra_headers=self.upload_headers(),
        ) as created:
            self.assertEqual(201, created.status)
            metadata = json.load(created)
        self.assertEqual(
            {
                "protocolVersion",
                "workerId",
                "sessionId",
                "attachmentId",
                "storageIdentity",
                "source",
                "kind",
                "contentType",
                "sizeBytes",
                "retentionClass",
                "sha256",
                "syntheticFixture",
                "createdAt",
                "storedAt",
            },
            set(metadata),
        )
        self.assertNotIn(str(self.store.root), json.dumps(metadata))

        with self.request("GET", self.path("metadata"), token="t" * 64) as response:
            self.assertEqual(metadata, json.load(response))
        with self.request("GET", self.path(), token="t" * 64) as response:
            self.assertEqual("image/png", response.headers.get_content_type())
            self.assertEqual(self.content, response.read())

    def test_missing_ambiguous_or_unsupported_metadata_fails_closed(self):
        headers = self.upload_headers()
        headers.pop("X-Atenea-Source")
        with self.assertRaises(urllib.error.HTTPError) as missing:
            self.request(
                "PUT",
                self.path(),
                token="t" * 64,
                content=self.content,
                extra_headers=headers,
            )
        self.assertEqual(400, missing.exception.code)

        headers = self.upload_headers(**{"Content-Type": "application/octet-stream"})
        with self.assertRaises(urllib.error.HTTPError) as unsupported:
            self.request(
                "PUT",
                self.path(),
                token="t" * 64,
                content=self.content,
                extra_headers=headers,
            )
        self.assertEqual(415, unsupported.exception.code)
        self.assertEqual([], list(self.store.sessions.rglob("content")))

    def test_delete_requires_exact_synthetic_confirmation(self):
        self.request(
            "PUT",
            self.path(),
            token="t" * 64,
            content=self.content,
            extra_headers=self.upload_headers(),
        ).close()
        with self.assertRaises(urllib.error.HTTPError) as denied:
            self.request("DELETE", self.path(), token="t" * 64)
        self.assertEqual(403, denied.exception.code)
        self.assertEqual(self.content, self.store.content(self.session_id, self.attachment_id)[1].read_bytes())

        with self.request(
            "DELETE",
            self.path(),
            token="t" * 64,
            extra_headers={"X-Atenea-Synthetic-Fixture": "true"},
        ) as accepted:
            self.assertTrue(json.load(accepted)["deleted"])


if __name__ == "__main__":
    unittest.main()
