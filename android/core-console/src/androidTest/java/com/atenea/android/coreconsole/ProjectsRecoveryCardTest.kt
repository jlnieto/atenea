package com.atenea.android.coreconsole

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.atenea.android.api.MobileProjectOverview
import com.atenea.android.api.MobileProjectSessionOverview
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ProjectsRecoveryCardTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun pendingRecoveryKeepsRecoveryActionAndAlsoOffersIndependentNewChange() {
        var opens = 0
        var newChanges = 0
        var viewsChanges = 0
        var rescues = 0
        compose.setContent {
            MaterialTheme {
                ProjectOverviewCard(
                    project = recoveryProject(),
                    draftTitle = "",
                    pending = false,
                    newChangePending = false,
                    actionsEnabled = true,
                    onDraftTitleChange = {},
                    onOpenSession = { opens += 1 },
                    onOpenChanges = { viewsChanges += 1 },
                    onNewDevelopmentChange = { newChanges += 1 },
                    onOpenRescue = { rescues += 1 }
                )
            }
        }

        compose.onNodeWithText("Nueva sesión pendiente").assertIsDisplayed()
        compose.onNodeWithText(
            "La sesión anterior ya está cerrada. Continúa la creación de su única sucesora vacía."
        ).assertIsDisplayed()
        compose.onAllNodesWithText("Titulo para nueva sesion").assertCountEquals(0)
        compose.onAllNodesWithText("Nueva sesion").assertCountEquals(0)
        compose.onAllNodesWithText("Rescate").assertCountEquals(0)
        compose.onNodeWithText("Nuevo cambio").assertIsDisplayed().performClick()
        compose.onNodeWithText("Ver cambios").assertIsDisplayed().performClick()
        compose.onNodeWithTag("project-recovery-action").assertIsDisplayed().performClick()

        assertEquals(1, opens)
        assertEquals(1, newChanges)
        assertEquals(1, viewsChanges)
        assertEquals(0, rescues)
    }

    @Test
    fun openSessionDoesNotHideNewChangeAction() {
        var newChanges = 0
        compose.setContent {
            MaterialTheme {
                ProjectOverviewCard(
                    project = recoveryProject().copy(
                        session = recoveryProject().session?.copy(
                            status = "OPEN",
                            recoveryPending = false
                        )
                    ),
                    draftTitle = "",
                    pending = false,
                    newChangePending = false,
                    actionsEnabled = true,
                    onDraftTitleChange = {},
                    onOpenSession = {},
                    onOpenChanges = {},
                    onNewDevelopmentChange = { newChanges += 1 },
                    onOpenRescue = {}
                )
            }
        }

        compose.onNodeWithText("Abrir sesion").assertIsDisplayed()
        compose.onNodeWithText("Nuevo cambio").assertIsDisplayed().performClick()

        assertEquals(1, newChanges)
    }

    private fun recoveryProject() = MobileProjectOverview(
        projectId = 1,
        projectName = "Atenea",
        description = "Self-hosted Atenea source repository",
        defaultBaseBranch = "main",
        session = MobileProjectSessionOverview(
            sessionId = 17,
            status = "CLOSED",
            title = "Validación sintética del cierre remoto",
            runInProgress = false,
            closeBlockedState = null,
            pullRequestStatus = null,
            lastActivityAt = "2026-08-09T10:00:00Z",
            recoveryPending = true
        )
    )
}
