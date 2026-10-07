package com.atenea.android.api

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MobileDiagnosticApiTest {
    @Test fun `send uses dedicated authenticated diagnostics endpoint without WorkSession or legacy upload`() = withServer { server ->
        server.enqueue(json(MobileDiagnosticReceiptTest.receiptJson().toString(), 201))
        val receipt = runBlocking { client(server).uploadMobileDiagnostic(MobileDiagnosticReceiptTest.reportBytes()) }
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/mobile/diagnostics", request.path)
        assertEquals("Bearer synthetic-access", request.getHeader("Authorization"))
        assertTrue(request.body.readUtf8().contains("application/json"))
        assertEquals(142, receipt.appVersionCode)
        assertEquals(1, server.requestCount)
    }

    @Test fun `latest and recent are authenticated reads with no upload or retry`() = withServer { server ->
        val receipt = MobileDiagnosticReceiptTest.receiptJson()
        server.enqueue(json(receipt.toString()))
        server.enqueue(json("[$receipt]"))
        val api = client(server)
        runBlocking {
            assertEquals(api.fetchLatestMobileDiagnostic(), api.fetchMobileDiagnostics().single())
        }
        for (path in listOf("/api/mobile/diagnostics/latest", "/api/mobile/diagnostics?limit=10")) {
            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals(path, request.path)
            assertEquals("Bearer synthetic-access", request.getHeader("Authorization"))
        }
        assertEquals(2, server.requestCount)
    }

    @Test fun `missing diagnostic endpoint never falls back to legacy upload or conversation`() = withServer { server ->
        server.enqueue(json("{\"message\":\"not found\"}", 404))
        assertFailsWith<AteneaApiException> {
            runBlocking { client(server).uploadMobileDiagnostic(MobileDiagnosticReceiptTest.reportBytes()) }
        }
        assertEquals("/api/mobile/diagnostics", server.takeRequest().path)
        assertEquals(1, server.requestCount)
    }

    @Test fun `wrong persisted checksum is not accepted as successful send`() = withServer { server ->
        server.enqueue(json(MobileDiagnosticReceiptTest.receiptJson().put("sha256", "0".repeat(64)).toString(), 201))
        assertFailsWith<IllegalArgumentException> {
            runBlocking { client(server).uploadMobileDiagnostic(MobileDiagnosticReceiptTest.reportBytes()) }
        }
        assertEquals(1, server.requestCount)
    }

    @Test fun `download verifies bytes and remains authenticated JSON with bounded size`() = withServer { server ->
        val receipt = parseMobileDiagnosticReceipt(MobileDiagnosticReceiptTest.receiptJson())
        server.enqueue(json(String(MobileDiagnosticReceiptTest.reportBytes(), Charsets.UTF_8)))
        val bytes = runBlocking { client(server).downloadMobileDiagnostic(receipt) }
        assertTrue(bytes.contentEquals(MobileDiagnosticReceiptTest.reportBytes()))
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals(receipt.contentPath, request.path)
        assertEquals("Bearer synthetic-access", request.getHeader("Authorization"))
        assertEquals("application/json", request.getHeader("Accept"))
    }

    @Test fun `malicious oversized download is rejected before allocating full body`() = withServer { server ->
        server.enqueue(json("").setHeader("Content-Length", 4 * 1024 * 1024 + 1)
            .setSocketPolicy(SocketPolicy.DISCONNECT_AT_END))
        val receipt = parseMobileDiagnosticReceipt(MobileDiagnosticReceiptTest.receiptJson())
        val error = assertFailsWith<AteneaApiException> {
            runBlocking { client(server).downloadMobileDiagnostic(receipt) }
        }
        assertEquals(413, error.status)
        assertEquals(1, server.requestCount)
    }

    private fun client(server: MockWebServer) = AteneaApiClient(server.url("/").toString().trimEnd('/'), { "synthetic-access" })
    private fun json(body: String, status: Int = 200) = MockResponse().setResponseCode(status)
        .setHeader("Content-Type", "application/json").setBody(body)
    private fun withServer(block: (MockWebServer) -> Unit) {
        val server = MockWebServer()
        server.start()
        try { block(server) } finally { server.shutdown() }
    }
}
