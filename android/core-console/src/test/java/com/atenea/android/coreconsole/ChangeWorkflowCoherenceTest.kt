package com.atenea.android.coreconsole

import com.atenea.android.api.AteneaApiException
import com.atenea.android.api.MobileDeliveryOperation
import com.atenea.android.api.MobileDeliveryState
import com.atenea.android.api.MobileDeliveryTarget
import com.atenea.android.api.MobileSourceUpdate
import com.atenea.android.api.MobileWorkSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChangeWorkflowCoherenceTest {
    private fun source(revision: Long = 4, state: String = "RESOLVER_COMPLETED", base: String = "UPDATE_REQUIRED") =
        MobileSourceUpdate(UUID.randomUUID(), 26, state, "1".repeat(40), revision, null, null, baseStatus = base)
    private fun snapshot(source: MobileSourceUpdate? = source(), validated: Boolean = true, operations: List<MobileDeliveryOperation> = emptyList()) =
        MobileDeliveryState(true, operations, sourceUpdateEnabled = true, sourceUpdate = source,
            validation = workflowEvidence(26, source?.sourceRevision ?: 4, if (validated) "CURRENT" else "STALE"))
    private fun operation(state: String = "QUEUED") = MobileDeliveryOperation(UUID.randomUUID(), UUID.randomUUID(), 26,
        "PUBLISH_PR", MobileDeliveryTarget.APP_PROD, null, state, null, null, null, null, null, null, null, null, null)
    private fun session() = MobileWorkSession(26, 1, "Mismo cambio", "OPEN", "IDLE", "main", "atenea/change-test", null,
        "OPEN", null, null, null, null, null, null, null, null, false,
        "00000000-0000-4000-8000-000000000001", "CURRENT", "DIRTY")

    @Test fun `new prepared revision overrides old CURRENT conversation without leaving the screen`() = runBlocking {
        val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
        try {
            val state = MobileDeliveryUiState(26, scope) { snapshot(source(5, "READY_TO_FINALIZE", "CURRENT"), false) }
            state.refresh()
            val validation = state.validationUi(session(), false)
            assertFalse(validation.current); assertTrue(validation.canStart)
            assertTrue(validation.message.contains("Revisión 5"))
            assertFalse(state.canUpdatePullRequest(true, false))
            assertEquals(DeliveryStepAction.NONE, state.currentStep(true, false).action)
        } finally { scope.cancel() }
    }

    @Test fun `continuous update base validate and update same PR never reactivates previous action`() = runBlocking {
        val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
        try {
            var server = snapshot()
            val state = MobileDeliveryUiState(26, scope) { server }
            state.refresh()
            assertEquals(DeliveryStepAction.UPDATE_BASE, state.currentStep(true, false).action)
            val response = CompletableDeferred<MobileSourceUpdate>()
            var preparations = 0
            state.submitSource(DeliveryStepAction.UPDATE_BASE) { preparations++; response.await() }
            assertTrue(state.busy); assertFalse(state.validationCurrent)
            repeat(10) { state.submitSource(DeliveryStepAction.UPDATE_BASE) { preparations++; response.await() } }
            assertEquals(1, preparations)
            val accepted = source(4, "QUEUED", "UNAVAILABLE")
            response.complete(accepted)
            // A successful POST followed by an old GET is not completion.
            assertTrue(state.busy); assertFalse(state.canUpdateBase(false))
            assertFalse(state.canUpdatePullRequest(true, false))
            assertEquals("Actualizando base con main", state.currentStep(true, false).title)
            server = snapshot(accepted.copy(sourceRevision = 5, state = "READY_TO_FINALIZE", baseStatus = "CURRENT"), false)
            state.refresh()
            assertFalse(state.busy); assertTrue(state.validationUi(session(), false).canStart)
            assertFalse(state.validationCurrent); assertFalse(state.canUpdatePullRequest(true, false))
            assertTrue(state.beginValidation()); assertFalse(state.beginValidation())
            assertFalse(state.validationUi(session(), false).canStart)
            assertEquals(DeliveryStepStatus.WORKING, state.currentStep(true, false).status)
            server = server.copy(validation = workflowEvidence(26, 5))
            state.finishValidation()
            assertTrue(state.validationCurrent); assertTrue(state.canUpdatePullRequest(true, false))
            val published = operation()
            var publications = 0
            state.submitDelivery(DeliveryStepAction.UPDATE_PR) { publications++; published }
            assertTrue(state.busy); assertEquals(1, publications)
            state.submitDelivery(DeliveryStepAction.UPDATE_PR) { publications++; published }
            assertEquals(1, publications)
            server = server.copy(operations = listOf(published.copy(state = "WAITING_CI")),
                sourceUpdate = server.sourceUpdate!!.copy(state = "PUBLISHED"))
            state.refresh()
            assertFalse(state.busy)
            assertEquals(DeliveryStepStatus.WAITING, state.currentStep(true, false).status)
            assertFalse(state.canUpdatePullRequest(true, false))
            assertEquals(1, preparations); assertEquals(1, publications)
        } finally { scope.cancel() }
    }

    @Test fun `late read started before mutation cannot restore old validation or hide pending request`() = runBlocking {
        val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
        try {
            var oldRead: CompletableDeferred<MobileDeliveryState>? = null
            var server = snapshot()
            val state = MobileDeliveryUiState(26, scope) { oldRead?.await() ?: server }
            state.refresh()
            val stale = server
            val delayed = CompletableDeferred<MobileDeliveryState>(); oldRead = delayed
            val reading = scope.launch { state.refresh() }
            val accepted = source(5, "READY_TO_FINALIZE", "CURRENT")
            oldRead = null; server = snapshot(accepted, false)
            state.submitSource(DeliveryStepAction.UPDATE_BASE) { accepted }
            assertFalse(state.validationCurrent)
            delayed.complete(stale); reading.join()
            assertEquals(5, state.validationEvidence!!.sourceRevision)
            assertFalse(state.validationCurrent); assertTrue(state.validationUi(session(), false).canStart)
        } finally { scope.cancel() }
    }

    @Test fun `older revision and same revision with another fingerprint never authorize`() = runBlocking {
        val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
        try {
            var server = snapshot(source(5, "READY_TO_FINALIZE", "CURRENT"), false)
            val state = MobileDeliveryUiState(26, scope) { server }
            state.refresh()
            server = snapshot(source(4, "READY_TO_FINALIZE", "CURRENT"))
            state.refresh(); assertEquals(5, state.validationEvidence!!.sourceRevision)
            assertFalse(state.validationCurrent)
            server = snapshot(source(5, "READY_TO_FINALIZE", "CURRENT")).copy(validation = workflowEvidence(26, 5).copy(sourceFingerprintSha256 = "9".repeat(64)))
            state.refresh(); assertFalse(state.available); assertFalse(state.validationCurrent)
        } finally { scope.cancel() }
    }

    @Test fun `concurrent readers share failure and require a fresh successful observation`() = runBlocking {
        val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
        try {
            val delayed = CompletableDeferred<MobileDeliveryState>()
            var delayRead = false
            var reads = 0
            val state = MobileDeliveryUiState(26, scope) { reads++; if (delayRead) delayed.await() else snapshot() }
            state.refresh(); delayRead = true
            val first = scope.launch { state.refresh() }
            delayRead = false
            val second = scope.launch { state.refresh() }
            assertEquals(2, reads)
            delayed.completeExceptionally(IOException("shared response")); first.join(); second.join()
            assertFalse(state.available); assertFalse(state.validationCurrent)
            state.refresh(); assertEquals(3, reads)
            assertTrue(state.available); assertTrue(state.validationCurrent)
        } finally { scope.cancel() }
    }

    @Test fun `missing foreign mismatched or incomplete validation fails closed despite old true flag`() = runBlocking {
        val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
        try {
            var server = snapshot(source(5, "READY_TO_FINALIZE", "CURRENT"))
            val state = MobileDeliveryUiState(26, scope) { server }
            state.refresh(); assertTrue(state.validationCurrent)
            for (proof in listOf(null, workflowEvidence(26, 5, passed = 3), workflowEvidence(26, 6))) {
                server = server.copy(validation = proof); state.refresh()
                assertFalse(state.validationCurrent); assertFalse(state.canUpdatePullRequest(true, false))
            }
            server = server.copy(validation = workflowEvidence(99, 6)); state.refresh()
            assertFalse(state.available)
        } finally { scope.cancel() }
    }

    @Test fun `lost response holds same action until durable observation never resubmits`() = runBlocking {
        val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
        try {
            var server = snapshot()
            var posts = 0
            val state = MobileDeliveryUiState(26, scope) { server }
            state.refresh()
            state.submitSource(DeliveryStepAction.UPDATE_BASE) { posts++; throw IOException("lost response") }
            assertTrue(state.busy); assertTrue(state.pendingUnconfirmed)
            repeat(3) { state.refresh(); state.submitSource(DeliveryStepAction.UPDATE_BASE) { posts++; error("duplicate") } }
            assertEquals(1, posts)
            assertEquals(DeliveryStepAction.REFRESH, state.currentStep(true, false).action)
            // A read-only baseStatus change alone is not evidence that the POST completed.
            server = server.copy(sourceUpdate = server.sourceUpdate!!.copy(baseStatus = "CURRENT"))
            state.refresh(); assertTrue(state.busy)
            server = snapshot(source(5, "READY_TO_FINALIZE", "CURRENT"), false)
            state.refresh(); assertFalse(state.busy); assertFalse(state.validationCurrent)
            assertEquals(1, posts)
        } finally { scope.cancel() }
    }

    @Test fun `read failure after accepted request retains barrier and resumes by reading only`() = runBlocking {
        val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
        try {
            var online = true; var server = snapshot(); var posts = 0
            val state = MobileDeliveryUiState(26, scope) { if (online) server else throw IOException("offline") }
            state.refresh()
            val accepted = source(4, "QUEUED", "UNAVAILABLE")
            state.submitSource(DeliveryStepAction.UPDATE_BASE) { posts++; online = false; accepted }
            assertTrue(state.busy); assertFalse(state.available); assertTrue(state.pendingUnconfirmed)
            server = snapshot(accepted.copy(sourceRevision = 5, state = "READY_TO_FINALIZE", baseStatus = "CURRENT"), false)
            online = true; state.refresh()
            assertFalse(state.busy); assertTrue(state.available); assertEquals(1, posts)
        } finally { scope.cancel() }
    }

    @Test fun `rejected command refreshes current revision and does not retain old publish button`() = runBlocking {
        val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
        try {
            var server = snapshot(source(4, "READY_TO_FINALIZE", "CURRENT"))
            val state = MobileDeliveryUiState(26, scope) { server }
            state.refresh()
            state.submitDelivery(DeliveryStepAction.UPDATE_PR) {
                server = snapshot(source(5, "READY_TO_FINALIZE", "CURRENT"), false)
                throw AteneaApiException(409, "VALIDATED_OWNERSHIP_REQUIRED")
            }
            assertFalse(state.busy); assertFalse(state.validationCurrent)
            assertFalse(state.canUpdatePullRequest(true, false)); assertTrue(state.validationUi(session(), false).canStart)
        } finally { scope.cancel() }
    }

    @Test fun `temporarily unavailable proof can recover without forgetting its revision fingerprint`() = runBlocking {
        val scope=CoroutineScope(Job()+Dispatchers.Unconfined)
        try {
            val original=snapshot(source(5,"READY_TO_FINALIZE","CURRENT"))
            var server=original
            val state=MobileDeliveryUiState(26,scope) { server }
            state.refresh();assertTrue(state.validationCurrent)
            server=original.copy(validation=null);state.refresh();assertFalse(state.validationCurrent)
            server=original;state.refresh();assertTrue(state.validationCurrent)
        } finally { scope.cancel() }
    }

    @Test fun `PR submission preserves passed validation while its button remains locked`() = runBlocking {
        val scope=CoroutineScope(Job()+Dispatchers.Unconfined)
        try {
            val state=MobileDeliveryUiState(26,scope) { snapshot(source(5,"READY_TO_FINALIZE","CURRENT")) }
            state.refresh()
            val reply=CompletableDeferred<MobileDeliveryOperation>()
            state.submitDelivery(DeliveryStepAction.UPDATE_PR) { reply.await() }
            assertTrue(state.validationCurrent);assertFalse(state.canUpdatePullRequest(true,false))
            assertEquals(DeliveryStepStatus.WORKING,state.currentStep(true,false).status)
            assertEquals("Actualizando la misma PR",state.currentStep(true,false).title)
        } finally { scope.cancel() }
    }

    @Test fun `validation stays available without granting administrative delivery permissions`() = runBlocking {
        val scope=CoroutineScope(Job()+Dispatchers.Unconfined)
        try {
            val state=MobileDeliveryUiState(26,scope) { snapshot(source(5,"READY_TO_FINALIZE","CURRENT"),false).copy(enabled=false) }
            state.refresh()
            assertTrue(state.validationUi(session(),false).canStart)
            assertTrue(state.beginValidation())
            assertFalse(state.canUpdatePullRequest(true,false));assertFalse(state.canPrepareReleaseRecovery(true,false))
            assertFalse(state.canUpdateBase(false));assertFalse(state.available)
        } finally { scope.cancel() }
    }
}
