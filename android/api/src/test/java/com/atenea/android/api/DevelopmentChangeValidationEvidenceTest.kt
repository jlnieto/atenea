package com.atenea.android.api

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DevelopmentChangeValidationEvidenceTest {
    @Test
    fun `query is authenticated GET and exposes exact durable failure without retry`() = withServer { server ->
        server.enqueue(response(evidence().toString()))
        val client = AteneaApiClient(server.url("/").toString().trimEnd('/'), { "test-access-token" })

        val result = runBlocking { client.fetchDevelopmentChangeValidationEvidence(21) }

        assertEquals("BLOCKED", result.validationState)
        assertEquals(0, result.passedOperations)
        assertEquals("TEST_DATABASE_SETUP_FAILED", result.lastAttempt?.errorCode)
        assertEquals(2, result.lastAttempt?.exitCode)
        assertEquals(result.operations.single().id, result.lastAttempt?.id)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/sessions/21/validation-evidence", request.path)
        assertEquals("Bearer test-access-token", request.getHeader("Authorization"))
        assertEquals(0L, request.body.size)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `foreign session response is rejected`() = withServer { server ->
        server.enqueue(response(evidence().put("workSessionId", 22).put("operations", JSONArray())
            .put("lastAttempt", JSONObject.NULL).toString()))
        val client = AteneaApiClient(server.url("/").toString().trimEnd('/'), { "test-access-token" })
        assertFailsWith<IllegalArgumentException> {
            runBlocking { client.fetchDevelopmentChangeValidationEvidence(21) }
        }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `missing endpoint never falls back to POST validation`() = withServer { server ->
        server.enqueue(MockResponse().setResponseCode(404).setBody("{}"))
        val client = AteneaApiClient(server.url("/").toString().trimEnd('/'), { "test-access-token" })
        assertFailsWith<AteneaApiException> {
            runBlocking { client.fetchDevelopmentChangeValidationEvidence(21) }
        }
        assertEquals("GET", server.takeRequest().method)
        assertEquals(1, server.requestCount)
    }

    private fun evidence() = JSONObject()
        .put("changeKey", "59315b6e-59bc-4884-9def-356e1ca86ef4")
        .put("workSessionId", 21).put("sourceRevision", 2)
        .put("sourceFingerprintSha256", "4".repeat(64)).put("validationState", "BLOCKED")
        .put("passedOperations", 0).put("requiredOperations", 4)
        .put("operations", JSONArray().put(attempt())).put("lastAttempt", attempt())

    private fun attempt() = JSONObject()
        .put("id", "0cc7815a-f703-46ee-938a-8ef4d00e68a2").put("workSessionId", 21)
        .put("operation", "BACKEND_TEST").put("status", "BLOCKED")
        .put("sourceTreeFingerprintSha256", "4".repeat(64)).put("definitionRevision", "atenea-backend-test-v2")
        .put("exitCode", 2).put("summary", "[TEST_DATABASE_SETUP_FAILED] No se pudo preparar PostgreSQL de pruebas.")
        .put("startedAt", "2026-10-05T10:00:00Z").put("finishedAt", "2026-10-05T10:00:00.100Z")

    private fun response(body: String) = MockResponse().setResponseCode(200)
        .setHeader("Content-Type", "application/json").setBody(body)

    private fun withServer(block: (MockWebServer) -> Unit) {
        val server = MockWebServer()
        server.start()
        try { block(server) } finally { server.shutdown() }
    }
}
