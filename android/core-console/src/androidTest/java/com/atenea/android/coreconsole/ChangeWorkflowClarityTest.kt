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
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ChangeWorkflowClarityTest {
    @get:Rule val compose = createComposeRule()

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
