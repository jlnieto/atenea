package com.atenea.android.api

import java.security.MessageDigest
import java.util.UUID
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MobileDiagnosticReceiptTest {
    @Test fun `receipt exposes only stable authenticated reference and checksum`() {
        val receipt = parseMobileDiagnosticReceipt(receiptJson())
        assertEquals(142, receipt.appVersionCode)
        assertEquals("/api/mobile/diagnostics/${receipt.id}/content", receipt.contentPath)
        receipt.verifyContent(reportBytes())
    }

    @Test fun `foreign paths and arbitrary host paths cannot be used as download authority`() {
        for (path in listOf("https://other.test/secret", "/srv/arbitrary/report.json",
                "/api/mobile/diagnostics/${UUID.randomUUID()}/content", "../../report.json")) {
            assertFailsWith<IllegalArgumentException> {
                parseMobileDiagnosticReceipt(receiptJson().put("contentPath", path))
            }
        }
    }

    @Test fun `malformed size version checksum and timestamps are rejected`() {
        for ((field, value) in listOf("sizeBytes" to 0, "sizeBytes" to 4 * 1024 * 1024 + 1,
                "appVersionCode" to 0, "sha256" to "not-a-checksum", "receivedAt" to "not-a-date")) {
            assertFailsWith<RuntimeException> { parseMobileDiagnosticReceipt(receiptJson().put(field, value)) }
        }
    }

    @Test fun `changed uploaded or downloaded bytes cannot be claimed as verified receipt`() {
        val receipt = parseMobileDiagnosticReceipt(receiptJson())
        assertFailsWith<IllegalArgumentException> { receipt.verifyContent("changed".toByteArray()) }
        assertFailsWith<IllegalArgumentException> {
            receipt.copy(sha256 = "0".repeat(64)).verifyContent(reportBytes())
        }
    }

    companion object {
        private val reportId = UUID.fromString("0cc7815a-f703-46ee-938a-8ef4d00e68a2")
        fun reportBytes() = "{\"synthetic\":true}".toByteArray(Charsets.UTF_8)
        fun receiptJson(): JSONObject = JSONObject()
            .put("id", reportId.toString()).put("receivedAt", "2026-10-07T10:00:01Z")
            .put("generatedAt", "2026-10-07T10:00:00Z").put("appVersionName", "0.5.109")
            .put("appVersionCode", 142).put("deviceModel", "SM-M135F").put("sizeBytes", reportBytes().size)
            .put("sha256", MessageDigest.getInstance("SHA-256").digest(reportBytes())
                .joinToString("") { "%02x".format(it.toInt() and 0xff) })
            .put("contentPath", "/api/mobile/diagnostics/$reportId/content")
    }
}
