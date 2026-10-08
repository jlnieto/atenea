package com.atenea.android.coreconsole

import com.atenea.android.api.MobileDeliveryOperation
import com.atenea.android.api.MobileDeliveryIntegration
import com.atenea.android.api.MobileDeliveryState
import com.atenea.android.api.MobileDeliveryTarget
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
