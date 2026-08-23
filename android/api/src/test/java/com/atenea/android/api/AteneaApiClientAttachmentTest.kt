package com.atenea.android.api

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AteneaApiClientAttachmentTest {

    @Test
    fun `turn body keeps stable request and ordered attachment identities`() {
        val requestId = UUID.fromString("00000000-0000-0000-0000-000000000010")
        val first = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val second = UUID.fromString("00000000-0000-0000-0000-000000000002")

        val body = buildMobileWorkSessionTurnBody("  revisa esto  ", requestId, listOf(first, second))

        assertEquals("  revisa esto  ", body.getString("message"))
        assertEquals(requestId.toString(), body.getString("clientRequestId"))
        assertEquals(listOf(first.toString(), second.toString()), body.getJSONArray("attachmentIds").strings())
    }

    @Test
    fun `capability parser rejects unknown server enums and retains exact limits`() {
        val ready = capabilityJson()
        val parsed = parseWorkSessionAttachmentCapability(ready)

        assertEquals(WorkSessionAttachmentCapabilityState.READY, parsed.state)
        assertEquals(WorkSessionAttachmentBlockedReason.NONE, parsed.blockedReason)
        assertEquals(16L * 1024L * 1024L, parsed.maxFileBytes)
        assertEquals(4, parsed.maxAttachmentsPerTurn)
        assertEquals(listOf("image/png", "image/jpeg", "image/webp"), parsed.acceptedContentTypes)

        ready.put("blockedReason", "FUTURE_UNKNOWN_REASON")
        assertFailsWith<IllegalArgumentException> { parseWorkSessionAttachmentCapability(ready) }
    }

    @Test
    fun `historical turn parser keeps exact attachment order and metadata`() {
        val first = attachmentBindingJson("00000000-0000-0000-0000-000000000001", 0)
        val second = attachmentBindingJson("00000000-0000-0000-0000-000000000002", 1)
        val turn = parseMobileConversationTurn(
            JSONObject()
                .put("id", 40)
                .put("actor", "OPERATOR")
                .put("messageText", "fixture")
                .put("createdAt", "2026-08-11T12:00:00Z")
                .put("attachments", JSONArray().put(first).put(second))
        )

        assertEquals(listOf(0, 1), turn.attachments.map { it.position })
        assertEquals(listOf("fixture-0.png", "fixture-1.png"), turn.attachments.map { it.originalFilename })
        assertTrue(turn.attachments.all { it.downloadPath.startsWith("/api/sessions/19/attachments/") })
    }

    @Test
    fun `multipart escapes display name and writes exact bytes`() {
        val bytes = byteArrayOf(0x01, 0x02, 0x03)
        val parts = multipartParts(
            boundary = "Boundary",
            fieldName = "file",
            fileName = "fixture\"image.png",
            contentType = "image/png",
            bytes = bytes
        )

        assertTrue(parts.header.toString(Charsets.UTF_8).contains("filename=\"fixture\\\"image.png\""))
        assertTrue(parts.header.toString(Charsets.UTF_8).contains("Content-Type: image/png"))
        assertContentEquals(bytes, parts.fileBytes)
        assertEquals(parts.header.size + bytes.size + parts.footer.size, parts.totalBytes)
    }

    @Test
    fun `upload preserves idempotency header across one token refresh`() = withServer { server ->
        val uploadAttempts = AtomicInteger()
        val authorization = mutableListOf<String?>()
        val idempotency = mutableListOf<String?>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/api/mobile/auth/refresh" -> jsonResponse(200, authJson("new-access"))
                "/api/mobile/sessions/19/attachments" -> {
                    authorization += request.getHeader("Authorization")
                    idempotency += request.getHeader("Idempotency-Key")
                    if (uploadAttempts.getAndIncrement() == 0) {
                        jsonResponse(401, "{\"message\":\"expired\"}")
                    } else {
                        jsonResponse(201, storedAttachmentJson().toString())
                    }
                }
                else -> MockResponse().setResponseCode(404)
            }
        }
        var accessToken = "old-access"
        val client = AteneaApiClient(
            baseUrl = server.baseUrl(),
            accessTokenProvider = { accessToken },
            refreshTokenProvider = { "refresh-token" },
            sessionUpdater = { accessToken = it.accessToken }
        )
        val key = UUID.fromString("00000000-0000-0000-0000-000000000099")

        val result = runBlocking {
            client.uploadWorkSessionAttachment(
                sessionId = 19,
                idempotencyKey = key,
                fileName = "fixture.png",
                contentType = "image/png",
                bytes = byteArrayOf(1, 2, 3)
            )
        }

        assertEquals(2, uploadAttempts.get())
        assertEquals(listOf<String?>("Bearer old-access", "Bearer new-access"), authorization)
        assertEquals(listOf<String?>(key.toString(), key.toString()), idempotency)
        assertEquals(19L, result.workSessionId)
    }

    @Test
    fun `download rejects declared content beyond the caller bound`() = withServer { server ->
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "image/png")
                .setBody(okio.Buffer().write(byteArrayOf(1, 2, 3, 4)))
        )
        val client = AteneaApiClient(server.baseUrl(), { "access" })

        val error = assertFailsWith<AteneaApiException> {
            runBlocking {
                client.downloadWorkSessionAttachment(
                    sessionId = 19,
                    attachmentId = UUID.fromString("00000000-0000-0000-0000-000000000001"),
                    maxBytes = 3
                )
            }
        }

        assertEquals(413, error.status)
    }

    private fun capabilityJson(): JSONObject = JSONObject()
        .put("state", "READY")
        .put("blockedReason", "NONE")
        .put("message", "Puedes adjuntar imágenes.")
        .put("nextAction", "Selecciona hasta 4 imágenes.")
        .put("policyRevision", "atenea-real-attachments-v1")
        .put("workerCompatibility", "COMPATIBLE")
        .put("acceptedContentTypes", JSONArray(listOf("image/png", "image/jpeg", "image/webp")))
        .put("currentSessionBytes", 100)
        .put("maxSessionBytes", 256L * 1024L * 1024L)
        .put("remainingSessionBytes", 200L * 1024L * 1024L)
        .put("maxFileBytes", 16L * 1024L * 1024L)
        .put("maxAttachmentsPerTurn", 4)
        .put("maxAttachmentBytesPerTurn", 32L * 1024L * 1024L)

    private fun attachmentBindingJson(id: String, position: Int): JSONObject = JSONObject()
        .put("id", id)
        .put("position", position)
        .put("originalFilename", "fixture-$position.png")
        .put("contentType", "image/png")
        .put("sizeBytes", 128 + position)
        .put("sha256", "a".repeat(64))
        .put("downloadPath", "/api/sessions/19/attachments/$id/content")

    private fun storedAttachmentJson(): JSONObject = JSONObject()
        .put("id", "00000000-0000-0000-0000-000000000001")
        .put("workSessionId", 19)
        .put("projectId", 1)
        .put("agentRunId", JSONObject.NULL)
        .put("source", "OPERATOR_UPLOAD")
        .put("kind", "IMAGE")
        .put("originalFilename", "fixture.png")
        .put("contentType", "image/png")
        .put("sizeBytes", 3)
        .put("retentionClass", "SESSION")
        .put("retainUntil", "2026-09-10T12:00:00Z")
        .put("sha256", "a".repeat(64))
        .put("createdAt", "2026-08-11T12:00:00Z")
        .put("indexedAt", "2026-08-11T12:00:00Z")

    private fun authJson(accessToken: String): String = JSONObject()
        .put("accessToken", accessToken)
        .put("accessTokenExpiresAt", "2026-08-11T13:00:00Z")
        .put("refreshToken", "new-refresh")
        .put("refreshTokenExpiresAt", "2026-09-11T12:00:00Z")
        .put(
            "operator",
            JSONObject()
                .put("id", 1)
                .put("email", "operator@example.invalid")
                .put("displayName", "Operator fixture")
                .put("codexOperationsRole", "PLATFORM_ADMINISTRATOR")
        )
        .toString()
}

private fun JSONArray.strings(): List<String> = List(length()) { index -> getString(index) }

private fun MockWebServer.baseUrl(): String = url("/").toString().trimEnd('/')

private fun jsonResponse(status: Int, body: String): MockResponse = MockResponse()
    .setResponseCode(status)
    .setHeader("Content-Type", "application/json")
    .setBody(body)

private inline fun withServer(block: (MockWebServer) -> Unit) {
    val server = MockWebServer()
    server.start()
    try {
        block(server)
    } finally {
        server.shutdown()
    }
}
