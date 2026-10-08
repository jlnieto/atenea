package com.atenea.android.api

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AteneaApiClientDevelopmentChangeTest {

    @Test
    fun `existing changes are listed read only with their bound session`() = withServer { server ->
        val changeKey = UUID.fromString("59315b6e-59bc-4884-9def-356e1ca86ef4")
        server.enqueue(jsonResponse(200, JSONArray().put(
            JSONObject()
                .put("changeKey", changeKey.toString())
                .put("projectId", 1)
                .put("title", "Monitorizar degradación de Apache cada 5 minutos")
                .put("status", "OPEN")
                .put("workspaceState", "READY")
                .put("activeSessionId", 21)
                .put("primaryAction", JSONObject().put("kind", "CONTINUE_SESSION").put("label", "Continuar sesión"))
                .put("updatedAt", "2026-09-19T21:29:59Z")
        ).toString()))
        val client = AteneaApiClient(server.baseUrl(), { "access-token" })

        val changes = runBlocking { client.fetchDevelopmentChanges(1) }

        assertEquals(1, changes.size)
        assertEquals(changeKey, changes.single().changeKey)
        assertEquals(1L, changes.single().projectId)
        assertEquals(21L, changes.single().activeSessionId)
        assertEquals("OPEN", changes.single().status)
        assertEquals("Continuar sesión", changes.single().primaryActionLabel)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/v2/projects/1/development-changes", request.path)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a change from another project cannot become a navigation target`() = withServer { server ->
        server.enqueue(jsonResponse(200, JSONArray().put(
            JSONObject()
                .put("changeKey", "59315b6e-59bc-4884-9def-356e1ca86ef4")
                .put("projectId", 2)
                .put("title", "Otro proyecto")
                .put("status", "OPEN")
                .put("workspaceState", "READY")
                .put("activeSessionId", 21)
        ).toString()))
        val client = AteneaApiClient(server.baseUrl(), { "access-token" })

        assertFailsWith<IllegalArgumentException> {
            runBlocking { client.fetchDevelopmentChanges(1) }
        }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `new change creates provisions refreshes and opens before returning session`() =
        withServer { server ->
            val changeKey = UUID.fromString("00000000-0000-0000-0000-000000000041")
            val keys = requestKeys()
            server.enqueue(jsonResponse(201, createdJson(changeKey)))
            server.enqueue(jsonResponse(200, provisionJson("SUCCEEDED", "READY")))
            server.enqueue(jsonResponse(200, detailJson(changeKey, version = 3, workspaceState = "READY")))
            server.enqueue(jsonResponse(200, sessionJson(sessionId = 91, state = "SUCCEEDED", sessionState = "OPEN")))
            val client = AteneaApiClient(server.baseUrl(), { "access-token" })

            val result = runBlocking {
                client.startNewDevelopmentChange(7, "  Mejora movil  ", keys)
            }

            assertEquals(changeKey, result.changeKey)
            assertEquals(91L, result.sessionId)

            val create = server.takeRequest()
            assertEquals("POST", create.method)
            assertEquals("/api/v2/projects/7/development-changes", create.path)
            assertEquals(keys.create.toString(), create.getHeader("Idempotency-Key"))
            assertEquals("Mejora movil", JSONObject(create.body.readUtf8()).getString("title"))

            val provision = server.takeRequest()
            assertEquals("POST", provision.method)
            assertEquals(
                "/api/v2/projects/7/development-changes/$changeKey/workspace/provision",
                provision.path
            )
            assertEquals(keys.provision.toString(), provision.getHeader("Idempotency-Key"))

            val detail = server.takeRequest()
            assertEquals("GET", detail.method)
            assertEquals("/api/v2/projects/7/development-changes/$changeKey", detail.path)

            val open = server.takeRequest()
            assertEquals("POST", open.method)
            assertEquals(
                "/api/v2/projects/7/development-changes/$changeKey/work-session:open-or-resolve",
                open.path
            )
            assertEquals(keys.openSession.toString(), open.getHeader("Idempotency-Key"))
            assertEquals(3L, JSONObject(open.body.readUtf8()).getLong("expectedChangeRevision"))
        }

    @Test
    fun `workspace failure stops before refresh or session and reports provisioning stage`() =
        withServer { server ->
            val changeKey = UUID.fromString("00000000-0000-0000-0000-000000000042")
            server.enqueue(jsonResponse(201, createdJson(changeKey)))
            server.enqueue(
                jsonResponse(
                    200,
                    provisionJson(
                        state = "BLOCKED",
                        workspaceState = "BLOCKED",
                        failureCode = "WORKER_UNAVAILABLE"
                    )
                )
            )
            val client = AteneaApiClient(server.baseUrl(), { "access-token" })

            val error = assertFailsWith<NewDevelopmentChangeException> {
                runBlocking { client.startNewDevelopmentChange(7, "Cambio bloqueado", requestKeys()) }
            }

            assertEquals(NewDevelopmentChangeStage.PROVISION, error.stage)
            assertEquals(2, server.requestCount)
        }

    private fun requestKeys() = NewDevelopmentChangeRequestKeys(
        create = UUID.fromString("00000000-0000-0000-0000-000000000001"),
        provision = UUID.fromString("00000000-0000-0000-0000-000000000002"),
        openSession = UUID.fromString("00000000-0000-0000-0000-000000000003")
    )

    private fun createdJson(changeKey: UUID): String = JSONObject()
        .put(
            "developmentChange",
            JSONObject()
                .put("changeKey", changeKey.toString())
                .put("version", 0)
        )
        .toString()

    private fun provisionJson(
        state: String,
        workspaceState: String,
        failureCode: String? = null
    ): String = JSONObject()
        .put("state", state)
        .put("workspaceState", workspaceState)
        .put("failureCode", failureCode ?: JSONObject.NULL)
        .toString()

    private fun detailJson(changeKey: UUID, version: Long, workspaceState: String): String =
        JSONObject()
            .put("changeKey", changeKey.toString())
            .put("version", version)
            .put("workspaceState", workspaceState)
            .toString()

    private fun sessionJson(sessionId: Long, state: String, sessionState: String): String =
        JSONObject()
            .put("state", state)
            .put("sessionId", sessionId)
            .put("sessionState", sessionState)
            .toString()
}

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
