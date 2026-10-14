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
    fun `abandoned history exposes archived conversation but cannot be active`() = withServer { server ->
        val value = JSONObject().put("changeKey", UUID.randomUUID().toString()).put("projectId", 1)
            .put("title", "Old test").put("status", "ABANDONED").put("workspaceState", "READY")
            .put("archivedSessionId", 20).put("activeSessionId", JSONObject.NULL)
        server.enqueue(jsonResponse(200, JSONArray().put(value).toString()))
        val client = AteneaApiClient(server.baseUrl(), { "access-token" })
        assertEquals(20L, runBlocking { client.fetchDevelopmentChanges(1) }.single().archivedSessionId)
        value.put("activeSessionId", 19)
        server.enqueue(jsonResponse(200, JSONArray().put(value).toString()))
        assertFailsWith<IllegalArgumentException> { runBlocking { client.fetchDevelopmentChanges(1) } }
    }

    @Test
    fun `retirement uses only session identity and idempotency key without commands`() = withServer { server ->
        val key = UUID.randomUUID()
        val value = JSONObject().put("operationId", UUID.randomUUID().toString()).put("sessionId", 19)
            .put("idempotencyKey", key.toString()).put("state", "APPLYING")
        server.enqueue(jsonResponse(200, value.toString()))
        val client = AteneaApiClient(server.baseUrl(), { "access-token" })
        assertEquals("APPLYING", runBlocking { client.retireWorkSession(19, key) })
        val request = server.takeRequest()
        assertEquals("/api/mobile/sessions/19/retirement", request.path)
        assertEquals(key.toString(), request.getHeader("Idempotency-Key"))
        assertEquals("{}", request.body.readUtf8())
        value.put("sessionId", 20)
        server.enqueue(jsonResponse(200, value.toString()))
        assertFailsWith<IllegalArgumentException> { runBlocking { client.retireWorkSession(19, key) } }
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `recovering retirement reads and reuses original durable key`() = withServer { server ->
        val key = UUID.randomUUID()
        val value = JSONObject().put("operationId", UUID.randomUUID().toString()).put("sessionId", 20)
            .put("idempotencyKey", key.toString()).put("state", "BLOCKED")
        server.enqueue(jsonResponse(200, value.toString()))
        value.put("state", "APPLYING")
        server.enqueue(jsonResponse(200, value.toString()))
        val client = AteneaApiClient(server.baseUrl(), { "access-token" })
        runBlocking { client.retireWorkSession(20, client.fetchRetirementKey(20), reconcile = true) }
        assertEquals("GET", server.takeRequest().method)
        val retry = server.takeRequest()
        assertEquals("/api/mobile/sessions/20/retirement/reconcile", retry.path)
        assertEquals(key.toString(), retry.getHeader("Idempotency-Key"))
    }

    @Test
    fun `preparing existing change never creates another change or duplicate prompt`() = withServer { server ->
        val changeKey = UUID.randomUUID()
        val detail = JSONObject().put("projectId", 1).put("changeKey", changeKey.toString()).put("status", "OPEN")
            .put("workspaceState", "NOT_PROVISIONED").put("version", 2)
            .put("primaryAction", action("PROVISION_WORKSPACE"))
        server.enqueue(jsonResponse(200, detail.toString()))
        server.enqueue(jsonResponse(200, provisionJson("SUCCEEDED", "READY")))
        detail.put("workspaceState", "READY").put("version", 3).put("primaryAction", action("BIND_SESSION"))
        server.enqueue(jsonResponse(200, detail.toString()))
        server.enqueue(jsonResponse(200, sessionJson(91, "SUCCEEDED", "OPEN")))
        server.enqueue(jsonResponse(200, JSONObject().put("id", 91).put("projectId", 1)
            .put("developmentChangeKey", changeKey.toString()).put("status", "OPEN").toString()))
        val client = AteneaApiClient(server.baseUrl(), { "access-token" })
        assertEquals(91L, runBlocking { client.prepareExistingDevelopmentChange(1, changeKey, UUID.randomUUID(), UUID.randomUUID()) })
        assertEquals("GET", server.takeRequest().method)
        assertEquals("/api/v2/projects/1/development-changes/$changeKey/workspace/provision", server.takeRequest().path)
        assertEquals("GET", server.takeRequest().method)
        val open = server.takeRequest()
        assertEquals("/api/v2/projects/1/development-changes/$changeKey/work-session:open-or-resolve", open.path)
        assertEquals(3, JSONObject(open.body.readUtf8()).getInt("expectedChangeRevision"))
        assertEquals("/api/sessions/91", server.takeRequest().path)
        assertEquals(5, server.requestCount)
    }

    @Test
    fun `ready change opens its conversation without provisioning or creating another ticket`() = withServer { server ->
        val key = UUID.randomUUID()
        val sessionKey = UUID.randomUUID()
        enqueueReadyConversation(server, key)
        val client = AteneaApiClient(server.baseUrl(), { "access-token" })
        assertEquals(91L, runBlocking { client.prepareExistingDevelopmentChange(1, key, UUID.randomUUID(), sessionKey) })
        assertEquals("GET", server.takeRequest().method)
        val open = server.takeRequest()
        assertEquals("/api/v2/projects/1/development-changes/$key/work-session:open-or-resolve", open.path)
        assertEquals(sessionKey.toString(), open.getHeader("Idempotency-Key"))
        assertEquals(setOf("expectedChangeRevision"), JSONObject(open.body.readUtf8()).keys().asSequence().toSet())
        assertEquals("/api/sessions/91", server.takeRequest().path)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `blocked change verifies absence then prepares and opens the same conversation`() = withServer { server ->
        val key = UUID.randomUUID()
        val provisionKey = UUID.randomUUID()
        server.enqueue(jsonResponse(200, existingDetail(key, "BLOCKED", "INSPECT_WORKSPACE").toString()))
        server.enqueue(jsonResponse(200, provisionJson("SUCCEEDED", "NOT_PROVISIONED")))
        server.enqueue(jsonResponse(200, existingDetail(key, "NOT_PROVISIONED", "PROVISION_WORKSPACE").toString()))
        server.enqueue(jsonResponse(200, provisionJson("SUCCEEDED", "READY")))
        enqueueReadyConversation(server, key)
        val client = AteneaApiClient(server.baseUrl(), { "access-token" })
        assertEquals(91L, runBlocking { client.prepareExistingDevelopmentChange(1, key, provisionKey, UUID.randomUUID()) })
        assertEquals("GET", server.takeRequest().method)
        val inspect = server.takeRequest()
        assertEquals("/api/v2/projects/1/development-changes/$key/workspace/inspect", inspect.path)
        assertEquals(UUID.nameUUIDFromBytes("inspect-workspace:$provisionKey".toByteArray()).toString(), inspect.getHeader("Idempotency-Key"))
        assertEquals("{}", inspect.body.readUtf8())
        assertEquals("GET", server.takeRequest().method)
        val provision = server.takeRequest()
        assertEquals("/api/v2/projects/1/development-changes/$key/workspace/provision", provision.path)
        assertEquals(provisionKey.toString(), provision.getHeader("Idempotency-Key"))
        assertEquals("{}", provision.body.readUtf8())
        assertEquals("GET", server.takeRequest().method)
        assertEquals("/api/v2/projects/1/development-changes/$key/work-session:open-or-resolve", server.takeRequest().path)
        assertEquals("/api/sessions/91", server.takeRequest().path)
        assertEquals(7, server.requestCount)
    }

    @Test
    fun `uncertain preparation reconciles original intent without another provision`() = withServer { server ->
        val key = UUID.randomUUID()
        server.enqueue(jsonResponse(200, existingDetail(key, "UNCERTAIN", "RECONCILE_WORKSPACE").toString()))
        server.enqueue(jsonResponse(200, provisionJson("SUCCEEDED", "READY")))
        enqueueReadyConversation(server, key)
        val client = AteneaApiClient(server.baseUrl(), { "access-token" })
        assertEquals(91L, runBlocking { client.prepareExistingDevelopmentChange(1, key, UUID.randomUUID(), UUID.randomUUID()) })
        assertEquals("GET", server.takeRequest().method)
        val reconcile = server.takeRequest()
        assertEquals("/api/v2/projects/1/development-changes/$key/workspace/reconcile", reconcile.path)
        assertEquals(null, reconcile.getHeader("Idempotency-Key"))
        assertEquals("{}", reconcile.body.readUtf8())
        assertEquals("GET", server.takeRequest().method)
        assertEquals("/api/v2/projects/1/development-changes/$key/work-session:open-or-resolve", server.takeRequest().path)
        assertEquals("/api/sessions/91", server.takeRequest().path)
        assertEquals(5, server.requestCount)
    }

    @Test
    fun `foreign or still uncertain workspace stops before provisioning or opening`() = withServer { server ->
        for (state in listOf("BLOCKED", "UNCERTAIN")) {
            val key = UUID.randomUUID()
            server.enqueue(jsonResponse(200, existingDetail(key, state,
                if (state == "BLOCKED") "INSPECT_WORKSPACE" else "RECONCILE_WORKSPACE").toString()))
            server.enqueue(jsonResponse(200, provisionJson(state, state, "OWNERSHIP_MISMATCH")))
            val client = AteneaApiClient(server.baseUrl(), { "access-token" })
            assertFailsWith<IllegalArgumentException> {
                runBlocking { client.prepareExistingDevelopmentChange(1, key, UUID.randomUUID(), UUID.randomUUID()) }
            }
            assertEquals("GET", server.takeRequest().method)
            assertEquals("POST", server.takeRequest().method)
        }
        assertEquals(4, server.requestCount)
    }

    @Test
    fun `disabled or mismatched server actions cannot be overridden by continuation`() = withServer { server ->
        for ((state, kind, enabled) in listOf(Triple("BLOCKED", "INSPECT_WORKSPACE", false),
            Triple("UNCERTAIN", "INSPECT_WORKSPACE", true), Triple("NOT_PROVISIONED", "PROVISION_WORKSPACE", false),
            Triple("READY", "BIND_SESSION", false))) {
            val key = UUID.randomUUID()
            val detail = existingDetail(key, state, kind).put("primaryAction", action(kind, enabled))
            server.enqueue(jsonResponse(200, detail.toString()))
            val client = AteneaApiClient(server.baseUrl(), { "access-token" })
            assertFailsWith<IllegalArgumentException> {
                runBlocking { client.prepareExistingDevelopmentChange(1, key, UUID.randomUUID(), UUID.randomUUID()) }
            }
            assertEquals("GET", server.takeRequest().method)
        }
        assertEquals(4, server.requestCount)
    }

    @Test
    fun `already opened conversation is verified and reused without a second open`() = withServer { server ->
        val key = UUID.randomUUID()
        val detail = existingDetail(key, "READY", "CONTINUE_SESSION").put("activeSessionId", 91).put("activeSessionStatus", "OPEN")
        repeat(2) {
            server.enqueue(jsonResponse(200, detail.toString()))
            server.enqueue(jsonResponse(200, ownedSession(key).toString()))
        }
        val client = AteneaApiClient(server.baseUrl(), { "access-token" })
        repeat(2) { assertEquals(91L, runBlocking { client.prepareExistingDevelopmentChange(1, key, UUID.randomUUID(), UUID.randomUUID()) }) }
        repeat(4) { assertEquals("GET", server.takeRequest().method) }
        assertEquals(4, server.requestCount)
    }

    @Test
    fun `lost open response resolves bound conversation without repeating the effect`() = withServer { server ->
        val key = UUID.randomUUID()
        server.enqueue(jsonResponse(200, existingDetail(key, "READY", "BIND_SESSION").toString()))
        server.enqueue(jsonResponse(500, "{}"))
        server.enqueue(jsonResponse(200, existingDetail(key, "READY", "CONTINUE_SESSION")
            .put("activeSessionId", 91).put("activeSessionStatus", "OPEN").toString()))
        server.enqueue(jsonResponse(200, ownedSession(key).toString()))
        val client = AteneaApiClient(server.baseUrl(), { "access-token" })
        val provisionKey = UUID.randomUUID()
        val sessionKey = UUID.randomUUID()
        assertFailsWith<Exception> { runBlocking { client.prepareExistingDevelopmentChange(1, key, provisionKey, sessionKey) } }
        assertEquals(91L, runBlocking { client.prepareExistingDevelopmentChange(1, key, provisionKey, sessionKey) })
        assertEquals("GET", server.takeRequest().method)
        assertEquals("POST", server.takeRequest().method)
        repeat(2) { assertEquals("GET", server.takeRequest().method) }
        assertEquals(4, server.requestCount)
    }

    @Test
    fun `closing or cross owned conversation is never a continuation target`() = withServer { server ->
        val key = UUID.randomUUID()
        server.enqueue(jsonResponse(200, existingDetail(key, "READY", "CONTINUE_SESSION")
            .put("activeSessionId", 91).put("activeSessionStatus", "CLOSING").toString()))
        val client = AteneaApiClient(server.baseUrl(), { "access-token" })
        assertFailsWith<IllegalArgumentException> { runBlocking { client.prepareExistingDevelopmentChange(1, key, UUID.randomUUID(), UUID.randomUUID()) } }
        assertEquals(1, server.requestCount)
        server.takeRequest()
        server.enqueue(jsonResponse(200, existingDetail(key, "READY", "CONTINUE_SESSION")
            .put("activeSessionId", 91).put("activeSessionStatus", "OPEN").toString()))
        server.enqueue(jsonResponse(200, ownedSession(UUID.randomUUID()).toString()))
        assertFailsWith<IllegalArgumentException> { runBlocking { client.prepareExistingDevelopmentChange(1, key, UUID.randomUUID(), UUID.randomUUID()) } }
        assertEquals(3, server.requestCount)
        repeat(2) { assertEquals("GET", server.takeRequest().method) }
    }

    private fun action(kind: String, enabled: Boolean = true) = JSONObject()
        .put("kind", kind).put("enabled", enabled).put("label", "Continuar cambio")

    private fun existingDetail(key: UUID, state: String, kind: String) = JSONObject()
        .put("projectId", 1).put("changeKey", key.toString()).put("status", "OPEN")
        .put("workspaceState", state).put("version", 3).put("primaryAction", action(kind))

    private fun ownedSession(key: UUID) = JSONObject().put("id", 91).put("projectId", 1)
        .put("developmentChangeKey", key.toString()).put("status", "OPEN")

    private fun enqueueReadyConversation(server: MockWebServer, key: UUID) {
        server.enqueue(jsonResponse(200, existingDetail(key, "READY", "BIND_SESSION").toString()))
        server.enqueue(jsonResponse(200, sessionJson(91, "SUCCEEDED", "OPEN")))
        server.enqueue(jsonResponse(200, ownedSession(key).toString()))
    }

    @Test
    fun `completed changes expose only their preserved history session`() = withServer { server ->
        val value=JSONObject().put("changeKey",UUID.randomUUID().toString()).put("projectId",1)
            .put("title","Delivered change").put("status","COMPLETED").put("workspaceState","READY")
            .put("activeSessionId",JSONObject.NULL).put("completedSessionId",21)
        server.enqueue(jsonResponse(200,JSONArray().put(value).toString()))
        val client=AteneaApiClient(server.baseUrl(),{"access-token"})
        assertEquals(21L,runBlocking { client.fetchDevelopmentChanges(1) }.single().completedSessionId)
        assertEquals(1,server.requestCount)
        value.put("status","OPEN")
        server.enqueue(jsonResponse(200,JSONArray().put(value).toString()))
        assertFailsWith<IllegalArgumentException> { runBlocking { client.fetchDevelopmentChanges(1) } }
    }

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
