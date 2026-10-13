package com.atenea.android.coreconsole

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.atenea.android.api.MobileProjectOverview
import com.atenea.android.api.MobileProjectSessionOverview
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ProjectsRecoveryCardTest {
    @get:Rule val compose = createComposeRule()

    @Test fun projectOffersOnlyChangeCreationAndHistory() {
        var starts = 0
        var lists = 0
        compose.setContent { MaterialTheme {
            ProjectOverviewCard(project(), false, true, {}, { lists++ }, { starts++ })
        } }
        compose.onNodeWithText("Nuevo cambio").assertIsDisplayed().performClick()
        compose.onNodeWithText("Ver cambios").assertIsDisplayed().performClick()
        for (text in listOf("Nueva sesion", "Nueva sesión", "Rescate", "Titulo para nueva sesion")) {
            compose.onAllNodesWithText(text).assertCountEquals(0)
        }
        compose.onAllNodesWithText("Continuar").assertCountEquals(0)
        assertEquals(1, starts)
        assertEquals(1, lists)
    }

    @Test fun continueOpensTheExistingChangeConversationWithoutResolvingAnotherSession() {
        var opened: Long? = null
        val active = project().copy(session = MobileProjectSessionOverview(
            sessionId = 21, status = "OPEN", title = "Cambio actual", runInProgress = false,
            closeBlockedState = null, pullRequestStatus = null, lastActivityAt = null, recoveryPending = false))
        compose.setContent { MaterialTheme {
            ProjectOverviewCard(active, false, true, { opened = it }, {}, {})
        } }
        compose.onNodeWithText("Continuar").assertIsDisplayed().performClick()
        assertEquals(21L, opened)
        compose.onAllNodesWithText("Abrir sesion").assertCountEquals(0)
    }

    private fun project() = MobileProjectOverview(1, "Atenea", null, "main", null)
}
