package com.atenea.android.coreconsole

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.atenea.android.api.MobileDevelopmentChange
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class ContinueDevelopmentChangeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun readyChangeOffersContinuationWithoutTechnicalSessionStep() {
        var continued = 0
        compose.setContent {
            AteneaOperatorTheme {
                DevelopmentChangeCard(change(), 1, {}, onPrepare = { continued++ })
            }
        }
        compose.onNodeWithText("Vincular sesión").assertDoesNotExist()
        compose.onNodeWithText("Sin WorkSession activa.").assertDoesNotExist()
        compose.onNodeWithText("Continuar cambio").assertIsDisplayed().performClick()
        assertEquals(1, continued)
    }

    @Test fun preparationKeepsVisibleDisabledProgressWithoutRepeatingAction() {
        compose.setContent {
            AteneaOperatorTheme {
                DevelopmentChangeCard(change(), 1, {}, onPrepare = {}, continuing = true)
            }
        }
        compose.onNodeWithText("Abriendo conversación…").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText("Vincular sesión").assertDoesNotExist()
    }

    private fun change() = MobileDevelopmentChange(
        changeKey = UUID.fromString("e726c66f-3952-4748-9870-f61cd154a6b5"),
        projectId = 1, title = "Ajustar alertas servidor", status = "OPEN", workspaceState = "READY",
        activeSessionId = null, primaryActionLabel = "Vincular sesión", updatedAt = null,
        primaryActionKind = "BIND_SESSION", primaryActionEnabled = true)
}
