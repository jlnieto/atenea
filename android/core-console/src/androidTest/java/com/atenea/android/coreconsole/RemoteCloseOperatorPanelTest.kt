package com.atenea.android.coreconsole

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.atenea.android.api.MobileSessionOperatorState
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals

class RemoteCloseOperatorPanelTest {
    @get:Rule val compose = createComposeRule()

    @Test fun closingShowsOnlyReconciliationOfTheExistingChange() {
        var clicks = 0
        render(closing(), onClick = { clicks++ })
        compose.onNodeWithText("Cerrando el cambio").assertIsDisplayed()
        compose.onNodeWithTag("remote-close-primary-action").assertIsEnabled().performClick()
        assertEquals(1, clicks)
        compose.onNodeWithTag("remote-close-confirmation").assertDoesNotExist()
        compose.onNodeWithText("Crear sesión").assertDoesNotExist()
    }

    @Test fun insufficientRoleDisablesActionAndExplainsAuthority() {
        render(closing().copy(requiredRole = "PLATFORM_ADMINISTRATOR"), role = "ROUTINE_OPERATOR")
        compose.onNodeWithText("Requiere administración de plataforma.").assertIsDisplayed()
        compose.onNodeWithTag("remote-close-primary-action").assertIsNotEnabled()
    }

    @Test fun legacyAdoptionIsUnavailableEvenForAdministrator() {
        render(closing().copy(state = "LEGACY_CLOSE_REQUIRED"))
        compose.onNodeWithTag("remote-close-primary-action").assertIsNotEnabled()
        compose.onNodeWithTag("remote-close-confirmation").assertDoesNotExist()
        compose.onNodeWithText("Confirmar WorkSession 19").assertDoesNotExist()
    }

    @Test fun staleStateRequiresReadOnlyRefreshBeforeReconcilingAgain() {
        render(closing(), action = RemoteCloseActionUiState(requiresRefresh = true,
            error = "El estado cambió. Actualiza antes de continuar."))
        compose.onNodeWithText("El estado cambió. Actualiza antes de continuar.").assertIsDisplayed()
        compose.onNodeWithTag("remote-close-primary-action").assertIsNotEnabled()
    }

    private fun render(state: MobileSessionOperatorState, role: String = "PLATFORM_ADMINISTRATOR",
        action: RemoteCloseActionUiState = RemoteCloseActionUiState(), onClick: () -> Unit = {}) {
        compose.setContent {
            MaterialTheme { RemoteCloseOperatorPanel(state, action, role, onClick) }
        }
    }
    private fun closing() = MobileSessionOperatorState(true, "CLOSING_REMOTE", "Cerrando el cambio",
        "Confirmación pendiente.", "RECONCILE_REMOTE_CLOSE", "Reconciliar cierre", true,
        "ROUTINE_OPERATOR", 21, null)
}
