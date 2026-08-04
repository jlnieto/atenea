package com.atenea.android.coreconsole

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.atenea.android.api.LegacyRemoteClosePlan
import com.atenea.android.api.MobileSessionOperatorState
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals

class RemoteCloseOperatorPanelTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun showsStateBlockerAndOnlyAuthorizedPrimaryAction() {
        var clicks = 0
        compose.setContent {
            MaterialTheme {
                RemoteCloseOperatorPanel(
                    serverState = blockedState(),
                    actionState = RemoteCloseActionUiState(),
                    operatorRole = "PLATFORM_ADMINISTRATOR",
                    onPrimaryAction = { clicks += 1 },
                    onConfirm = {},
                    onCancel = {}
                )
            }
        }

        compose.onNodeWithText("Bloqueada por una sesión cerrada").assertIsDisplayed()
        compose.onNodeWithText("Otra sesión cerrada conserva la capacidad necesaria y no puede reintentarse todavía.").assertIsDisplayed()
        compose.onNodeWithTag("remote-close-primary-action").assertIsEnabled().performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun disablesActionAndExplainsMissingAuthority() {
        compose.setContent {
            MaterialTheme {
                RemoteCloseOperatorPanel(
                    serverState = blockedState(),
                    actionState = RemoteCloseActionUiState(),
                    operatorRole = "ROUTINE_OPERATOR",
                    onPrimaryAction = {},
                    onConfirm = {},
                    onCancel = {}
                )
            }
        }

        compose.onNodeWithText("Requiere administración de plataforma.").assertIsDisplayed()
        compose.onNodeWithTag("remote-close-primary-action").assertIsNotEnabled()
    }

    @Test
    fun confirmationKeepsRetainedStateCopyAndVisibleControls() {
        compose.setContent {
            MaterialTheme {
                RemoteCloseOperatorPanel(
                    serverState = blockedState(),
                    actionState = RemoteCloseActionUiState(plan = plan()),
                    operatorRole = "PLATFORM_ADMINISTRATOR",
                    onPrimaryAction = {},
                    onConfirm = {},
                    onCancel = {}
                )
            }
        }

        compose.onNodeWithTag("remote-close-confirmation").assertIsDisplayed()
        compose.onNodeWithText("Se retirará únicamente su ownership remoto activo. El historial, Git, runs y adjuntos permanecerán conservados.").assertIsDisplayed()
        compose.onNodeWithTag("remote-close-confirm-action").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("Cancelar").assertIsDisplayed()
    }

    private fun blockedState() = MobileSessionOperatorState(
        surfaceEnabled = true,
        state = "CLOSED_OWNER_BLOCKS_CAPACITY",
        title = "Bloqueada por una sesión cerrada",
        blocker = "Otra sesión cerrada conserva la capacidad necesaria y no puede reintentarse todavía.",
        primaryAction = "RECONCILE_REMOTE_CLOSE",
        primaryActionLabel = "Reconciliar cierre",
        primaryActionAvailable = true,
        requiredRole = "PLATFORM_ADMINISTRATOR",
        targetWorkSessionId = 16,
        targetAgentRunId = 96
    )

    private fun plan() = LegacyRemoteClosePlan(
        planId = "00000000-0000-0000-0000-000000000016",
        workSessionId = 16,
        operation = "RECONCILE_REMOTE_CLOSE",
        state = "READY_FOR_CONFIRMATION",
        requiredRole = "PLATFORM_ADMINISTRATOR",
        ownershipFingerprintSha256 = "a".repeat(64),
        expiresAt = "2026-08-04T18:00:00Z",
        consumed = false,
        expectedImpact = "Retirar ownership remoto activo de esta sesión.",
        valuesExposed = false,
        createdAt = "2026-08-04T17:55:00Z"
    )
}
