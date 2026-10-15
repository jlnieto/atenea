package com.atenea.android.coreconsole

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.atenea.android.api.ChangeHelp
import com.atenea.android.api.ChangeHelpDestination
import com.atenea.android.api.ChangeHelpNavigation
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class ChangeHelpNavigationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun changeAttentionAndHelpLiveOnlyInConversationMenu() {
        compose.setContent {
            ConversationSurface(title = "Sesión actual", status = "Validación bloqueada", turns = emptyList(),
                input = "", pending = false, placeholder = "Prompt", onInputChange = {}, onSend = {},
                onBack = {}, onRefresh = {}, error = null, changeNeedsAttention = true,
                changeContent = { Text("Panel del cambio") }, helpContent = { _ -> Text("Ayuda independiente") })
        }
        compose.onNodeWithText("Cambio · necesita atención").assertDoesNotExist()
        compose.onNodeWithText("Ayuda con este cambio").assertDoesNotExist()
        compose.onNodeWithContentDescription("Opciones de conversación").performClick()
        compose.onNodeWithText("Ayuda con este cambio").assertIsDisplayed()
        compose.onNodeWithText("Abrir Core").assertDoesNotExist()
        compose.onNodeWithText("Cambio · necesita atención").assertIsDisplayed().performClick()
        compose.onNodeWithText("Panel del cambio").assertIsDisplayed()
    }

    @Test fun helpIsAccessibleWhenConversationFailedAndComposerIsDisabled() {
        var sent = 0
        compose.setContent {
            ConversationSurface(title = "Cambio actual", status = "Necesita atención", turns = emptyList(),
                input = "", pending = false, placeholder = "Prompt", composerEnabled = false,
                onInputChange = {}, onSend = { sent++ }, onBack = {}, onRefresh = {},
                error = "No se pudo cargar la conversación", helpContent = { _ -> Text("Ayuda independiente") })
        }
        compose.onNodeWithContentDescription("Opciones de conversación").performClick()
        compose.onNodeWithText("Ayuda con este cambio").performClick()
        compose.onNodeWithText("Ayuda independiente").assertIsDisplayed()
        assertEquals(0, sent)
    }

    @Test fun navigatingToValidationOnlyOpensPanelAndPreservesPromptDraft() {
        var sent = 0
        var validated = 0
        compose.setContent {
            var input by remember { mutableStateOf("") }
            ConversationSurface(title = "Cambio actual", status = "Pruebas pendientes", turns = emptyList(),
                input = input, pending = false, placeholder = "Prompt", onInputChange = { input = it },
                onSend = { sent++ }, onBack = {}, onRefresh = {}, error = null,
                changeContent = { AteneaButton("Validar cambio", onClick = { validated++ }) },
                helpContent = { navigate -> Column {
                    Text("El código está guardado. Pruebas pendientes.")
                    AteneaButton("Ir a Validar cambio", onClick = { navigate(ChangeHelpDestination.CHANGE) })
                } })
        }
        compose.onNodeWithTag("conversation-input").performTextReplacement("Mi borrador")
        compose.onNodeWithContentDescription("Opciones de conversación").performClick()
        compose.onNodeWithText("Ayuda con este cambio").performClick()
        compose.onNodeWithText("Ir a Validar cambio").performClick()
        compose.onNodeWithText("Validar cambio").assertIsDisplayed()
        assertEquals(0, sent); assertEquals(0, validated)
        compose.onNodeWithContentDescription("Volver").performClick()
        compose.onNodeWithTag("conversation-input").assertTextEquals("Mi borrador")
    }

    @Test fun simplerExplanationIsAHelpQuestionNotNavigationOrExecution() {
        var question: Pair<String, Boolean>? = null
        var navigations = 0
        compose.setContent {
            AteneaOperatorTheme {
                ChangeHelpPanel(listOf(ChangeHelpTurn(null, fixture())), "", false, null,
                    onQuestionChange = {}, onAsk = { text, simple -> question = text to simple },
                    onRefresh = {}, onNavigate = { navigations++ })
            }
        }
        compose.onNodeWithText("Más fácil").performClick()
        assertEquals("No lo entiendo. Explícamelo más fácil" to true, question)
        assertEquals(0, navigations)
    }

    private fun fixture() = ChangeHelp(22, UUID.fromString("a4ff9100-2e8a-46df-911f-53874886701e"), 1,
        "2026-10-14T08:00:00Z", "VALIDATION_REQUIRED", "El cambio está guardado. Falta comprobarlo.",
        "Ahora valida el cambio.", listOf("0/4 comprobaciones de esta revisión"),
        ChangeHelpNavigation(ChangeHelpDestination.CHANGE, "Ir a Validar cambio", "Sólo abre el panel."),
        listOf("¿Qué hago ahora?"), "RECORDED_STATE")
}
