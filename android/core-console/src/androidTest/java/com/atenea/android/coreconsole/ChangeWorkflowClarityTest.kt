package com.atenea.android.coreconsole

import androidx.compose.material3.Text
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import com.atenea.android.api.AteneaApiClient
import com.atenea.android.api.MobileDeliveryState
import com.atenea.android.api.MobileSourceUpdate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.util.UUID
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ChangeWorkflowClarityTest {
    @get:Rule val compose = createComposeRule()

    @Test fun outdatedBaseActionIsVisibleWithoutOfferingObsoleteValidationOrAnotherPr() {
        assertOutdatedBaseAction(validated=false)
    }
    @Test fun outdatedBaseActionReplacesPublishEvenWhenTheRevisionIsValidated() {
        assertOutdatedBaseAction(validated=true)
    }
    private fun assertOutdatedBaseAction(validated: Boolean) {
        var validations=0
        val scope=CoroutineScope(Job()+Dispatchers.Unconfined)
        try {
            val api=AteneaApiClient("https://synthetic.invalid",{ null },operatorRoleProvider={ "PLATFORM_ADMINISTRATOR" })
            val state=MobileDeliveryUiState(22,scope) {
                MobileDeliveryState(true,emptyList(),sourceUpdateEnabled=true,
                    sourceUpdate=MobileSourceUpdate(UUID.randomUUID(),22,"RESOLVER_COMPLETED","1".repeat(40),4,124,null,
                        baseStatus="UPDATE_REQUIRED"))
            }
            runBlocking { state.refresh() }
            compose.setContent {
                AteneaOperatorTheme {
                    WorkSessionChangePanel(22,"Mismo cambio",
                        DevelopmentChangeValidationUiState(true,!validated,"Validar cambio","Revisión pendiente",current=validated),
                        null,false,null,{}, { validations++ },deliveryBeforeValidation=state.deliveryBeforeValidation(false)) {
                        MobileDeliveryPanel(api,22,validated=validated,runInProgress=false,state=state)
                    }
                }
            }
            // Render the actual panel/button, not a hand-written test substitute.
            // Clicking the fixed authenticated API is covered by MobileDeliveryTest.
            compose.onNode(hasText("Actualizar base con main") and hasClickAction()).assertIsDisplayed()
            compose.onNodeWithText("Validar cambio").assertDoesNotExist()
            compose.onNodeWithText("Actualizar la misma PR").assertDoesNotExist()
            assertEquals(0,validations)
            val bitmap=compose.onRoot().captureToImage().asAndroidBitmap()
            File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,"stale-base-$validated.png")
                .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
        } finally { scope.cancel() }
    }

    @Test fun readyConfirmationPrecedesCompletedValidationAndTechnicalDetails() {
        var confirmations = 0
        var validations = 0
        var reads = 0
        compose.setContent {
            AteneaOperatorTheme {
                ConversationSurface(title = "Rediseñar vista ajustes", status = "Preparación lista",
                    turns = emptyList(), input = "", pending = false, placeholder = "Prompt",
                    onInputChange = {}, onSend = {}, onBack = {}, onRefresh = {}, error = null,
                    changeContent = {
                WorkSessionChangePanel(22, "Rediseñar vista ajustes",
                    DevelopmentChangeValidationUiState(true, false, "Validar cambio", "Validado", current = true),
                    null, false, null, { reads++ }, { validations++ }) {
                    DeliveryStepCard(DeliveryStepUi(DeliveryStepStatus.ACTION, "Backend PROD: listo para publicar",
                        "La preparación terminó. No se publicará sola: confirma con tu autenticador antes de que caduque.")) {
                        AteneaButton("Confirmar Backend PROD", onClick = { confirmations++ })
                    }
                }
                    })
            }
        }
        compose.onNodeWithContentDescription("Opciones de conversación").performClick()
        compose.onNodeWithText("Cambio").performClick()
        compose.onNodeWithText("Te toca a ti").assertIsDisplayed()
        compose.onNodeWithText("Confirmar Backend PROD").assertIsDisplayed()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "change-ready-small-screen.png")
            .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        compose.onNodeWithText("Confirmar Backend PROD").performClick()
        compose.onNodeWithText("Consultar resultado").assertDoesNotExist()
        assertEquals(1, confirmations); assertEquals(0, validations); assertEquals(0, reads)
    }
    @Test fun completedValidationDetailsAreExplicitlyExpandableWithoutRepeatingTests() {
        var validations = 0
        var reads = 0
        compose.setContent {
            AteneaOperatorTheme {
                WorkSessionChangePanel(22, "Cambio actual",
                    DevelopmentChangeValidationUiState(true, false, "Validar cambio", "Validado", current = true),
                    null, false, null, { reads++ }, { validations++ }) { Text("Paso actual") }
            }
        }
        compose.onNodeWithText("Consultar resultado").assertDoesNotExist()
        compose.onNodeWithText("Ver pruebas y detalles").performClick()
        compose.onNodeWithText("Consultar resultado").assertIsDisplayed().performClick()
        assertEquals(1, reads); assertEquals(0, validations)
        compose.onNodeWithText("Ocultar pruebas y detalles").performClick()
        compose.onNodeWithText("Consultar resultado").assertDoesNotExist()
    }
    @Test fun runningValidationExplainsWaitingWithoutShowingDeliveryActions() {
        compose.setContent {
            AteneaOperatorTheme {
                WorkSessionChangePanel(22, "Cambio actual",
                    DevelopmentChangeValidationUiState(true, false, "Validando…", "2/4", inProgress = true),
                    null, false, null, {}, {}) { Text("Crear PR") }
            }
        }
        compose.onNodeWithText("Atenea está trabajando").assertIsDisplayed()
        compose.onNodeWithText("Atenea está comprobando esta revisión. Puedes salir de la app; no repitas la validación.").assertIsDisplayed()
        compose.onNodeWithText("Crear PR").assertDoesNotExist()
    }
}
