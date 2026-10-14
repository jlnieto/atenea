package com.atenea.android.api

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ChangeHelpTest {
    @Test fun `overview is authenticated GET and never starts a task or validation`() = withServer { server ->
        server.enqueue(response(payload()))
        val client = AteneaApiClient(server.url("/").toString().trimEnd('/'), { "synthetic-token" })
        val result = runBlocking { client.fetchChangeHelp(22) }
        assertEquals(ChangeHelpDestination.CHANGE, result.nextAction.destination)
        val request = server.takeRequest()
        assertEquals("GET", request.method); assertEquals("/api/mobile/sessions/22/help", request.path)
        assertEquals("Bearer synthetic-token", request.getHeader("Authorization"))
        assertEquals(0L, request.body.size); assertEquals(1, server.requestCount)
    }
    @Test fun `question and simplification go only to the help endpoint`() = withServer { server ->
        server.enqueue(response(payload()))
        val client = AteneaApiClient(server.url("/").toString().trimEnd('/'), { "synthetic-token" })
        runBlocking { client.askChangeHelp(22, "No lo entiendo", true) }
        val request = server.takeRequest()
        assertEquals("POST", request.method); assertEquals("/api/mobile/sessions/22/help", request.path)
        assertEquals(setOf("question", "simple"), JSONObject(request.body.readUtf8()).keySet())
        assertEquals(1, server.requestCount)
    }
    @Test fun `foreign session or unrecognized navigation is rejected`() = withServer { server ->
        val client = AteneaApiClient(server.url("/").toString().trimEnd('/'), { "synthetic-token" })
        server.enqueue(response(payload().put("workSessionId", 23)))
        assertFailsWith<IllegalArgumentException> { runBlocking { client.fetchChangeHelp(22) } }
        val unsupported = payload().put("nextAction", JSONObject().put("destination", "EXECUTE_COMMAND").put("label", "x").put("reason", "x"))
        server.enqueue(response(unsupported))
        assertFailsWith<IllegalArgumentException> { runBlocking { client.fetchChangeHelp(22) } }
        assertEquals(2, server.requestCount)
    }
    @Test fun `missing help never falls back to an AgentRun or Core command`() = withServer { server ->
        server.enqueue(MockResponse().setResponseCode(404).setBody("{}"))
        val client = AteneaApiClient(server.url("/").toString().trimEnd('/'), { "synthetic-token" })
        assertFailsWith<AteneaApiException> { runBlocking { client.fetchChangeHelp(22) } }
        assertEquals(1, server.requestCount)
    }
    @Test fun `empty or oversized question does not make a request`() = withServer { server ->
        val client = AteneaApiClient(server.url("/").toString().trimEnd('/'), { "synthetic-token" })
        assertFailsWith<IllegalArgumentException> { runBlocking { client.askChangeHelp(22, " ") } }
        assertFailsWith<IllegalArgumentException> { runBlocking { client.askChangeHelp(22, "x".repeat(1001)) } }
        assertEquals(0, server.requestCount)
    }
    private fun payload() = JSONObject().put("protocolVersion", "change-help/v1").put("workSessionId", 22)
        .put("changeKey", "a4ff9100-2e8a-46df-911f-53874886701e").put("sourceRevision", 1)
        .put("observedAt", "2026-10-14T08:00:00Z").put("stage", "VALIDATION_REQUIRED")
        .put("answer", "El cambio está guardado").put("simpleAnswer", "Valida el cambio")
        .put("facts", JSONArray().put("0/4 comprobaciones"))
        .put("nextAction", JSONObject().put("destination", "CHANGE").put("label", "Ir a Validar cambio").put("reason", "No inicia pruebas"))
        .put("suggestedQuestions", JSONArray().put("Qué hago")).put("explanationSource", "RECORDED_STATE")
    private fun response(value: JSONObject) = MockResponse().setResponseCode(200)
        .setHeader("Content-Type", "application/json").setBody(value.toString())
    private fun withServer(test: (MockWebServer) -> Unit) {
        val server = MockWebServer(); server.start()
        try { test(server) } finally { server.shutdown() }
    }
}
