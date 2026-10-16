package com.atenea.android.api

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import java.util.UUID
import kotlin.test.*

class ValidationRecoveryTest {
    val id = UUID.fromString("a4ff9100-2e8a-46df-911f-53874886701e")
    private fun operation() = JSONObject().put("protocolVersion", "validation-recovery/v1").put("id", id.toString())
        .put("kind", "REPAIR").put("state", "READY").put("sourceRevision", 1).put("runId", JSONObject.NULL).put("errorCode", JSONObject.NULL)
    private fun overview() = JSONObject().put("protocolVersion", "validation-recovery/v1").put("workSessionId", 26)
        .put("changeKey", id.toString()).put("sourceRevision", 1).put("failureCode", "UNSUPPORTED_DEPENDENCY_MANIFEST")
        .put("canPrepareRepair", true).put("canInvestigate", true).put("repair", JSONObject.NULL).put("investigation", JSONObject.NULL)
    private fun withServer(block: (MockWebServer, AteneaApiClient) -> Unit) {
        val server = MockWebServer(); server.start()
        try { block(server, AteneaApiClient(server.url("/").toString().trimEnd('/'), { "synthetic-token" })) }
        finally { server.shutdown() }
    }
    private fun response(value: JSONObject) = MockResponse().setHeader("Content-Type", "application/json").setBody(value.toString())
    @Test fun `consultation is authenticated read and never creates a task`() = withServer { server, client ->
        server.enqueue(response(overview())); assertTrue(runBlocking { client.fetchValidationRecovery(26) }.canPrepareRepair)
        val request = server.takeRequest(); assertEquals("GET", request.method); assertEquals(0L, request.body.size)
        assertEquals("Bearer synthetic-token", request.getHeader("Authorization")); assertEquals(1, server.requestCount)
    }
    @Test fun `actions only send empty bodies to closed scoped endpoints`() = withServer { server, client ->
        val actions: List<Pair<String, suspend () -> ValidationRecoveryOperation>> = listOf(
            "prepare" to { client.prepareValidationRepair(26) }, "$id/apply" to { client.applyValidationRepair(26, id) },
            "$id/recover" to { client.recoverValidationRepair(26, id) }, "$id/cancel" to { client.cancelValidationRepair(26, id) },
            "investigate" to { client.investigateValidation(26) })
        actions.forEach { (path, action) ->
            server.enqueue(response(operation())); runBlocking { action() }
            val request = server.takeRequest(); assertEquals("POST", request.method)
            assertEquals("/api/mobile/sessions/26/validation-recovery/$path", request.path)
            assertEquals(0, JSONObject(request.body.readUtf8()).length())
        }
        assertEquals(actions.size, server.requestCount)
    }
    @Test fun `foreign identity unknown protocol and state are rejected`() {
        assertFailsWith<IllegalArgumentException> { parseValidationRecovery(overview().put("workSessionId", 27), 26) }
        assertFailsWith<IllegalArgumentException> { parseValidationRecovery(overview().put("protocolVersion", "foreign"), 26) }
        assertFailsWith<IllegalArgumentException> { parseValidationRecoveryOperation(operation().put("state", "EXECUTE_SHELL")) }
    }
    @Test fun `missing endpoint never falls back to duplicate prompt or validation`() = withServer { server, client ->
        server.enqueue(MockResponse().setResponseCode(404).setBody("{}"))
        assertFailsWith<AteneaApiException> { runBlocking { client.fetchValidationRecovery(26) } }
        assertEquals(1, server.requestCount)
    }
}
