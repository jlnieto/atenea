package com.atenea.android.coreconsole

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import com.atenea.android.api.*
import kotlinx.coroutines.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class AndroidDeliveryGuidancePanelTest {
    @get:Rule val compose=createComposeRule()
    private val now=System.currentTimeMillis()/1000
    private fun op(kind: String,state: String,target: MobileDeliveryTarget=MobileDeliveryTarget.APP_PROD,error: String?=null)=
        MobileDeliveryOperation(UUID.randomUUID(),UUID.randomUUID(),22,kind,target,"1".repeat(40),state,null,error,null,null,null,null,"2".repeat(40),null,null)
    private fun snapshot(backendDeployed: Boolean)=MobileDeliveryState(true,listOf(
        op("RELEASE","BLOCKED",MobileDeliveryTarget.ANDROID_STABLE,"APK_VERSION_NOT_NEWER"),op("INTEGRATE","SUCCEEDED")),
        integration=MobileDeliveryIntegration(22,"1".repeat(40),"MERGED",false,null),releaseRecoveryEnabled=true,completionEnabled=true,
        deployment=MobileDeploymentObservation(22,if (backendDeployed) "DEPLOYED" else "NOT_INCLUDED",if (backendDeployed) "OPERATOR" else null,
            "3".repeat(40),"2".repeat(40),backendDeployed,now,UUID.randomUUID(),UUID.randomUUID(),"4".repeat(64),null),
        readiness=MobileChangeDeliveryReadiness(22,"CONFIRMED","1".repeat(40),"2".repeat(40),
            listOf(MobileDeliveryTarget.APP_PROD,MobileDeliveryTarget.ANDROID_STABLE),false,
            MobileAndroidChannel("3".repeat(40),"0.5.127",160,"4".repeat(64),now),"NOT_INCLUDED",now),
        validation=workflowEvidenceForUi(),androidVersionPreparationEnabled=true)
    // The UI test uses a real four-operation current observation, not the composable's validated argument alone.
    private fun workflowEvidenceForUi(): DevelopmentChangeValidationEvidence {
        val attempts=listOf("BACKEND_TEST","WEB_BUILD","ANDROID_BUILD","PLAYWRIGHT_ACCEPTANCE").map {
            DevelopmentChangeValidationAttempt(UUID.randomUUID(),22,it,"SUCCEEDED","4".repeat(64),"synthetic",0,"PASS",null,null)
        }
        return DevelopmentChangeValidationEvidence(UUID.randomUUID(),22,6,"4".repeat(64),"CURRENT",4,4,attempts,attempts.last())
    }

    @Test fun backendIsFirstAndIrrelevantWorkerControlsAreAbsent() { render(false,"Preparar Backend PROD","delivery-backend-first.png") }
    @Test fun versionCollisionOffersTheVersionActionAndNoConfusingOtherPublicationButtons() { render(true,"Preparar versión Android nueva","delivery-version-recovery.png") }
    @Test fun backgroundPollLeavesConsultEnabledAndManualClickAcknowledgesTheSameResult() {
        val scope=CoroutineScope(Job()+Dispatchers.Unconfined)
        try {
            val value=snapshot(true).let { it.copy(readiness=it.readiness!!.copy(androidStatus="UNAVAILABLE",android=null)) }
            var gate: CompletableDeferred<MobileDeliveryState>?=null
            val state=MobileDeliveryUiState(22,scope) { gate?.await() ?: value }
            runBlocking { state.refresh() }
            val api=AteneaApiClient("https://synthetic.invalid",{null},operatorRoleProvider={"PLATFORM_ADMINISTRATOR"})
            compose.setContent { AteneaOperatorTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
                MobileDeliveryPanel(api,22,true,false,state)
            } } }
            compose.runOnIdle { gate=CompletableDeferred();scope.launch { state.refresh() } }
            compose.onNode(hasText("Consultar estado") and hasClickAction()).assertIsDisplayed().assertIsEnabled().performClick()
            compose.onNode(hasText("Consultando…") and hasClickAction()).assertIsNotEnabled()
            compose.runOnIdle { gate!!.complete(value) }
            compose.onNode(hasText("Consultar estado") and hasClickAction()).assertIsEnabled()
            compose.onNodeWithText("Estado consultado: sin cambios.",substring=true).assertIsDisplayed()
        } finally { scope.cancel() }
    }
    private fun render(backend: Boolean,label: String,screenshot: String) {
        val scope=CoroutineScope(Job()+Dispatchers.Unconfined)
        try {
            val api=AteneaApiClient("https://synthetic.invalid",{null},operatorRoleProvider={"PLATFORM_ADMINISTRATOR"})
            val state=MobileDeliveryUiState(22,scope) { snapshot(backend) }
            runBlocking { state.refresh() }
            compose.setContent { AteneaOperatorTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
                MobileDeliveryPanel(api,22,validated=true,runInProgress=false,state=state)
            } } }
            compose.onNodeWithText(label).assertIsDisplayed().assertIsEnabled()
            compose.onNodeWithText("Preparar Worker AX42").assertDoesNotExist()
            compose.onNodeWithText("Otras publicaciones (Worker / Android)").assertDoesNotExist()
            val image=compose.onRoot().captureToImage().asAndroidBitmap()
            File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,screenshot).outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) }
        } finally { scope.cancel() }
    }
}
