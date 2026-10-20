package com.atenea.android.api

import org.json.JSONArray
import org.json.JSONObject
import kotlin.test.*

class ChangeValidationWorkflowParserTest {
    @Test fun `queued server intent is active even before its first check exists`() {
        val parsed = parseChangeValidationWorkflow(workflow())
        assertTrue(parsed.running); assertEquals("QUEUED", parsed.phase); assertNull(parsed.activeOperationId)
    }
    @Test fun `only closed states phases and durable timestamps are accepted`() {
        val variants = listOf(workflow().put("state", "ARBITRARY"), workflow().put("phase", "EXEC_COMMAND"),
            workflow().put("state", "SUCCEEDED"), workflow().put("phase", "MONITORING"),
            workflow().put("updatedAt", "invalid"), workflow().put("errorCode", "/tmp/arbitrary"),
            workflow().put("sourceFingerprintSha256", "foreign"),
            workflow().put("state", "FAILED").put("finishedAt", "2026-10-20T10:00:30Z"))
        variants.forEach { assertFails { parseChangeValidationWorkflow(it) } }
    }
    @Test fun `workflow must belong to the same session revision and tree as its evidence`() {
        for (w in listOf(workflow().put("workSessionId", 99), workflow().put("sourceRevision", 4),
                workflow().put("sourceFingerprintSha256", "5".repeat(64)))) {
            assertFails { parseDevelopmentChangeValidationEvidence(evidence().put("workflow", w)) }
        }
    }
    @Test fun `matching intent survives evidence parsing and legacy missing intent remains unknown`() {
        val parsed = parseDevelopmentChangeValidationEvidence(evidence().put("workflow", workflow()))
        assertEquals("RUNNING", parsed.workflow!!.state)
        assertNull(parseDevelopmentChangeValidationEvidence(evidence()).workflow)
    }
    private fun workflow() = JSONObject().put("id", "00000000-0000-4000-8000-000000000002")
        .put("workSessionId", 26).put("sourceRevision", 3).put("sourceFingerprintSha256", "4".repeat(64))
        .put("state", "RUNNING").put("phase", "QUEUED").put("activeOperationId", JSONObject.NULL)
        .put("errorCode", JSONObject.NULL).put("startedAt", "2026-10-20T10:00:00Z")
        .put("updatedAt", "2026-10-20T10:00:30Z").put("finishedAt", JSONObject.NULL)
    private fun evidence() = JSONObject().put("changeKey", "00000000-0000-4000-8000-000000000001")
        .put("workSessionId", 26).put("sourceRevision", 3).put("sourceFingerprintSha256", "4".repeat(64))
        .put("validationState", "STALE").put("passedOperations", 0).put("requiredOperations", 4)
        .put("operations", JSONArray()).put("lastAttempt", JSONObject.NULL)
}
