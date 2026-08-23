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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AteneaSessionSecurityClientTest {
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
