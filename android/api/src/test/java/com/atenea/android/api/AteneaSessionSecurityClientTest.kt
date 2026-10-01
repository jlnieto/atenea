package com.atenea.android.api

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class AteneaSessionSecurityClientTest {
    @Test
    fun `family refresh payload matches the backend shared contract without metadata`() {
        val contract = JSONObject(javaClass.getResource("/android-family-refresh-v1.json")!!.readText())
        assertEquals(contract.toMap(), buildFamilyRefreshBody("refresh-1").toMap())
    }

    @Test
    fun `login refresh and logout declare family protocol and single flight`() = withServer { server ->
        server.enqueue(jsonResponse(200, authJson("access-1", "refresh-1")))
        server.enqueue(jsonResponse(200, authJson("access-2", "refresh-2")))
        server.enqueue(MockResponse().setResponseCode(204))
        val client = AteneaApiClient(server.baseUrl(), { null })

        runBlocking {
            client.login("operator@example.invalid", "synthetic-password")
            client.refresh("refresh-1")
            client.logout("refresh-2")
        }

        val requests = List(3) { server.takeRequest(2, TimeUnit.SECONDS)!! }
        val bodies = requests.map { JSONObject(it.body.readUtf8()) }
        bodies.forEach { json ->
            assertEquals("FAMILY_V1", json.getString("sessionProtocolVersion"))
            assertTrue(json.getBoolean("singleFlightRefresh"))
        }
        assertEquals("ANDROID", bodies[0].getString("clientType"))
        assertFalse(bodies[1].has("clientType"))
        assertFalse(bodies[1].has("deviceLabel"))
        val contract = JSONObject(javaClass.getResource("/android-family-refresh-v1.json")!!.readText())
        assertEquals(contract.toMap(), bodies[1].toMap())
    }

    @Test
    fun `screen and background clients share rotation even with a late old 401`() = withServer { server ->
        val firstWave = CountDownLatch(2)
        val renewed = CountDownLatch(1)
        val requests = AtomicInteger()
        val rotations = AtomicInteger()
        val tokens = AtomicReference("old-access" to "old-refresh")
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/api/mobile/auth/sessions" -> {
                    val number = requests.incrementAndGet()
                    if (number <= 2) {
                        firstWave.countDown()
                        check(firstWave.await(3, TimeUnit.SECONDS))
                        if (number == 2) check(renewed.await(3, TimeUnit.SECONDS))
                        jsonResponse(401, "{\"message\":\"expired access\"}")
                    } else {
                        assertEquals("Bearer new-access", request.getHeader("Authorization"))
                        jsonResponse(200, "[]")
                    }
                }
                "/api/mobile/auth/refresh" -> {
                    rotations.incrementAndGet()
                    jsonResponse(200, authJson("new-access", "new-refresh"))
                }
                else -> MockResponse().setResponseCode(404)
            }
        }
        fun client() = AteneaApiClient(server.baseUrl(),
            { tokens.get().first }, { tokens.get().second },
            sessionUpdater = {
                tokens.set(it.accessToken to it.refreshToken)
                renewed.countDown()
            })
        runBlocking {
            listOf(async { client().fetchOperatorSessions() },
                async { client().fetchOperatorSessions() }).awaitAll()
        }
        assertEquals(1, rotations.get())
        assertEquals(4, requests.get())
    }

    @Test
    fun `two access expiries renew transparently across client recreation`() = withServer { server ->
        val tokens = AtomicReference("access-0" to "refresh-0")
        val expiredAccess = AtomicReference("access-0")
        val generation = AtomicInteger()
        val contract = JSONObject(javaClass.getResource("/android-family-refresh-v1.json")!!.readText())
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/api/mobile/auth/sessions" -> if (
                    request.getHeader("Authorization") == "Bearer ${expiredAccess.get()}") {
                    jsonResponse(401, "{\"message\":\"expired access\"}")
                } else jsonResponse(200, "[]")
                "/api/mobile/auth/refresh" -> {
                    val body = JSONObject(request.body.readUtf8())
                    val expected = JSONObject(contract.toString())
                        .put("refreshToken", "refresh-${generation.get()}")
                    if (body.toMap() != expected.toMap()) {
                        jsonResponse(401, "{\"message\":\"Session metadata requires family adoption\"}")
                    } else {
                        val next = generation.incrementAndGet()
                        jsonResponse(200, authJson("access-$next", "refresh-$next"))
                    }
                }
                else -> MockResponse().setResponseCode(404)
            }
        }
        repeat(2) {
            expiredAccess.set(tokens.get().first)
            val client = AteneaApiClient(server.baseUrl(),
                { tokens.get().first }, { tokens.get().second },
                sessionUpdater = { tokens.set(it.accessToken to it.refreshToken) })
            runBlocking { assertEquals(emptyList(), client.fetchOperatorSessions()) }
        }
        assertEquals(2, generation.get())
        assertEquals("access-2" to "refresh-2", tokens.get())
    }

    @Test
    fun `temporary refresh error preserves session and is not reported as expiry`() = withServer { server ->
        server.enqueue(jsonResponse(401, "{\"message\":\"expired access\"}"))
        server.enqueue(jsonResponse(503, "{\"message\":\"Servicio temporalmente no disponible\"}"))
        val tokens = AtomicReference("old-access" to "old-refresh")
        val client = AteneaApiClient(server.baseUrl(),
            { tokens.get().first }, { tokens.get().second },
            sessionUpdater = { tokens.set(it.accessToken to it.refreshToken) })
        val failure = assertFailsWith<AteneaApiException> {
            runBlocking { client.fetchOperatorSessions() }
        }
        assertEquals(503, failure.status)
        assertEquals("Servicio temporalmente no disponible", failure.message)
        assertEquals("old-access" to "old-refresh", tokens.get())
    }

    @Test
    fun `protocol rejection is not disguised as session expiry`() = withServer { server ->
        server.enqueue(jsonResponse(401, "{\"message\":\"expired access\"}"))
        server.enqueue(jsonResponse(401, "{\"message\":\"Session metadata requires family adoption\"}"))
        val client = AteneaApiClient(server.baseUrl(), { "old-access" }, { "old-refresh" })
        val failure = assertFailsWith<AteneaApiException> {
            runBlocking { client.fetchOperatorSessions() }
        }
        assertEquals("Session metadata requires family adoption", failure.message)
    }

    @Test
    fun `revoked refresh requires login without calling it a temporary failure`() = withServer { server ->
        server.enqueue(jsonResponse(401, "{\"message\":\"expired access\"}"))
        server.enqueue(jsonResponse(401, "{\"message\":\"Session is revoked\"}"))
        val client = AteneaApiClient(server.baseUrl(), { "old-access" }, { "old-refresh" })
        val failure = assertFailsWith<AteneaApiException> {
            runBlocking { client.fetchOperatorSessions() }
        }
        assertEquals(401, failure.status)
        assertTrue(failure.message.contains("revocado"))
    }

    @Test
    fun `a refresh completed after logout cannot restore the stored session`() = withServer { server ->
        val tokens = AtomicReference<Pair<String, String>?>("old-access" to "old-refresh")
        val updates = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/api/mobile/auth/sessions" -> jsonResponse(401, "{\"message\":\"expired access\"}")
                "/api/mobile/auth/refresh" -> {
                    // The store is cleared while rotation is in flight.
                    tokens.set(null)
                    jsonResponse(200, authJson("new-access", "new-refresh"))
                }
                else -> MockResponse().setResponseCode(404)
            }
        }
        val client = AteneaApiClient(server.baseUrl(),
            { tokens.get()?.first }, { tokens.get()?.second },
            sessionUpdater = {
                updates.incrementAndGet()
                tokens.set(it.accessToken to it.refreshToken)
            })
        val failure = assertFailsWith<AteneaApiException> {
            runBlocking { client.fetchOperatorSessions() }
        }
        assertEquals(401, failure.status)
        assertEquals(null, tokens.get())
        assertEquals(0, updates.get())
    }

    @Test
    fun `concurrent 401 responses share exactly one refresh`() = withServer { server ->
        val firstWave = CountDownLatch(2)
        val requestCount = AtomicInteger()
        val refreshCount = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/api/mobile/auth/sessions" -> {
                    val number = requestCount.incrementAndGet()
                    if (number <= 2) {
                        firstWave.countDown()
                        firstWave.await(3, TimeUnit.SECONDS)
                        jsonResponse(401, "{\"message\":\"expired\"}")
                    } else {
                        jsonResponse(200, "[]")
                    }
                }
                "/api/mobile/auth/refresh" -> {
                    refreshCount.incrementAndGet()
                    jsonResponse(200, authJson("new-access", "new-refresh"))
                }
                else -> MockResponse().setResponseCode(404)
            }
        }
        var access = "old-access"
        var refresh = "old-refresh"
        val client = AteneaApiClient(
            baseUrl = server.baseUrl(),
            accessTokenProvider = { access },
            refreshTokenProvider = { refresh },
            sessionUpdater = {
                access = it.accessToken
                refresh = it.refreshToken
            }
        )

        runBlocking {
            listOf(
                async { client.fetchOperatorSessions() },
                async { client.fetchOperatorSessions() }
            ).awaitAll()
        }

        assertEquals(1, refreshCount.get())
        assertEquals(4, requestCount.get())
        assertEquals("new-access", access)
        assertEquals("new-refresh", refresh)
    }

    @Test
    fun `inventory and remote revocation preserve exact public family identity`() = withServer { server ->
        val familyId = "00000000-0000-4000-8000-000000000099"
        server.enqueue(jsonResponse(200, JSONArray().put(JSONObject()
            .put("familyId", familyId)
            .put("clientType", "ANDROID")
            .put("deviceLabel", "Teléfono sintético")
            .put("createdAt", "2026-08-13T09:00:00Z")
            .put("lastUsedAt", "2026-08-13T10:00:00Z")
            .put("absoluteExpiresAt", "2026-09-13T09:00:00Z")
            .put("state", "ACTIVE")
            .put("current", false)).toString()))
        server.enqueue(MockResponse().setResponseCode(204))
        val client = AteneaApiClient(server.baseUrl(), { "access" })

        val sessions = runBlocking {
            val result = client.fetchOperatorSessions()
            client.revokeOperatorSession(result.single().familyId)
            result
        }

        assertEquals("Teléfono sintético", sessions.single().deviceLabel)
        assertEquals("/api/mobile/auth/sessions", server.takeRequest().path)
        val revoke = server.takeRequest()
        assertEquals("/api/mobile/auth/sessions/$familyId", revoke.path)
        assertEquals("DELETE", revoke.method)
    }

    private fun authJson(access: String, refresh: String) = JSONObject()
        .put("accessToken", access)
        .put("accessTokenExpiresAt", "2099-01-01T00:00:00Z")
        .put("refreshToken", refresh)
        .put("refreshTokenExpiresAt", "2099-02-01T00:00:00Z")
        .put("operator", JSONObject()
            .put("id", 1)
            .put("email", "operator@example.invalid")
            .put("displayName", "Operador sintético")
            .put("codexOperationsRole", "PLATFORM_ADMINISTRATOR"))
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
