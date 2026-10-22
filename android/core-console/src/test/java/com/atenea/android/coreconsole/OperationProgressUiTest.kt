package com.atenea.android.coreconsole

import com.atenea.android.api.*
import java.util.UUID
import java.time.Instant
import kotlinx.coroutines.*
import kotlin.test.*

class OperationProgressUiTest {
    private val now=Instant.now().epochSecond
    private val id=UUID.randomUUID()
    private val operation=MobileDeliveryOperation(id,UUID.randomUUID(),28,"PUBLISH_PR",MobileDeliveryTarget.APP_PROD,
        "a".repeat(40),"WAITING_CI",null,"CI_PENDING",null,null,null,null,null,null,null,createdAt=Instant.ofEpochSecond(now-300).toString())
    private fun github(state: String)=MobileGitHubProgress("a".repeat(40),state,now,"OPERATIONAL",
        listOf(MobileProgressCheck("Validación del cambio","PASSED",now-300,now-200)))
    @Test fun `phases explain queue running closing and real failure without made up percentage`() {
        assertTrue(githubProgressTitle(github("NOT_STARTED")).contains("registre"))
        assertTrue(githubProgressTitle(github("QUEUED")).contains("turno"))
        assertTrue(githubProgressTitle(github("RUNNING")).contains("ejecutando"))
        assertTrue(githubProgressTitle(github("CLOSING")).contains("Pruebas correctas"))
        assertTrue(githubProgressTitle(github("FAILED")).contains("fallida"))
        assertTrue(githubProgressTitle(github("UNAVAILABLE")).contains("por confirmar"))
        assertFalse(githubProgressTitle(github("CLOSING")).contains("%"))
    }
    @Test fun `stale or future observations never look like fresh execution or failure`() {
        assertTrue(progressAge(now-200,now).contains("Sin confirmación reciente"))
        assertTrue(progressAge(now-200,now).contains("no demuestra un fallo"))
        assertTrue(progressAge(now+20,now).contains("hora"))
        assertTrue(progressAge(now-5,now).contains("5 s"))
    }
    @Test fun `closed provider jobs stay waiting and progress never authorizes integration`() = runBlocking<Unit> {
        val progress=MobileOperationProgress(id,28,"PUBLISH_PR","WAITING_CI",now-300,now,now,github("CLOSING"))
        val snapshot=MobileDeliveryState(true,listOf(operation),validation=workflowEvidence(28),progress=progress)
        val state=MobileDeliveryUiState(28,this) { snapshot }
        state.refresh()
        assertEquals(DeliveryStepStatus.WAITING,state.currentStep(true,false,now).status)
        assertEquals(DeliveryStepAction.NONE,state.currentStep(true,false,now).action)
        assertNull(state.integration);assertNotNull(state.progress)
    }
    @Test fun `another operation progress cannot describe current operation`() = runBlocking {
        val progress=MobileOperationProgress(UUID.randomUUID(),28,"PUBLISH_PR","WAITING_CI",now-300,now,now,github("PASSED"))
        val state=MobileDeliveryUiState(28,this) { MobileDeliveryState(true,listOf(operation),validation=workflowEvidence(28),progress=progress) }
        state.refresh();assertNull(state.progress)
        assertEquals(DeliveryStepAction.NONE,state.currentStep(true,false,now).action)
    }
    @Test fun `stale phase only offers read only consultation not another operation`() = runBlocking<Unit> {
        val progress=MobileOperationProgress(id,28,"PUBLISH_PR","WAITING_CI",now-300,now,now,github("RUNNING").copy(observedAt=now-200))
        val state=MobileDeliveryUiState(28,this) { MobileDeliveryState(true,listOf(operation),validation=workflowEvidence(28),progress=progress) }
        state.refresh()
        assertEquals(DeliveryStepStatus.CHECKING,state.currentStep(true,false,now).status)
        assertEquals(DeliveryStepAction.REFRESH,state.currentStep(true,false,now).action)
        assertTrue(state.canConsult)
    }
    @Test fun `PR creation does not hide backend checks still running before integration`() = runBlocking<Unit> {
        val done=operation.copy(state="SUCCEEDED")
        val progress=MobileOperationProgress(id,28,"PR_CHECKS","PENDING",now-300,now,now,github("RUNNING"))
        val state=MobileDeliveryUiState(28,this) { MobileDeliveryState(true,listOf(done),
            integration=MobileDeliveryIntegration(28,"a".repeat(40),"MERGEABLE",true,null),
            validation=workflowEvidence(28),progress=progress) }
        state.refresh()
        assertEquals(DeliveryStepStatus.WORKING,state.currentStep(true,false,now).status)
        assertEquals(DeliveryStepAction.NONE,state.currentStep(true,false,now).action)
        assertEquals("Comprobando la PR en GitHub",state.currentStep(true,false,now).title)
    }
    @Test fun `main advancing offers the existing explicit base update not an endless GitHub wait`() = runBlocking {
        val progress=MobileOperationProgress(id,28,"PR_CHECKS","PENDING",now-300,now,now,github("BASE_CHANGED"))
        for (stale in listOf(false,true)) {
            val state=MobileDeliveryUiState(28,this) { MobileDeliveryState(true,listOf(operation.copy(state="SUCCEEDED")),
                integration=MobileDeliveryIntegration(28,"a".repeat(40),"MERGEABLE",true,null),
                sourceUpdateEnabled=true,validation=workflowEvidence(28),
                progress=if(stale) progress.copy(github=github("BASE_CHANGED").copy(observedAt=now-200)) else progress) }
            state.refresh()
            assertEquals(!stale,state.canUpdateBase(false));assertFalse(state.canUpdateBase(true))
            assertEquals(if(stale) DeliveryStepAction.REFRESH else DeliveryStepAction.UPDATE_BASE,
                state.currentStep(true,false,now).action)
            assertEquals(DeliveryStepStatus.ATTENTION,state.currentStep(true,false,now).status)
            assertTrue(state.currentStep(true,false,now).message.contains("misma PR"))
        }
    }
    @Test fun `background reads keep the consult button stable and last success while waiting`() = runBlocking {
        val blocked=CompletableDeferred<Unit>();val started=CompletableDeferred<Unit>();var count=0
        val state=MobileDeliveryUiState(28,this) {
            if(count++>0) { started.complete(Unit); blocked.await() }
            MobileDeliveryState(true,listOf(operation),validation=workflowEvidence(28))
        }
        state.refresh();val last=state.lastObservedAt;val read=launch(start=CoroutineStart.UNDISPATCHED) { state.refresh() }
        started.await()
        assertTrue(state.refreshing);assertTrue(state.canConsult);assertEquals(last,state.lastObservedAt)
        assertEquals(DeliveryStepStatus.WAITING,state.currentStep(true,false,now).status)
        blocked.complete(Unit);read.join();assertTrue(state.canConsult)
    }
    @Test fun `storage phases explain real work and shortage without fake percentage`() {
        val storage=MobileStorageProgress("a".repeat(40),MobileDeliveryTarget.APP_PROD,"RECLAIMING",now,5_000_000_000,20_000_000_000,15_000_000_000,0)
        assertTrue(storageProgressTitle(storage).contains("Retirando"))
        assertTrue(storageProgressTitle(storage.copy(phase="SPACE_READY")).contains("Espacio preparado"))
        assertTrue(storageCapacityDetail(storage.copy(phase="INSUFFICIENT_SPACE")).contains("15,0 GB"))
        assertTrue(storageCapacityDetail(storage.copy(phase="INSUFFICIENT_SPACE")).contains("No se ha desplegado"))
        assertFalse(storageProgressTitle(storage).contains("%"))
    }
    @Test fun `storage from another source cannot describe the current publication`() = runBlocking {
        val release=operation.copy(kind="RELEASE",state="PREPARING",errorCode=null)
        val storage=MobileStorageProgress("a".repeat(40),MobileDeliveryTarget.APP_PROD,"RECLAIMING",now,5,20,15,0)
        for(source in listOf("a".repeat(40),"b".repeat(40))) {
            val progress=MobileOperationProgress(id,28,"RELEASE","PREPARING",now-300,now,now,null,storage.copy(sourceCommit=source))
            val state=MobileDeliveryUiState(28,this) { MobileDeliveryState(true,listOf(release),validation=workflowEvidence(28),progress=progress) }
            state.refresh();assertEquals(source==release.sourceCommit,state.progress!=null)
            assertTrue(state.canConsult)
        }
    }
}
