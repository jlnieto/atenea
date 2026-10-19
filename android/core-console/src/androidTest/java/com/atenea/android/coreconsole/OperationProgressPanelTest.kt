package com.atenea.android.coreconsole

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import com.atenea.android.api.*
import kotlinx.coroutines.*
import java.io.File
import java.time.Instant
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class OperationProgressPanelTest {
    @get:Rule val compose=createComposeRule()
    private val now=Instant.now().epochSecond
    private val id=UUID.randomUUID()
    private fun proof(kind: String,state: String,githubState: String?=null): MobileDeliveryState {
        val op=MobileDeliveryOperation(id,UUID.randomUUID(),28,kind,MobileDeliveryTarget.APP_PROD,"a".repeat(40),state,
            null,null,null,null,null,null,null,null,null,createdAt=Instant.ofEpochSecond(now-300).toString())
        val attempts=listOf("BACKEND_TEST","WEB_BUILD","ANDROID_BUILD","PLAYWRIGHT_ACCEPTANCE").map {
            DevelopmentChangeValidationAttempt(UUID.randomUUID(),28,it,"SUCCEEDED","b".repeat(64),"synthetic",0,"PASS",null,null)
        }
        val validation=DevelopmentChangeValidationEvidence(id,28,1,"b".repeat(64),"CURRENT",4,4,attempts,attempts.last())
        val github=githubState?.let { MobileGitHubProgress("a".repeat(40),it,now,"DEGRADED",listOf(
            MobileProgressCheck("Validación del cambio",when(it) { "QUEUED"->"QUEUED";"RUNNING"->"RUNNING";else->"PASSED" },if(it=="QUEUED") null else now-250,
                if(it in setOf("QUEUED","RUNNING")) null else now-50))) }
        return MobileDeliveryState(true,listOf(op),validation=validation,
            progress=MobileOperationProgress(id,28,kind,state,now-300,now,now,github))
    }
    @Test fun queuedAndClosedJobsExplainExternalWaitingWithoutOfferingAnotherAction() {
        val scope=CoroutineScope(Job()+Dispatchers.Unconfined)
        try {
            var snapshot=proof("PUBLISH_PR","WAITING_CI","QUEUED");var reads=0
            val state=MobileDeliveryUiState(28,scope) { reads++;snapshot };runBlocking { state.refresh() }
            val api=AteneaApiClient("https://synthetic.invalid",{null},operatorRoleProvider={"PLATFORM_ADMINISTRATOR"})
            compose.setContent { AteneaOperatorTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
                MobileDeliveryPanel(api,28,true,false,state)
            } } }
            compose.onNodeWithText("Esperando a otro servicio").assertIsDisplayed()
            compose.onNodeWithText("Esperando turno en GitHub").assertIsDisplayed()
            compose.onNode(hasText("Integrar cambio") and hasClickAction()).assertDoesNotExist()
            screenshot("queued")
            compose.runOnIdle { snapshot=proof("PUBLISH_PR","WAITING_CI","CLOSING");runBlocking { state.refresh() } }
            compose.onNodeWithText("Pruebas correctas · esperando confirmación de GitHub").assertIsDisplayed()
            compose.onNodeWithText("1 de 1 comprobaciones correctas").assertIsDisplayed()
            compose.onNodeWithText("GitHub informa de una incidencia",substring=true).assertExists()
            compose.onNode(hasText("Crear PR") and hasClickAction()).assertDoesNotExist()
            screenshot("closing")
            compose.runOnIdle { assertEquals(2,reads) }
        } finally { scope.cancel() }
    }
    @Test fun preparationUsesTheSamePassiveClockWithoutPollingOrRestarting() {
        val scope=CoroutineScope(Job()+Dispatchers.Unconfined)
        try {
            var reads=0;val state=MobileDeliveryUiState(28,scope) { reads++;proof("RELEASE","PREPARING") }
            runBlocking { state.refresh() }
            val api=AteneaApiClient("https://synthetic.invalid",{null},operatorRoleProvider={"PLATFORM_ADMINISTRATOR"})
            compose.setContent { AteneaOperatorTheme { MobileDeliveryPanel(api,28,true,false,state) } }
            compose.onNodeWithText("Preparando Backend PROD").assertIsDisplayed()
            compose.onNodeWithText("Desde la solicitud:",substring=true).assertIsDisplayed()
            compose.onNodeWithText("Última consulta confirmada:",substring=true).assertIsDisplayed()
            compose.mainClock.advanceTimeBy(2_000)
            compose.runOnIdle { assertEquals(1,reads) }
            screenshot("preparing")
        } finally { scope.cancel() }
    }
    @Test fun existingPrKeepsItsChecksVisibleInsteadOfPrematureIntegrationButton() {
        val scope=CoroutineScope(Job()+Dispatchers.Unconfined)
        try {
            val original=proof("PUBLISH_PR","WAITING_CI","RUNNING")
            val snapshot=original.copy(operations=original.operations.map { it.copy(state="SUCCEEDED") },
                integration=MobileDeliveryIntegration(28,"a".repeat(40),"MERGEABLE",true,null),
                progress=original.progress!!.copy(kind="PR_CHECKS",state="PENDING"))
            val state=MobileDeliveryUiState(28,scope) { snapshot };runBlocking { state.refresh() }
            val api=AteneaApiClient("https://synthetic.invalid",{null},operatorRoleProvider={"PLATFORM_ADMINISTRATOR"})
            compose.setContent { AteneaOperatorTheme { MobileDeliveryPanel(api,28,true,false,state) } }
            compose.onNodeWithText("Comprobando la PR en GitHub").assertIsDisplayed()
            compose.onNodeWithText("GitHub está ejecutando las comprobaciones").assertIsDisplayed()
            compose.onNode(hasText("Integrar cambio") and hasClickAction()).assertDoesNotExist()
            screenshot("pr-checks")
        } finally { scope.cancel() }
    }
    private fun screenshot(name: String) {
        File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,"operation-progress-$name.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it)
        }
    }
}
