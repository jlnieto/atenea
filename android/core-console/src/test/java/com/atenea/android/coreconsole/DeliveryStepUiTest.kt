package com.atenea.android.coreconsole

import com.atenea.android.api.MobileDeliveryIntegration
import com.atenea.android.api.MobileDeliveryOperation
import com.atenea.android.api.MobileDeliveryState
import com.atenea.android.api.MobileDeliveryTarget
import com.atenea.android.api.MobileDeploymentObservation
import com.atenea.android.api.MobileSourceUpdate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeliveryStepUiTest {
    private val now = System.currentTimeMillis() / 1000
    private fun operation(kind: String, state: String, target: MobileDeliveryTarget = MobileDeliveryTarget.APP_PROD) =
        MobileDeliveryOperation(UUID.randomUUID(), UUID.randomUUID(), 22, kind, target, "1".repeat(40), state,
            "4".repeat(64), null, versionCode = null, versionName = null, expiresAt = now + 600,
            pullRequestUrl = null, mergeCommit = "2".repeat(40), effectiveSourceCommit = null, resultSha256 = null)
    private fun snapshot(vararg ops: MobileDeliveryOperation) = MobileDeliveryState(true, ops.toList(),
        integration = MobileDeliveryIntegration(22, "1".repeat(40), "MERGED", false, null),
        releaseRecoveryEnabled = true, completionEnabled = true,
        deployment = MobileDeploymentObservation(22, "NOT_INCLUDED", null, null, null, false,
            null, null, null, null, "RELEASE_CHANGE_NOT_INCLUDED"))
    private fun state(value: MobileDeliveryState, check: (MobileDeliveryUiState) -> Unit) = runBlocking {
        val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
        try {
            val state = MobileDeliveryUiState(22, scope) { value }
            state.refresh(); check(state)
        } finally { scope.cancel() }
    }

    @Test fun `ready main publication shows user confirmation not working or another preparation`() {
        val ready = operation("RELEASE", "READY").copy(sourceCommit = "2".repeat(40))
        state(snapshot(ready, operation("INTEGRATE", "SUCCEEDED"))) {
            val step = it.currentStep(true, false, now)
            assertEquals(DeliveryStepStatus.ACTION, step.status)
            assertEquals(DeliveryStepAction.CONFIRM_RELEASE, step.action)
            assertEquals(ready.id, step.operation?.id)
            assertTrue(step.title.contains("Backend PROD"))
            assertFalse(it.canPrepareReleaseRecovery(true, false))
        }
    }
    @Test fun `expired plan cannot confirm and retained terminal expiry offers explicit new preparation`() {
        val expired = operation("RELEASE", "READY").copy(expiresAt = now)
        state(snapshot(expired, operation("INTEGRATE", "SUCCEEDED"))) {
            val step = it.currentStep(true, false, now)
            assertEquals(DeliveryStepStatus.ATTENTION, step.status)
            assertEquals(DeliveryStepAction.REFRESH, step.action)
            assertFalse(it.canPrepareReleaseRecovery(true, false))
        }
        state(snapshot(expired.copy(state = "BLOCKED", errorCode = "PLAN_EXPIRED"), operation("INTEGRATE", "SUCCEEDED"))) {
            assertEquals(DeliveryStepAction.PREPARE_BACKEND, it.currentStep(true, false, now).action)
            assertTrue(it.canPrepareReleaseRecovery(true, false))
            assertEquals(expired.id, it.operations.first().id)
        }
    }
    @Test fun `preparing waiting CI and applying only report working and preserve operation`() {
        for (status in listOf("PREPARING", "WAITING_CI", "APPLYING")) {
            val op = operation("RELEASE", status)
            state(snapshot(op, operation("INTEGRATE", "SUCCEEDED"))) {
                val step = it.currentStep(true, false, now)
                assertEquals(DeliveryStepStatus.WORKING, step.status)
                assertEquals(DeliveryStepAction.NONE, step.action)
                assertEquals(op.id, step.operation?.id)
                assertTrue(step.message.contains("Puedes salir"))
            }
        }
    }
    @Test fun `old failed plan does not mark already verified backend as needing attention`() {
        val observed = MobileDeploymentObservation(22, "DEPLOYED", "OPERATOR", "3".repeat(40), "2".repeat(40),
            true, now, UUID.randomUUID(), UUID.randomUUID(), "5".repeat(64), null)
        state(snapshot(operation("RELEASE", "BLOCKED").copy(errorCode = "PLAN_EXPIRED"),
            operation("INTEGRATE", "SUCCEEDED")).copy(deployment = observed)) {
            assertEquals(DeliveryStepStatus.ACTION, it.currentStep(true, false, now).status)
            assertEquals(DeliveryStepAction.FINALIZE, it.currentStep(true, false, now).action)
            assertTrue(it.canFinalize(true, false))
        }
    }
    @Test fun `Android ready plan is visible as the current action even with backend history`() {
        val android = operation("RELEASE", "READY", MobileDeliveryTarget.ANDROID_STABLE)
        state(snapshot(android, operation("RELEASE", "BLOCKED"), operation("INTEGRATE", "SUCCEEDED"))) {
            val step = it.currentStep(true, false, now)
            assertEquals(DeliveryStepAction.CONFIRM_RELEASE, step.action)
            assertTrue(step.title.contains("Android estable"))
        }
    }
    @Test fun `healthy backend cannot hide failed Android but newer same target success retires the failure`() {
        val failed = operation("RELEASE", "FAILED", MobileDeliveryTarget.ANDROID_STABLE).copy(errorCode = "RELEASE_BUILD_FAILED")
        val observed = MobileDeploymentObservation(22, "DEPLOYED", "OPERATOR", "3".repeat(40), "2".repeat(40),
            true, now, UUID.randomUUID(), UUID.randomUUID(), "5".repeat(64), null)
        state(snapshot(failed, operation("INTEGRATE", "SUCCEEDED")).copy(deployment = observed)) {
            val step = it.currentStep(true, false, now)
            assertEquals(DeliveryStepStatus.ATTENTION, step.status)
            assertEquals(DeliveryStepAction.REFRESH, step.action)
            assertEquals(failed.id, step.operation?.id)
        }
        state(snapshot(operation("RELEASE", "SUCCEEDED", MobileDeliveryTarget.ANDROID_STABLE), failed,
            operation("INTEGRATE", "SUCCEEDED")).copy(deployment = observed)) {
            assertEquals(DeliveryStepAction.FINALIZE, it.currentStep(true, false, now).action)
        }
    }
    @Test fun `rollback failure and unavailable observations never offer a second publication`() {
        state(snapshot(operation("RELEASE", "ROLLBACK_FAILED"), operation("INTEGRATE", "SUCCEEDED"))) {
            assertEquals(DeliveryStepStatus.ATTENTION, it.currentStep(true, false, now).status)
            assertEquals(DeliveryStepAction.NONE, it.currentStep(true, false, now).action)
            assertFalse(it.canPrepareReleaseRecovery(true, false))
        }
        state(snapshot(operation("INTEGRATE", "SUCCEEDED")).copy(deployment =
            MobileDeploymentObservation(22, "UNAVAILABLE", null, null, null, false, null, null, null, null, "RELEASE_TRANSPORT_UNAVAILABLE"))) {
            assertEquals(DeliveryStepAction.REFRESH, it.currentStep(true, false, now).action)
            assertFalse(it.canPrepareReleaseRecovery(true, false))
        }
    }
    @Test fun `published historical source update does not hide current integration action`() {
        state(snapshot(operation("PUBLISH_PR", "SUCCEEDED")).copy(
            integration = MobileDeliveryIntegration(22, "1".repeat(40), "MERGEABLE", true, null),
            sourceUpdate = MobileSourceUpdate(UUID.randomUUID(), 22, "PUBLISHED", "2".repeat(40), 1, 114, null))) {
            assertEquals(DeliveryStepAction.INTEGRATE, it.currentStep(true, false, now).action)
            assertEquals(DeliveryStepStatus.ACTION, it.currentStep(true, false, now).status)
        }
    }
    @Test fun `source recovery remains reachable before validation without bypassing its guards`() {
        state(snapshot().copy(sourceUpdateEnabled = true,
            integration = null, sourceUpdate = MobileSourceUpdate(UUID.randomUUID(), 22, "FAILED", "2".repeat(40), 1, 114, null))) {
            assertEquals(DeliveryStepAction.RETRY_RESOLVER, it.currentStep(false, false, now).action)
            assertTrue(it.canRetryResolver(false))
            assertEquals(DeliveryStepStatus.WORKING, it.currentStep(false, true, now).status)
        }
    }
    @Test fun `offline retains history but cannot authorize from old ready plan`() {
        val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
        try {
            var online = true
            val state = MobileDeliveryUiState(22, scope) {
                if (!online) error("offline")
                snapshot(operation("RELEASE", "READY"), operation("INTEGRATE", "SUCCEEDED"))
            }
            runBlocking { state.refresh(); online = false; state.refresh() }
            assertEquals(2, state.operations.size)
            assertEquals(DeliveryStepStatus.CHECKING, state.currentStep(true, false, now).status)
            assertEquals(DeliveryStepAction.REFRESH, state.currentStep(true, false, now).action)
            assertFalse(state.available)
        } finally { scope.cancel() }
    }
}
