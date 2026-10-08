package com.atenea.android.coreconsole

import com.atenea.android.api.MobileDeliveryOperation
import com.atenea.android.api.MobileDeliveryIntegration
import com.atenea.android.api.MobileDeliveryState
import com.atenea.android.api.MobileDeliveryTarget
import com.atenea.android.api.MobileSourceUpdate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MobileDeliveryUiStateTest {
    @Test fun `recovery is explicit blocks active runs and does not bypass validation for publication`() = runBlocking<Unit> {
        val scope=CoroutineScope(Job()+Dispatchers.Unconfined)
        try {
            var update=MobileSourceUpdate(UUID.randomUUID(),21,"ATTENTION","2".repeat(40),null,null,"SOURCE_UPDATE_REF_MOVED",true)
            var operation=operation().copy(kind="PUBLISH_PR",state="BLOCKED")
            val state=MobileDeliveryUiState(21,scope) { MobileDeliveryState(true,listOf(operation),null,true,update) }
            state.refresh();assertTrue(state.canRecoverSource(false,false));assertFalse(state.canRecoverSource(false,true))
            update=update.copy(state="FAILED",resolverRunId=105);state.refresh();assertFalse(state.canRecoverSource(true,false))
            update=update.copy(state="READY_TO_FINALIZE",resolverRunId=null);state.refresh()
            assertFalse(state.canRecoverSource(false,false));assertTrue(state.canRecoverSource(true,false))
            operation=operation.copy(state="QUEUED");state.refresh();assertFalse(state.canRecoverSource(true,false))
            update=update.copy(state="PUBLISHED");operation=operation.copy(state="BLOCKED");state.refresh()
            assertFalse(state.canRecoverSource(true,false))
        } finally { scope.cancel() }
    }
    @Test fun `failed resolver retry requires current online intent and never enables integration`() = runBlocking<Unit> {
        val scope=CoroutineScope(Job()+Dispatchers.Unconfined)
        try {
            var update=MobileSourceUpdate(UUID.randomUUID(),21,"FAILED","2".repeat(40),4,105,"SOURCE_UPDATE_RESOLVER_FAILED")
            var online=true
            val state=MobileDeliveryUiState(21,scope) {
                if (!online) error("offline")
                MobileDeliveryState(true,listOf(operation().copy(state="SUCCEEDED")),
                    MobileDeliveryIntegration(21,"1".repeat(40),"CONFLICTS",false,null),true,update)
            }
            state.refresh();assertTrue(state.canRetryResolver(false));assertFalse(state.canRetryResolver(true))
            assertFalse(state.canResolveConflicts(true,false));assertFalse(state.canUpdatePullRequest(true,false));assertFalse(state.integration!!.allowsRequest)
            update=update.copy(state="RESOLVING",resolverRunId=106);state.refresh();assertFalse(state.canRetryResolver(false))
            update=update.copy(state="FAILED",resolverRunId=null);state.refresh();assertFalse(state.canRetryResolver(false))
            update=update.copy(resolverRunId=106);state.refresh();assertTrue(state.canRetryResolver(false))
            online=false;state.refresh();assertFalse(state.canRetryResolver(false))
        } finally { scope.cancel() }
    }
    @Test fun `same PR update needs new validation and online durable receipt`() = runBlocking<Unit> {
        val scope=CoroutineScope(Job()+Dispatchers.Unconfined)
        try {
            var online=true
            var update=MobileSourceUpdate(UUID.randomUUID(),21,"RESOLVER_COMPLETED","2".repeat(40),5,105,null)
            val state=MobileDeliveryUiState(21,scope) {
                if (!online) error("offline")
                MobileDeliveryState(true,listOf(operation().copy(state="SUCCEEDED")),
                    MobileDeliveryIntegration(21,"1".repeat(40),"STALE_SOURCE",false,null),true,update)
            }
            state.refresh()
            assertFalse(state.canUpdatePullRequest(false,false)); assertFalse(state.canUpdatePullRequest(true,true))
            assertTrue(state.canUpdatePullRequest(true,false)); assertFalse(state.integration!!.allowsRequest)
            update=update.copy(state="PUBLISHED"); state.refresh()
            assertFalse(state.canUpdatePullRequest(true,false))
            assertTrue(sourceUpdateLabel("PUBLISHED").contains("GitHub"))
            update=update.copy(state="READY_TO_FINALIZE"); state.refresh(); assertTrue(state.canUpdatePullRequest(true,false))
            online=false;state.refresh();assertFalse(state.canUpdatePullRequest(true,false))
        } finally { scope.cancel() }
    }
    @Test fun `resolver action is closed and old completion never enables integration`() = runBlocking<Unit> {
        val scope=CoroutineScope(Job()+Dispatchers.Unconfined)
        try {
            var update: MobileSourceUpdate?=null
            var online=true
            val state=MobileDeliveryUiState(21,scope) {
                if (!online) error("offline")
                MobileDeliveryState(true,listOf(operation().copy(state="SUCCEEDED")),MobileDeliveryIntegration(21,"1".repeat(40),"CONFLICTS",false,null),true,update)
            }
            state.refresh()
            assertTrue(state.canResolveConflicts(true,false))
            assertFalse(state.canResolveConflicts(false,false)); assertFalse(state.canResolveConflicts(true,true))
            update=MobileSourceUpdate(UUID.randomUUID(),21,"RESOLVING","2".repeat(40),4,105,null)
            state.refresh(); assertFalse(state.canResolveConflicts(true,false))
            val id=state.sourceUpdate!!.id
            update=update!!.copy(state="RESOLVER_COMPLETED")
            state.refresh(); assertEquals(id,state.sourceUpdate!!.id)
            assertTrue(state.canResolveConflicts(true,false))
            assertFalse(state.canResolveConflicts(false,false)); assertFalse(state.canResolveConflicts(true,true))
            update=update!!.copy(state="PUBLISHED");state.refresh()
            assertTrue(state.canResolveConflicts(true,false)); assertEquals(id,state.sourceUpdate!!.id)
            update=update!!.copy(state="RESOLVER_COMPLETED");state.refresh()
            assertFalse(state.integration!!.allowsRequest)
            assertTrue(sourceUpdateLabel("RESOLVER_COMPLETED").contains("Falta validar"))
            online=false; state.refresh()
            assertEquals(id,state.sourceUpdate!!.id); assertFalse(state.sourceUpdateEnabled)
            assertFalse(state.canResolveConflicts(true,false))
        } finally { scope.cancel() }
    }
    @Test fun `foreign source update is rejected and legacy capability does not authorize resolver`() = runBlocking<Unit> {
        val scope=CoroutineScope(Job()+Dispatchers.Unconfined)
        try {
            val foreign=MobileSourceUpdate(UUID.randomUUID(),22,"RESOLVING","2".repeat(40),4,105,null)
            val state=MobileDeliveryUiState(21,scope) { MobileDeliveryState(true,listOf(operation()),null,true,foreign) }
            state.refresh(); assertFalse(state.available); assertNull(state.sourceUpdate)
            val legacy=MobileDeliveryUiState(21,scope) {
                MobileDeliveryState(true,listOf(operation()),MobileDeliveryIntegration(21,"1".repeat(40),"CONFLICTS",false,null))
            }
            legacy.refresh(); assertFalse(legacy.canResolveConflicts(true,false))
        } finally { scope.cancel() }
    }
    @Test
    fun `a requested or missing run is not presented as running checks`() {
        assertTrue(deliveryErrorLabel("UFD_REQUESTED").contains("esperando que arranquen"))
        assertFalse(deliveryErrorLabel("UFD_REQUESTED").contains("está ejecutando"))
        assertTrue(deliveryErrorLabel("UFD_QUEUED").contains("están en cola"))
        assertFalse(deliveryErrorLabel("UFD_NOT_STARTED").contains("está ejecutando"))
        assertFalse(deliveryErrorLabel("UFD_DISPATCH_UNCONFIRMED").contains("está ejecutando"))
        assertTrue(deliveryErrorLabel("UFD_CONTROLLER_UNAVAILABLE").contains("detenido"))
        assertTrue(deliveryErrorLabel("PR_MERGE_CONFLICTS").contains("conflictos"))
        assertFalse(deliveryErrorLabel("PR_MERGE_CONFLICTS").contains("ejecutando"))
        assertTrue(deliveryIntegrationLabel("UNKNOWN").contains("calculando"))
        assertTrue(deliveryErrorLabel("GITHUB_CHECKS_FAILED").contains("han fallado"))
    }
    @Test
    fun `conflict observation disables integration and polling remains read only`() = runBlocking<Unit> {
        val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
        try {
            var reads = 0
            val result = MobileDeliveryIntegration(21, "1".repeat(40), "CONFLICTS", false, null)
            val state = MobileDeliveryUiState(21, scope) {
                reads++; MobileDeliveryState(true, listOf(operation()), result)
            }
            state.refresh(); state.refresh()
            assertEquals(2, reads)
            assertFalse(state.integration!!.allowsRequest)
            assertTrue(deliveryIntegrationLabel(state.integration!!.mergeState).contains("conflictos"))
        } finally { scope.cancel() }
    }
    @Test
    fun `transport failure discards old mergeability instead of enabling a stale confirmation`() = runBlocking<Unit> {
        val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
        try {
            var fail = false
            val state = MobileDeliveryUiState(21, scope) {
                if (fail) error("offline")
                MobileDeliveryState(true, listOf(operation()), MobileDeliveryIntegration(21, "1".repeat(40), "MERGEABLE", true, null))
            }
            state.refresh(); assertTrue(state.integration!!.allowsRequest)
            fail = true; state.refresh()
            assertFalse(state.available); assertNull(state.integration)
            assertEquals(1, state.operations.size)
        } finally { scope.cancel() }
    }
    @Test
    fun `foreign integration receipt cannot be adopted`() = runBlocking<Unit> {
        val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
        try {
            val state = MobileDeliveryUiState(21, scope) {
                MobileDeliveryState(true, listOf(operation()), MobileDeliveryIntegration(22, "1".repeat(40), "MERGEABLE", true, null))
            }
            state.refresh()
            assertFalse(state.available); assertNull(state.integration)
        } finally { scope.cancel() }
    }
    @Test
    fun `refresh preserves same durable operation identity without creating one`() = runBlocking<Unit> {
        val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
        try {
            val operation = operation()
            var reads = 0
            val state = MobileDeliveryUiState(21, scope) { reads++; MobileDeliveryState(true, listOf(operation)) }
            state.refresh()
            state.refresh()
            assertEquals(operation.id, state.operations.single().id)
            assertEquals(operation.operationId, state.operations.single().operationId)
            assertEquals(2, reads)
            assertTrue(state.available)
            assertTrue(state.loaded)
        } finally { scope.cancel() }
    }

    @Test
    fun `one ongoing action survives pane absence and duplicate tap is ignored`() = runBlocking<Unit> {
        val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
        try {
            val completion = CompletableDeferred<Unit>()
            var actions = 0
            val state = MobileDeliveryUiState(21, scope) { MobileDeliveryState(true, listOf(operation())) }
            state.act { actions++; completion.await() }
            assertTrue(state.busy)
            state.act { actions++ }
            assertEquals(1, actions)
            completion.complete(Unit)
            assertFalse(state.busy)
            assertEquals(1, actions)
            assertEquals(1, state.operations.size)
        } finally { scope.cancel() }
    }

    @Test
    fun `transport failure keeps durable identity but disables stale authorization`() = runBlocking<Unit> {
        val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
        try {
            val operation = operation()
            var unavailable = false
            val state = MobileDeliveryUiState(21, scope) {
                if (unavailable) error("Sin conexión") else MobileDeliveryState(true, listOf(operation))
            }
            state.refresh()
            unavailable = true
            state.refresh()
            assertEquals(operation.id, state.operations.single().id)
            assertFalse(state.available)
            assertNotNull(state.loadError)
            unavailable = false
            state.refresh()
            assertTrue(state.available)
            assertNull(state.loadError)
        } finally { scope.cancel() }
    }

    @Test
    fun `foreign session receipt is not adopted`() = runBlocking<Unit> {
        val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
        try {
            val state = MobileDeliveryUiState(21, scope) { MobileDeliveryState(true, listOf(operation().copy(sessionId = 22))) }
            state.refresh()
            assertFalse(state.available)
            assertTrue(state.operations.isEmpty())
            assertNotNull(state.loadError)
        } finally { scope.cancel() }
    }

    private fun operation() = MobileDeliveryOperation(
        id = UUID.randomUUID(), operationId = UUID.randomUUID(), sessionId = 21, kind = "PUBLISH_PR",
        target = MobileDeliveryTarget.APP_PROD, sourceCommit = "1".repeat(40), state = "WAITING_CI",
        planSha256 = null, errorCode = null, versionCode = null, versionName = null, expiresAt = null,
        pullRequestUrl = null, mergeCommit = null, effectiveSourceCommit = null, resultSha256 = null
    )
}
