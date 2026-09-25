package com.atenea.android.coreconsole

import com.atenea.android.api.MobileWorkSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DevelopmentChangeValidationUiStateTest {

    @Test
    fun `unvalidated change exposes enabled action only while idle`() {
        val idle = developmentChangeValidationUiState(session(), false, false, null)
        val running = developmentChangeValidationUiState(session(), true, false, null)

        assertTrue(idle.visible)
        assertTrue(idle.canStart)
        assertEquals("Validar cambio", idle.label)
        assertFalse(running.canStart)
    }

    @Test
    fun `current evidence hides another validation action`() {
        val state = developmentChangeValidationUiState(
            session(validationState = "CURRENT"), false, false, null
        )

        assertTrue(state.visible)
        assertFalse(state.canStart)
        assertEquals("Cambio validado para la revisión actual.", state.message)
    }

    @Test
    fun `pending durable progress is rendered verbatim and cannot duplicate start`() {
        val state = developmentChangeValidationUiState(
            session(), false, true, "Android build (2/4)"
        )

        assertFalse(state.canStart)
        assertEquals("Validando…", state.label)
        assertEquals("Android build (2/4)", state.message)
    }

    private fun session(validationState: String = "NOT_STARTED") = MobileWorkSession(
        id = 21,
        projectId = 1,
        title = "Monitorizar degradación",
        status = "OPEN",
        operationalState = "READY",
        baseBranch = "main",
        workspaceBranch = "atenea/change-59315b6e-59bc-4884-9def-356e1ca86ef4",
        pullRequestUrl = null,
        pullRequestStatus = "NOT_CREATED",
        finalCommitSha = null,
        openedAt = null,
        lastActivityAt = null,
        publishedAt = null,
        closedAt = null,
        closeBlockedState = null,
        closeBlockedReason = null,
        closeBlockedAction = null,
        closeRetryable = false,
        developmentChangeKey = "59315b6e-59bc-4884-9def-356e1ca86ef4",
        developmentChangeValidationState = validationState,
        developmentChangeSourceState = "DIRTY"
    )
}
