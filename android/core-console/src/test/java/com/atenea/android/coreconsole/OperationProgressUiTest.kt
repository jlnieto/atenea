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
    @Test fun `background reads keep the consult button stable and last success while waiting`() = runBlocking {
        val blocked=CompletableDeferred<Unit>();var count=0
        val state=MobileDeliveryUiState(28,this) {
            if(count++>0) blocked.await()
            MobileDeliveryState(true,listOf(operation),validation=workflowEvidence(28))
        }
        state.refresh();val last=state.lastObservedAt;val read=launch(start=CoroutineStart.UNDISPATCHED) { state.refresh() }
        assertTrue(state.refreshing);assertTrue(state.canConsult);assertEquals(last,state.lastObservedAt)
        assertEquals(DeliveryStepStatus.WAITING,state.currentStep(true,false,now).status)
        blocked.complete(Unit);read.join();assertTrue(state.canConsult)
    }
}
