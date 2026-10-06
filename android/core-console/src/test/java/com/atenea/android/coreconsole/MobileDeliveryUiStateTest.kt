package com.atenea.android.coreconsole

import com.atenea.android.api.MobileDeliveryOperation
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
