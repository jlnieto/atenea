package com.atenea.android.coreconsole

import androidx.compose.runtime.saveable.SaverScope
import com.atenea.android.api.AteneaApiException
import com.atenea.android.api.ChangeValidationWorkflow
import com.atenea.android.api.DevelopmentChangeValidationAttempt
import com.atenea.android.api.MobileDeliveryState
import com.atenea.android.api.MobileWorkSession
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class ServerOwnedValidationUiTest {
    private val now = Instant.parse("2026-10-20T10:00:30Z")
    private fun workflow(state: String = "RUNNING", phase: String = "QUEUED") = ChangeValidationWorkflow(
        UUID.fromString("00000000-0000-4000-8000-000000000002"), 26, 3, "3".padStart(64, '0'), state, phase,
        if (phase == "MONITORING") UUID.randomUUID() else null, null, "2026-10-20T10:00:00Z", now.toString(),
        if (state == "RUNNING") null else now.toString())
    private fun session() = MobileWorkSession(26, 1, "Mismo cambio", "OPEN", "IDLE", "main", "atenea/change-test", null,
        "OPEN", null, null, null, null, null, null, null, null, false,
        "00000000-0000-4000-8000-000000000001", "STALE", "DIRTY")

    @Test fun `returning to a fresh screen keeps server validation busy without a local flag`() = runBlocking {
        val evidence = workflowEvidence(26, 3, "STALE", 2).copy(workflow = workflow())
        val state = MobileDeliveryUiState(26, this) { MobileDeliveryState(true, emptyList(), validation = evidence) }
        state.refresh()
        assertFalse(state.validationRequested)
        assertTrue(state.validationRunning)
        val ui = state.validationUi(session(), false)
        assertEquals("Validando…", ui.label)
        assertTrue(ui.inProgress); assertFalse(ui.canStart)
        assertFalse(state.beginValidation())
        assertEquals(DeliveryStepStatus.WORKING, state.currentStep(false, false).status)
    }

    @Test fun `running Android never offers continue validation when its timer is ticking`() = runBlocking {
        val original = workflowEvidence(26, 3, "STALE", 2)
        val active = DevelopmentChangeValidationAttempt(UUID.randomUUID(), 26, "ANDROID_BUILD", "RUNNING",
            original.sourceFingerprintSha256!!, "atenea-android-build-v2", null, "Running", "2026-10-20T10:00:00Z", null,
            workerState = "RUNNING", workerObservedAt = now.toString(), workerStartedAt = "2026-10-20T10:00:01Z")
        val evidence = original.copy(operations = original.operations + active, workflow = workflow(phase = "MONITORING"))
        val state = MobileDeliveryUiState(26, this) { MobileDeliveryState(true, emptyList(), validation = evidence) }
        state.refresh()
        assertEquals("Validando…", state.validationUi(session(), false).label)
        assertFalse(state.validationUi(session(), false).canStart)
        val progress = validationProgressUi(evidence, 26, now, false, true)!!
        assertEquals(ValidationProgressStatus.RUNNING, progress.steps[2].status)
        assertTrue(progress.steps[2].timing!!.contains("29 s"))
    }

    @Test fun `gap between completed checks remains working and explains server preparation`() = runBlocking {
        val evidence = workflowEvidence(26, 3, "STALE", 3).copy(workflow = workflow())
        val state = MobileDeliveryUiState(26, this) { MobileDeliveryState(true, emptyList(), validation = evidence) }
        state.refresh()
        assertTrue(state.validationRunning)
        val progress = validationProgressUi(evidence, 26, now, false, true)!!
        assertTrue(progress.confirmation!!.contains("preparando la siguiente"))
        assertEquals(ValidationProgressStatus.PENDING, progress.steps.last().status)
        assertFalse(state.validationUi(session(), false).canStart)
    }

    @Test fun `four passed checks remain busy until server persists final promotion`() = runBlocking {
        var evidence = workflowEvidence(26, 3, "STALE", 4).copy(workflow = workflow())
        val state = MobileDeliveryUiState(26, this) { MobileDeliveryState(true, emptyList(), validation = evidence) }
        state.refresh(); assertTrue(state.validationRunning); assertFalse(state.validationCurrent)
        evidence = evidence.copy(validationState = "CURRENT", workflow = workflow("SUCCEEDED", "FINISHED"))
        state.refresh(); assertFalse(state.validationRunning); assertTrue(state.validationCurrent)
        assertFalse(state.validationUi(session(), false).canStart)
    }

    @Test fun `only a terminal failed workflow offers an explicit retry`() = runBlocking {
        val evidence = workflowEvidence(26, 3, "BLOCKED", 0).copy(workflow = workflow("FAILED", "FINISHED"))
        val state = MobileDeliveryUiState(26, this) { MobileDeliveryState(true, emptyList(), validation = evidence) }
        state.refresh()
        assertEquals("Reintentar validación", state.validationUi(session(), false).label)
        assertTrue(state.validationUi(session(), false).canStart)
        assertTrue(state.validationUi(session(), false).needsAttention)
        assertFalse(state.validationRunning)
    }

    @Test fun `unavailable worker is waiting for confirmation not a fictitious running check`() {
        val evidence = workflowEvidence(26, 3, "STALE", 0).copy(
            workflow = workflow().copy(errorCode = "VALIDATION_SOURCE_UNCONFIRMED"))
        val progress = validationProgressUi(evidence, 26, now, false, true)!!
        assertTrue(progress.uncertain)
        assertTrue(progress.confirmation!!.contains("Todavía no se admite otra prueba"))
        assertTrue(progress.steps.all { it.status == ValidationProgressStatus.PENDING })
    }

    @Test fun `an active server intent cannot be authorized by an older CURRENT projection`() = runBlocking {
        val evidence = workflowEvidence(26, 3, "CURRENT", 4).copy(workflow = workflow())
        val state = MobileDeliveryUiState(26, this) { MobileDeliveryState(true, emptyList(), validation = evidence) }
        state.refresh()
        assertFalse(state.validationCurrent)
        assertFalse(state.validationUi(session(), false).canStart)
        assertTrue(state.validationUi(session(), false).inProgress)
    }

    @Test fun `admission refusal survives passive manual and foreground style reads without becoming a failed test`() = runBlocking {
        val evidence = workflowEvidence(26, 3, "NOT_STARTED", 0)
        var reads = 0
        val state = MobileDeliveryUiState(26, this) { reads++; MobileDeliveryState(true, emptyList(), validation = evidence) }
        state.refresh(); assertTrue(state.beginValidation())
        val failure = ValidationRequestFailure.capture(evidence, AteneaApiException(422,
            "Acceptance projection does not own the current source tree; validation state was not promoted"))
        state.finishValidation()
        repeat(3) { state.refresh() }; state.consult()
        val ui = state.validationUi(session(), false, failure)
        assertEquals("Volver a solicitar", ui.label); assertTrue(ui.needsAttention); assertTrue(ui.canStart)
        assertFalse(ui.inProgress); assertTrue(ui.message.contains("El servidor no aceptó"))
        assertTrue(ui.message.contains("Tu código se conserva")); assertFalse(ui.message.contains("Fallida"))
        assertNull(state.validationEvidence!!.workflow); assertTrue(state.validationEvidence!!.operations.isEmpty())
        assertEquals(0, state.validationEvidence!!.passedOperations); assertEquals(6, reads)
    }

    @Test fun `saved admission error restores on a fresh screen but never belongs to another session or revision`() = runBlocking {
        val evidence = workflowEvidence(26, 3, "NOT_STARTED", 0)
        val failure = ValidationRequestFailure.capture(evidence, AteneaApiException(422, "VALIDATION_SOURCE_CHANGED"))
        val saveScope = object : SaverScope { override fun canBeSaved(value: Any) = true }
        val saved = with(ValidationRequestFailure.Saver) { saveScope.save(failure) }!!
        val restored = ValidationRequestFailure.Saver.restore(saved)!!
        assertEquals(failure, restored)
        var server = evidence
        val state = MobileDeliveryUiState(26, this) { MobileDeliveryState(true, emptyList(), validation = server) }
        state.refresh(); assertTrue(state.validationUi(session(), false, restored).needsAttention)
        assertFalse(restored.matches(evidence.copy(workSessionId = 27)))
        assertFalse(restored.matches(evidence.copy(changeKey = UUID.randomUUID())))
        assertFalse(restored.matches(evidence.copy(sourceFingerprintSha256 = "f".repeat(64))))
        server = evidence.copy(sourceRevision = 4, sourceFingerprintSha256 = "f".repeat(64))
        state.refresh()
        assertFalse(state.validationUi(session(), false, restored).needsAttention)
        assertEquals("Validar cambio", state.validationUi(session(), false, restored).label)
    }

    @Test fun `unconfirmed request is not presented as running and a confirmed server workflow takes precedence`() = runBlocking {
        var evidence = workflowEvidence(26, 3, "NOT_STARTED", 0)
        val failure = ValidationRequestFailure.capture(evidence, java.io.IOException("synthetic lost response"))
        val state = MobileDeliveryUiState(26, this) { MobileDeliveryState(true, emptyList(), validation = evidence) }
        state.refresh()
        var ui = state.validationUi(session(), false, failure)
        assertEquals("Recuperar solicitud", ui.label); assertTrue(ui.canStart); assertTrue(ui.needsAttention)
        assertFalse(ui.inProgress); assertTrue(ui.message.contains("mismo identificador"))
        evidence = evidence.copy(validationState = "STALE", workflow = workflow())
        state.refresh(); ui = state.validationUi(session(), false, failure)
        assertTrue(ui.inProgress); assertFalse(ui.needsAttention); assertFalse(ui.canStart)
        assertEquals("Validando…", ui.label); assertFalse(state.beginValidation())
        evidence = workflowEvidence(26, 3, "CURRENT", 4).copy(workflow = workflow("SUCCEEDED", "FINISHED"))
        state.refresh(); ui = state.validationUi(session(), false, failure)
        assertTrue(ui.current); assertFalse(ui.needsAttention); assertFalse(ui.canStart)
    }

    @Test fun `offline read never enables retry from a retained admission error`() = runBlocking {
        val evidence = workflowEvidence(26, 3, "NOT_STARTED", 0)
        val failure = ValidationRequestFailure.capture(evidence, AteneaApiException(422, "VALIDATION_SOURCE_CHANGED"))
        var online = true
        val state = MobileDeliveryUiState(26, this) {
            if (!online) error("offline")
            MobileDeliveryState(true, emptyList(), validation = evidence)
        }
        state.refresh(); online = false; state.refresh()
        val ui = state.validationUi(session(), false, failure)
        assertFalse(ui.canStart); assertTrue(ui.checking); assertFalse(state.beginValidation())
        online = true; state.refresh()
        assertTrue(state.validationUi(session(), false, failure).needsAttention)
    }

    @Test fun `sending a request is not falsely presented as an admitted server validation`() = runBlocking {
        var evidence = workflowEvidence(26, 3, "NOT_STARTED", 0)
        val state = MobileDeliveryUiState(26, this) { MobileDeliveryState(true, emptyList(), validation = evidence) }
        state.refresh(); assertTrue(state.beginValidation())
        var ui = state.validationUi(session(), false)
        assertTrue(ui.requesting); assertTrue(ui.inProgress); assertFalse(ui.canStart)
        assertEquals("Solicitando…", ui.label); assertTrue(ui.message.contains("Todavía no se ha confirmado"))
        state.refresh(); assertTrue(state.validationUi(session(), false).requesting)
        evidence = evidence.copy(validationState = "STALE", workflow = workflow())
        state.refresh(); ui = state.validationUi(session(), false)
        assertFalse(ui.requesting); assertTrue(ui.inProgress); assertEquals("Validando…", ui.label)
        state.finishValidation(); assertTrue(state.validationUi(session(), false).inProgress)
    }

    @Test fun `request timeout is uncertain and not permission to discard the idempotency key`() {
        val evidence = workflowEvidence(26, 3, "NOT_STARTED", 0)
        val failure = ValidationRequestFailure.capture(evidence, AteneaApiException(408, "Timeout"))
        assertFalse(failure.rejected); assertTrue(failure.message.contains("mismo identificador"))
    }
}
