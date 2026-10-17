package com.atenea.android.coreconsole

import com.atenea.android.api.DevelopmentChangeValidationAttempt
import com.atenea.android.api.DevelopmentChangeValidationEvidence
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ValidationProgressUiTest {
    private val now = Instant.parse("2026-10-17T10:05:00Z")
    private val tree = "4".repeat(64)
    private fun attempt(kind: String, status: String = "SUCCEEDED", phase: String? = null,
        observed: String? = if (phase != null) now.minusSeconds(2).toString() else null) =
        DevelopmentChangeValidationAttempt(UUID.randomUUID(), 26, kind, status, tree, "synthetic", 0, "Persisted result",
            now.minusSeconds(120).toString(), if (status == "RUNNING") null else now.minusSeconds(60).toString(),
            durationMillis = if (status == "RUNNING") null else 60_000,
            workerState = phase, workerObservedAt = observed,
            workerStartedAt = if (phase == "RUNNING") now.minusSeconds(90).toString() else null)
    private fun evidence(vararg attempts: DevelopmentChangeValidationAttempt) = DevelopmentChangeValidationEvidence(
        UUID.randomUUID(), 26, 6, tree, "STALE", attempts.count { it.status == "SUCCEEDED" }, 4, attempts.toList(), null)
    private fun progress(proof: DevelopmentChangeValidationEvidence, at: Instant = now, requested: Boolean = true,
        confirmed: Boolean = true) = validationProgressUi(proof, 26, at, requested, confirmed)!!

    @Test fun `two completed queued Android and remaining web are distinct ordered steps`() {
        val result = progress(evidence(attempt("WEB_BUILD"), attempt("BACKEND_TEST"), attempt("ANDROID_BUILD", "RUNNING", "QUEUED")))
        assertEquals(2, result.passed)
        assertEquals(listOf("BACKEND_TEST", "WEB_BUILD", "ANDROID_BUILD", "PLAYWRIGHT_ACCEPTANCE"), result.steps.map { it.operation })
        assertEquals(listOf(ValidationProgressStatus.PASSED, ValidationProgressStatus.PASSED,
            ValidationProgressStatus.QUEUED, ValidationProgressStatus.PENDING), result.steps.map { it.status })
        assertEquals("En cola", result.steps[2].statusLabel)
        assertEquals("Desde la solicitud: 2 min 0 s", result.steps[2].timing)
        assertEquals("Tiempo desde la primera solicitud: 2 min 0 s", result.totalTime)
        assertTrue(result.confirmation!!.contains("hace 2 s"))
        assertFalse(result.uncertain)
    }

    @Test fun `execution duration uses real broker start not admission or client click`() {
        val result = progress(evidence(attempt("ANDROID_BUILD", "RUNNING", "RUNNING")))
        assertEquals(ValidationProgressStatus.RUNNING, result.steps[2].status)
        assertEquals("En ejecución desde hace 1 min 30 s", result.steps[2].timing)
        val later = progress(evidence(attempt("ANDROID_BUILD", "RUNNING", "RUNNING")), now.plusSeconds(4))
        assertEquals("En ejecución desde hace 1 min 34 s", later.steps[2].timing)
    }

    @Test fun `without broker execution timestamp timing says since request never execution duration`() {
        val active = attempt("BACKEND_TEST", "RUNNING", "RUNNING").copy(workerStartedAt = null)
        assertEquals("Desde la solicitud: 2 min 0 s", progress(evidence(active)).steps[0].timing)
    }

    @Test fun `stale confirmation is not a heartbeat and cannot keep claiming live execution`() {
        val active = attempt("ANDROID_BUILD", "RUNNING", "RUNNING", now.minusSeconds(90).toString())
        val result = progress(evidence(active))
        assertEquals(ValidationProgressStatus.CONFIRMING, result.steps[2].status)
        assertEquals("Último estado: en ejecución", result.steps[2].statusLabel)
        assertTrue(result.confirmation!!.contains("Sin confirmación reciente"))
        assertTrue(result.confirmation!!.contains("no repitas la validación"))
        assertTrue(result.uncertain)
    }

    @Test fun `read failure retains labelled historical results rather than presenting them as live`() {
        val result = progress(evidence(attempt("BACKEND_TEST", "RUNNING", "RUNNING")), confirmed = false)
        assertEquals(ValidationProgressStatus.CONFIRMING, result.steps[0].status)
        assertTrue(result.confirmation!!.contains("últimos resultados guardados"))
    }

    @Test fun `legacy nonterminal evidence does not invent queued running or a confirmation`() {
        val result = progress(evidence(attempt("BACKEND_TEST", "RUNNING")))
        assertEquals(ValidationProgressStatus.CONFIRMING, result.steps[0].status)
        assertTrue(result.confirmation!!.contains("todavía no ha confirmado"))
        assertTrue(result.uncertain)
    }

    @Test fun `old revision attempt never enters this progress or its elapsed time`() {
        val proof = evidence().copy(lastAttempt = attempt("ANDROID_BUILD", "FAILED").copy(sourceTreeFingerprintSha256 = "5".repeat(64)))
        val result = progress(proof)
        assertEquals(0, result.passed)
        assertTrue(result.steps.all { it.status == ValidationProgressStatus.PENDING })
        assertNull(result.totalTime)
        assertNull(result.confirmation)
    }

    @Test fun `terminal measured durations and overall duration do not keep increasing`() {
        val proof = evidence(attempt("BACKEND_TEST"), attempt("WEB_BUILD"), attempt("ANDROID_BUILD"), attempt("PLAYWRIGHT_ACCEPTANCE"))
        val first = progress(proof, requested = false)
        assertEquals(first, progress(proof, now.plusSeconds(300), requested = false))
        assertEquals(4, first.passed)
        assertEquals("Duración: 1 min 0 s", first.steps[0].timing)
        assertEquals("Tiempo desde la primera solicitud: 1 min 0 s", first.totalTime)
    }

    @Test fun `failure blocks a phase without calling unexecuted phases completed`() {
        val result = progress(evidence(attempt("BACKEND_TEST"), attempt("WEB_BUILD", "FAILED")), requested = false)
        assertEquals(1, result.passed)
        assertEquals(ValidationProgressStatus.STOPPED, result.steps[1].status)
        assertEquals(ValidationProgressStatus.PENDING, result.steps[2].status)
        assertEquals(ValidationProgressStatus.PENDING, result.steps[3].status)
    }

    @Test fun `cancelling and reconciling are not displayed as executing tests`() {
        for (phase in listOf("CANCELLING", "RECONCILING")) {
            val result = progress(evidence(attempt("BACKEND_TEST", "RUNNING", phase)))
            assertEquals(ValidationProgressStatus.CONFIRMING, result.steps[0].status)
            assertFalse(result.steps[0].statusLabel.contains("ejecución"))
        }
    }

    @Test fun `foreign mismatched duplicate or fabricated evidence is never rendered`() {
        val good = evidence(attempt("BACKEND_TEST"))
        for (proof in listOf(good.copy(workSessionId = 99), good.copy(passedOperations = 4),
            good.copy(operations = listOf(good.operations[0].copy(workSessionId = 99))),
            good.copy(operations = listOf(good.operations[0].copy(sourceTreeFingerprintSha256 = "5".repeat(64)))),
            good.copy(operations = good.operations + good.operations[0]))) {
            assertNull(validationProgressUi(proof, 26, now, false, true))
        }
    }

    @Test fun `future or missing timestamps do not invent live confirmation or negative time`() {
        val active = attempt("BACKEND_TEST", "RUNNING", "RUNNING", now.plusSeconds(60).toString())
        val result = progress(evidence(active))
        assertTrue(result.uncertain)
        assertEquals(ValidationProgressStatus.CONFIRMING, result.steps[0].status)
        assertEquals("0 s", validationDuration(-1))
        assertEquals("2 h 1 min", validationDuration(7260))
    }
}
