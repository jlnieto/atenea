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
    @Test fun `failed release build offers one repair instead of repeating backend preparation`() {
        val failed=operation("RELEASE","BLOCKED").copy(errorCode="RELEASE_BUILD_FAILED")
        val build=com.atenea.android.api.MobileBuildStatus("FAILED","2".repeat(40),"BACKEND_COMPILATION",
            "https://github.com/jlnieto/atenea/actions/runs/91",true,null,listOf("src/test/java/com/atenea/Test.java:71"))
        state(snapshot(failed,operation("INTEGRATE","SUCCEEDED")).copy(sourceUpdateEnabled=true,build=build)) {
            assertEquals(DeliveryStepAction.REPAIR_BUILD,it.currentStep(true,false,now).action)
            assertTrue(it.canRepairBuild(false));assertFalse(it.canRepairBuild(true))
            assertFalse(it.canPrepareReleaseRecovery(true,false))
            assertEquals(DeliveryStepStatus.ATTENTION,it.currentStep(true,false,now).status)
        }
    }
    @Test fun `unknown or legacy failed build cannot advertise another preparation`() {
        val failed=operation("RELEASE","BLOCKED").copy(errorCode="RELEASE_BUILD_FAILED")
        state(snapshot(failed,operation("INTEGRATE","SUCCEEDED"))) {
            assertEquals(DeliveryStepAction.REFRESH,it.currentStep(true,false,now).action)
            assertFalse(it.canRepairBuild(false))
        }
        state(snapshot(failed,operation("INTEGRATE","SUCCEEDED")).copy(build=
            com.atenea.android.api.MobileBuildStatus(null,null,null,null,false,"BUILD_STATUS_UNAVAILABLE",emptyList()))) {
            assertEquals(DeliveryStepStatus.CHECKING,it.currentStep(true,false,now).status)
            assertFalse(it.canPrepareReleaseRecovery(true,false))
        }
    }
    @Test fun `newer successful build retires historical failure without claiming deployment`() {
        val failed=operation("RELEASE","BLOCKED").copy(errorCode="RELEASE_BUILD_FAILED")
        state(snapshot(failed,operation("INTEGRATE","SUCCEEDED")).copy(build=
            com.atenea.android.api.MobileBuildStatus("READY","3".repeat(40),"COMPLETED",null,false,null,emptyList()))) {
            assertEquals(DeliveryStepAction.PREPARE_BACKEND,it.currentStep(true,false,now).action)
            assertEquals(DeliveryStepStatus.ACTION,it.currentStep(true,false,now).status)
            assertFalse(it.appDeployed);assertTrue(it.canPrepareReleaseRecovery(true,false))
        }
    }
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
            val readiness=value.readiness ?: com.atenea.android.api.MobileChangeDeliveryReadiness(22,"CONFIRMED","1".repeat(40),"2".repeat(40),
                listOf(MobileDeliveryTarget.APP_PROD),value.deployment?.status=="DEPLOYED" && value.deployment?.healthy==true,null,"UNAVAILABLE",now)
            val state = MobileDeliveryUiState(22, scope) { value.copy(readiness=readiness,
                validation=workflowEvidence(22,value.sourceUpdate?.sourceRevision ?: 1)) }
            state.refresh(); check(state)
        } finally { scope.cancel() }
    }

    @Test fun `outdated base replaces update PR even after four checks and is actionable before validation`() {
        val source=MobileSourceUpdate(UUID.randomUUID(),22,"RESOLVER_COMPLETED","2".repeat(40),4,124,null,
            baseStatus="UPDATE_REQUIRED")
        val rejected=operation("PUBLISH_PR","BLOCKED").copy(errorCode="SOURCE_FINALIZATION_MAIN_MOVED")
        state(snapshot(rejected,operation("PUBLISH_PR","SUCCEEDED")).copy(integration=null,sourceUpdateEnabled=true,sourceUpdate=source)) {
            for (validated in listOf(false,true)) {
                val step=it.currentStep(validated,false,now)
                assertEquals(DeliveryStepAction.UPDATE_BASE,step.action)
                assertEquals(DeliveryStepStatus.ATTENTION,step.status)
                assertTrue(step.message.contains("No repitas el prompt"))
                assertTrue(it.deliveryBeforeValidation(false));assertTrue(it.canUpdateBase(false))
                assertFalse(it.canUpdatePullRequest(validated,false));assertFalse(it.canRecoverSource(validated,false))
            }
            assertFalse(it.canUpdateBase(true));assertFalse(it.deliveryBeforeValidation(true))
            val checking=it.currentStep(false,false,now,validationInProgress=true)
            assertEquals(DeliveryStepStatus.WORKING,checking.status)
            assertEquals(DeliveryStepAction.NONE,checking.action)
            assertTrue(checking.title.contains("Validando"))
        }
    }
    @Test fun `current base requires fresh validation and unconfirmed base only allows reading`() {
        val source=MobileSourceUpdate(UUID.randomUUID(),22,"READY_TO_FINALIZE","2".repeat(40),5,null,null,baseStatus="CURRENT")
        state(snapshot().copy(integration=null,sourceUpdateEnabled=true,sourceUpdate=source)) {
            assertEquals(DeliveryStepAction.NONE,it.currentStep(false,false,now).action)
            assertFalse(it.deliveryBeforeValidation(false));assertFalse(it.canUpdatePullRequest(false,false))
            assertEquals(DeliveryStepAction.UPDATE_PR,it.currentStep(true,false,now).action)
            assertTrue(it.canUpdatePullRequest(true,false));assertFalse(it.canUpdateBase(false))
        }
        for (status in listOf("UNAVAILABLE","NOT_REQUIRED")) {
            state(snapshot().copy(integration=null,sourceUpdateEnabled=true,sourceUpdate=source.copy(baseStatus=status))) {
                assertEquals(DeliveryStepAction.REFRESH,it.currentStep(false,false,now).action)
                assertEquals(DeliveryStepStatus.CHECKING,it.currentStep(true,false,now).status)
                assertTrue(it.deliveryBeforeValidation(false));assertFalse(it.canUpdateBase(false))
                assertFalse(it.canUpdatePullRequest(true,false))
            }
        }
    }
    @Test fun `pending publication and retained finalization recovery precede another base preparation`() {
        val source=MobileSourceUpdate(UUID.randomUUID(),22,"READY_TO_FINALIZE","2".repeat(40),5,null,null,baseStatus="CURRENT")
        state(snapshot(operation("PUBLISH_PR","QUEUED")).copy(integration=null,sourceUpdateEnabled=true,sourceUpdate=source)) {
            assertEquals(DeliveryStepStatus.WORKING,it.currentStep(true,false,now).status)
            assertEquals(DeliveryStepAction.NONE,it.currentStep(true,false,now).action)
            assertFalse(it.canUpdateBase(false));assertFalse(it.canUpdatePullRequest(true,false))
        }
        state(snapshot(operation("PUBLISH_PR","BLOCKED")).copy(integration=null,sourceUpdateEnabled=true,
            sourceUpdate=source.copy(baseStatus="NOT_REQUIRED",recoveryAvailable=true))) {
            assertEquals(DeliveryStepAction.RECOVER_SOURCE,it.currentStep(true,false,now).action)
            assertFalse(it.canUpdateBase(false))
        }
    }
    @Test fun `main moving between observation and click refreshes guidance without a second write`() = runBlocking {
        val scope=CoroutineScope(Job()+Dispatchers.Unconfined)
        try {
            var loads=0;var submissions=0
            var source=MobileSourceUpdate(UUID.randomUUID(),22,"RESOLVER_COMPLETED","2".repeat(40),4,124,null,baseStatus="CURRENT")
            val state=MobileDeliveryUiState(22,scope) { loads++;snapshot().copy(integration=null,sourceUpdateEnabled=true,sourceUpdate=source,
                validation=workflowEvidence(22,4)) }
            state.refresh();assertTrue(state.canUpdatePullRequest(true,false))
            state.submitDelivery(DeliveryStepAction.UPDATE_PR) { submissions++;source=source.copy(baseStatus="UPDATE_REQUIRED")
                throw com.atenea.android.api.AteneaApiException(409,"SOURCE_FINALIZATION_MAIN_MOVED") }
            assertEquals(1,submissions);assertEquals(2,loads)
            assertEquals(DeliveryStepAction.UPDATE_BASE,state.currentStep(true,false,now).action)
            assertTrue(state.actionError!!.contains("Actualizar base con main"))
            assertFalse(state.actionError!!.contains("misma operación"))
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
    @Test fun `preparing and applying work while CI waits without repeating the operation`() {
        for (status in listOf("PREPARING", "WAITING_CI", "APPLYING")) {
            val op = operation("RELEASE", status)
            state(snapshot(op, operation("INTEGRATE", "SUCCEEDED"))) {
                val step = it.currentStep(true, false, now)
                assertEquals(if(status=="WAITING_CI") DeliveryStepStatus.WAITING else DeliveryStepStatus.WORKING, step.status)
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
        val missing=com.atenea.android.api.MobileChangeDeliveryReadiness(22,"CONFIRMED","1".repeat(40),"2".repeat(40),
            listOf(MobileDeliveryTarget.APP_PROD,MobileDeliveryTarget.ANDROID_STABLE),false,
            com.atenea.android.api.MobileAndroidChannel("3".repeat(40),"0.5.127",160,"5".repeat(64),now),"NOT_INCLUDED",now)
        state(snapshot(failed, operation("INTEGRATE", "SUCCEEDED")).copy(deployment = observed,readiness=missing)) {
            val step = it.currentStep(true, false, now)
            assertEquals(DeliveryStepStatus.ATTENTION, step.status)
            assertEquals(DeliveryStepAction.REFRESH, step.action)
            assertEquals(failed.id, step.operation?.id)
        }
        state(snapshot(operation("RELEASE", "SUCCEEDED", MobileDeliveryTarget.ANDROID_STABLE), failed,
            operation("INTEGRATE", "SUCCEEDED")).copy(deployment = observed,readiness=missing.copy(ready=true,androidStatus="INCLUDED"))) {
            assertEquals(DeliveryStepAction.FINALIZE, it.currentStep(true, false, now).action)
        }
    }
    @Test fun `backend is first and a version collision has a real mobile recovery action`() {
        val failed=operation("RELEASE","BLOCKED",MobileDeliveryTarget.ANDROID_STABLE).copy(errorCode="APK_VERSION_NOT_NEWER")
        val facts=com.atenea.android.api.MobileChangeDeliveryReadiness(22,"CONFIRMED","1".repeat(40),"2".repeat(40),
            listOf(MobileDeliveryTarget.APP_PROD,MobileDeliveryTarget.ANDROID_STABLE),false,
            com.atenea.android.api.MobileAndroidChannel("3".repeat(40),"0.5.127",160,"5".repeat(64),now),"NOT_INCLUDED",now)
        val value=snapshot(failed,operation("INTEGRATE","SUCCEEDED")).copy(readiness=facts,androidVersionPreparationEnabled=true)
        state(value) { assertEquals(DeliveryStepAction.PREPARE_BACKEND,it.currentStep(true,false,now).action) }
        val deployed=MobileDeploymentObservation(22,"DEPLOYED","OPERATOR","3".repeat(40),"2".repeat(40),true,now,UUID.randomUUID(),UUID.randomUUID(),"5".repeat(64),null)
        state(value.copy(deployment=deployed)) {
            val step=it.currentStep(true,false,now)
            assertEquals(DeliveryStepAction.PREPARE_ANDROID_VERSION,step.action)
            assertTrue(step.message.contains("sólo incremente"));assertTrue(step.message.contains("original"))
        }
        state(value.copy(deployment=deployed,androidVersionPreparationEnabled=false)) {
            assertEquals(DeliveryStepAction.REFRESH,it.currentStep(true,false,now).action)
        }
        val version=operation("ANDROID_VERSION","READY",MobileDeliveryTarget.ANDROID_STABLE).copy(versionCode=161,versionName="0.5.128")
        state(value.copy(operations=listOf(version)+value.operations,deployment=deployed)) {
            assertEquals(DeliveryStepAction.INTEGRATE_ANDROID_VERSION,it.currentStep(true,false,now).action)
            assertFalse(it.currentStep(true,false,now).message.contains("autenticador"))
        }
        state(value.copy(operations=listOf(version.copy(state="SUCCEEDED"))+value.operations,deployment=deployed)) {
            assertEquals(DeliveryStepAction.PREPARE_ANDROID,it.currentStep(true,false,now).action)
        }
    }
    @Test fun `verified current delivery supersedes historical failure but stale observations and failed rollback do not`() {
        val failed=operation("RELEASE","BLOCKED",MobileDeliveryTarget.ANDROID_STABLE).copy(errorCode="APK_VERSION_NOT_NEWER")
        val facts=com.atenea.android.api.MobileChangeDeliveryReadiness(22,"CONFIRMED","1".repeat(40),"2".repeat(40),
            listOf(MobileDeliveryTarget.ANDROID_STABLE),true,
            com.atenea.android.api.MobileAndroidChannel("3".repeat(40),"0.5.128",161,"5".repeat(64),now),"INCLUDED",now)
        val value=snapshot(failed,operation("INTEGRATE","SUCCEEDED")).copy(readiness=facts)
        state(value) { assertEquals(DeliveryStepAction.FINALIZE,it.currentStep(true,false,now).action);assertEquals(failed,it.operations.first()) }
        state(value.copy(readiness=facts.copy(observedAt=now-61))) { assertEquals(DeliveryStepAction.REFRESH,it.currentStep(true,false,now).action) }
        state(value.copy(readiness=facts.copy(android=facts.android!!.copy(observedAt=now-61)))) {
            assertEquals(DeliveryStepAction.REFRESH,it.currentStep(true,false,now).action);assertFalse(it.canFinalize(true,false))
        }
        state(value.copy(operations=listOf(failed.copy(state="ROLLBACK_FAILED"))+value.operations.drop(1))) {
            assertEquals(DeliveryStepStatus.ATTENTION,it.currentStep(true,false,now).status);assertFalse(it.canFinalize(true,false))
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

    @Test fun `source synchronization is working without offering another recovery`() {
        for (phase in listOf("QUEUED", "PREPARE_CLAIMED", "UNCERTAIN")) {
            val source=MobileSourceUpdate(UUID.randomUUID(),22,phase,"2".repeat(40),2,null,null,false)
            state(snapshot(operation("PUBLISH_PR","SUCCEEDED")).copy(integration=null,sourceUpdateEnabled=true,sourceUpdate=source)) {
                val step=it.currentStep(false,false,now)
                assertEquals(DeliveryStepStatus.WORKING,step.status)
                assertEquals(DeliveryStepAction.NONE,step.action)
                assertFalse(it.canRecoverSource(false,false))
            }
        }
    }

    @Test fun `stopped source recovery says stopped explains cause and preserves same operation`() {
        val source=MobileSourceUpdate(UUID.randomUUID(),22,"ATTENTION","2".repeat(40),2,null,"SOURCE_UPDATE_TARGET_UNAVAILABLE",true)
        state(snapshot(operation("PUBLISH_PR","SUCCEEDED")).copy(integration=null,sourceUpdateEnabled=true,sourceUpdate=source)) {
            val step=it.currentStep(false,false,now)
            assertEquals(DeliveryStepStatus.ATTENTION,step.status)
            assertEquals(DeliveryStepAction.RECOVER_SOURCE,step.action)
            assertTrue(step.message.contains("detenida, no trabajando"))
            assertTrue(step.message.contains("código aprobado"))
            assertEquals(source.id,it.sourceUpdate?.id)
        }
        state(snapshot().copy(integration=null,sourceUpdateEnabled=true,
            sourceUpdate=source.copy(errorCode="SOURCE_UPDATE_TARGET_INVALID",recoveryAvailable=false))) {
            assertEquals(DeliveryStepAction.NONE,it.currentStep(false,false,now).action)
            assertEquals(DeliveryStepStatus.ATTENTION,it.currentStep(false,false,now).status)
        }
    }
}
