package com.atenea.android.api

import org.json.JSONArray
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertNotEquals

class DevelopmentChangeValidationEvidenceParserTest {
    @Test
    fun `persisted failure keeps exact phase code exit and identity`() {
        val result = parseDevelopmentChangeValidationEvidence(evidence())
        assertEquals("BLOCKED", result.validationState)
        assertEquals("BACKEND_TEST", result.operations.single().operation)
        assertEquals(0, result.passedOperations)
        assertEquals("TEST_DATABASE_SETUP_FAILED", result.lastAttempt?.errorCode)
        assertEquals(2, result.lastAttempt?.exitCode)
        assertEquals(result.operations.single().id, result.lastAttempt?.id)
    }

    @Test
    fun `older source attempt stays visible but does not count as current validation`() {
        val old = attempt().put("sourceTreeFingerprintSha256", "5".repeat(64))
        val json = evidence().put("operations", JSONArray()).put("lastAttempt", old)
        val result = parseDevelopmentChangeValidationEvidence(json)
        assertEquals(0, result.passedOperations)
        assertNotEquals(result.sourceFingerprintSha256, result.lastAttempt?.sourceTreeFingerprintSha256)
    }

    @Test
    fun `generic historical summary must not invent an error code`() {
        val result = parseDevelopmentChangeValidationEvidence(evidence().put("lastAttempt",
            attempt().put("summary", "Closed validation failed")))
        assertNull(result.lastAttempt?.errorCode)
    }

    @Test
    fun `foreign operation is rejected even if envelope has expected session`() {
        assertFailsWith<IllegalArgumentException> {
            parseDevelopmentChangeValidationEvidence(evidence().put("lastAttempt", attempt().put("workSessionId", 22)))
        }
    }

    @Test
    fun `wrong tree duplicate kind and incorrect pass count are rejected`() {
        val variants = listOf(
            evidence().put("operations", JSONArray().put(attempt().put("sourceTreeFingerprintSha256", "5".repeat(64)))),
            evidence().put("operations", JSONArray().put(attempt()).put(attempt())),
            evidence().put("passedOperations", 4),
            evidence().put("operations", JSONArray().put(attempt().put("operation", "ARBITRARY_COMMAND")))
        )
        variants.forEach { json ->
            assertFailsWith<IllegalArgumentException> { parseDevelopmentChangeValidationEvidence(json) }
        }
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

}
