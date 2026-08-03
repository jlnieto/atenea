package com.atenea.android.coreconsole

import com.atenea.android.api.CodexCatalog
import com.atenea.android.api.CodexCatalogModel
import com.atenea.android.api.CodexRecoveryAction
import com.atenea.android.api.CodexRunDetail
import com.atenea.android.api.CodexSettings
import com.atenea.android.api.CodexProgressEvent
import com.atenea.android.api.CodexProgressReplay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CodexConversationOperationsTest {

    @Test
    fun resolvesModelAndEffortSourcesIndependently() {
        val profile = resolveEffectiveCodexProfile(
            catalog(),
            CodexSettings("WORK_SESSION", 41, null, "high"),
            CodexSettings("PROJECT", 7, "gpt-5.6-sol", null)
        )

        assertEquals("gpt-5.6-sol", profile.model.modelId)
        assertEquals("Proyecto", profile.modelSource)
        assertEquals("high", profile.reasoningEffort)
        assertEquals("Sesión", profile.effortSource)
    }

    @Test
    fun rejectsAnEffortOutsideTheEffectiveModel() {
        assertFailsWith<IllegalArgumentException> {
            resolveEffectiveCodexProfile(
                catalog(),
                CodexSettings("WORK_SESSION", 41, "gpt-5.6-sol", "max"),
                null
            )
        }
    }

    @Test
    fun exposesExactlyOneContextualRecoveryAction() {
        assertEquals(CodexRecoveryAction.CANCEL, codexRecoveryAction(detail("RUNNING"), "WAIT", "CHECKING"))
        assertEquals(CodexRecoveryAction.RETRY, codexRecoveryAction(detail("FAILED"), "RETRY", "FAILED"))
        assertEquals(CodexRecoveryAction.RECONCILE, codexRecoveryAction(detail("FAILED"), "REQUEST_RECONCILIATION", "FAILED"))
        assertEquals(null, codexRecoveryAction(detail("SUCCEEDED"), "NONE", "COMPLETED"))
    }

    @Test
    fun formatsBoundedOperatorStateWithoutPayloadValues() {
        assertEquals("1 min 24 s", formatCodexElapsed(84_000))
        assertEquals("Comprobando cambios", codexProgressLabel("CHECKING"))
        assertEquals("Contactar con un operador autorizado", codexNextActionLabel("CONTACT_PRIVILEGED_OPERATOR"))
    }

    @Test
    fun reconnectMergesOnlyTheDurableGapWithoutDuplicateEvents() {
        val initial = replay(after = 0, events = listOf(event(1), event(2)))
        val gap = replay(after = 2, events = listOf(event(3)))

        val merged = mergeCodexProgressReplay(initial, gap)

        assertEquals(listOf(1L, 2L, 3L), merged.events.map { it.sequence })
        assertEquals(3L, merged.latestObservedSequence())
    }

    @Test
    fun replayBelowRetentionFloorDropsEventsOutsideTheRetainedWindow() {
        val initial = replay(after = 0, events = listOf(event(1), event(2)))
        val retained = replay(after = 2, floor = 4, belowFloor = true, events = listOf(event(4), event(5)))

        val merged = mergeCodexProgressReplay(initial, retained)

        assertEquals(listOf(4L, 5L), merged.events.map { it.sequence })
    }

    private fun catalog() = CodexCatalog(
        workerId = "ax42-01",
        catalogRevision = "a".repeat(64),
        schemaVersion = "codex-model-catalog-v1",
        codexVersion = "0.145.0",
        generatedAt = null,
        observedAt = null,
        models = listOf(
            CodexCatalogModel("gpt-5.6-sol", "GPT-5.6 Sol", "medium", "AVAILABLE", listOf("medium", "high"))
        )
    )

    private fun detail(status: String) = CodexRunDetail(
        runId = 99,
        workSessionId = 41,
        status = status,
        modelId = "gpt-5.6-sol",
        modelSource = "PROJECT",
        reasoningEffort = "high",
        effortSource = "WORK_SESSION",
        catalogRevision = "a".repeat(64),
        codexVersion = "0.145.0",
        currentState = status,
        latestSequence = 2,
        retainedFloor = 1,
        elapsedMillis = 84_000,
        requiredNextAction = null,
        retryOfRunId = null
    )

    private fun event(sequence: Long) = CodexProgressEvent(sequence, "CHECKING", "Comprobando", null)

    private fun replay(
        after: Long,
        floor: Long = 1,
        belowFloor: Boolean = false,
        events: List<CodexProgressEvent>
    ) = CodexProgressReplay(
        requestedAfterSequence = after,
        retainedFloor = floor,
        cursorWasBelowRetainedFloor = belowFloor,
        currentState = "CHECKING",
        latestEvent = events.lastOrNull(),
        terminalOutcome = null,
        elapsedMillis = 1_000,
        requiredNextAction = "WAIT",
        events = events
    )
}
