package com.atenea.android.coreconsole

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.atenea.android.api.ValidationRecovery
import com.atenea.android.api.ValidationRecoveryOperation
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ValidationRecoveryPanelTest {
    @get:Rule val compose = createComposeRule()
    private val id = UUID.fromString("a4ff9100-2e8a-46df-911f-53874886701e")
    private fun value(repair: String? = null, known: Boolean = true) = ValidationRecovery(26, id, 1,
        "UNSUPPORTED_DEPENDENCY_MANIFEST", known, true,
        repair?.let { ValidationRecoveryOperation(id, "REPAIR", it, 1, null, null) }, null)

    @Test fun knownFailureOffersPreparationNotAnotherValidationAndRefreshOnlyReads() {
        var prepare = 0; var apply = 0; var refresh = 0
        compose.setContent { AteneaOperatorTheme { ValidationRecoveryPanel(value(), false, null, {},
            { refresh++ }, { prepare++ }, { apply++ }, {}, {}, {}, {}, {}) } }
        compose.onNodeWithText("Preparar reparación").assertIsDisplayed().performClick()
        compose.onNodeWithText("Consultar resultado").performScrollTo().performClick()
        assertEquals(1, prepare); assertEquals(0, apply); assertEquals(1, refresh)
        compose.onNodeWithText("Validar cambio").assertDoesNotExist()
    }
    @Test fun readyPlanRequiresExplicitApplicationAndCanBeDiscarded() {
        var apply = 0; var cancel = 0
        compose.setContent { AteneaOperatorTheme { ValidationRecoveryPanel(value("READY"), false, null, {},
            {}, {}, { apply++ }, {}, { cancel++ }, {}, {}, {}) } }
        assertEquals(0, apply)
        compose.onNodeWithText("Aplicar reparación").performClick(); assertEquals(1, apply)
        compose.onNodeWithText("Descartar preparación sin cambiar código").performScrollTo().performClick()
        assertEquals(1, cancel)
    }
    @Test fun inProgressDoesNotOfferDuplicatePreparationApplicationOrInvestigation() {
        compose.setContent { AteneaOperatorTheme { ValidationRecoveryPanel(value("APPLYING"), false, null,
            {}, {}, {}, {}, {}, {}, {}, {}, {}) } }
        compose.onNodeWithText("Atenea está trabajando").assertIsDisplayed()
        compose.onNodeWithText("Aplicar reparación").assertDoesNotExist()
        compose.onNodeWithText("Preparar reparación").assertDoesNotExist()
        compose.onNodeWithText("Investigar este bloqueo").assertDoesNotExist()
    }
    @Test fun unknownFailureOffersSameConversationInvestigationNotInventedRepair() {
        var investigate = 0
        compose.setContent { AteneaOperatorTheme { ValidationRecoveryPanel(value(known = false), false, null,
            {}, {}, {}, {}, {}, {}, { investigate++ }, {}, {}) } }
        compose.onNodeWithText("Investigar este bloqueo").performScrollTo().performClick()
        assertEquals(1, investigate); compose.onNodeWithText("Preparar reparación").assertDoesNotExist()
    }
    @Test fun appliedRepairNavigatesToCurrentValidationWithoutStartingIt() {
        var navigation = 0
        compose.setContent { AteneaOperatorTheme { ValidationRecoveryPanel(value("APPLIED"), false, null,
            {}, {}, {}, {}, {}, {}, {}, { navigation++ }, {}) } }
        compose.onNodeWithText("Ir a Validar cambio").performClick(); assertEquals(1, navigation)
        compose.onNodeWithText("Aplicar reparación").assertDoesNotExist()
    }
    @Test fun independentRescueNeedsNoBackendAndDoesNotOpenUntilConfirmed() {
        var opened = 0
        compose.setContent { AteneaOperatorTheme { IndependentRescueEntry { opened++ } } }
        compose.onNodeWithText("Rescate independiente").performClick()
        assertEquals(0, opened)
        compose.onNodeWithText("Si Atenea no responde").assertIsDisplayed()
        compose.onNodeWithText("Abrir acceso privado").performClick()
        assertEquals(1, opened)
    }
    @Test fun investigationShowsQueuedRunningAndCompletedInlineWithoutBackNavigation() {
        val current = androidx.compose.runtime.mutableStateOf("QUEUED")
        compose.setContent { AteneaOperatorTheme { ValidationRecoveryPanel(
            value(known = false).copy(investigation = ValidationRecoveryOperation(id, "INVESTIGATION", "INVESTIGATED", 1, 126, null, current.value)),
            false, null, {}, {}, {}, {}, {}, {}, {}, {}, {}) } }
        compose.onNodeWithText("Pendiente de arrancar").assertIsDisplayed()
        compose.onNodeWithText("Investigación · tarea 126 · revisión 1").assertIsDisplayed()
        compose.onNodeWithText("Investigar este bloqueo").assertDoesNotExist()
        compose.onNodeWithText("El bloqueo necesita investigación").assertDoesNotExist()
        compose.runOnIdle { current.value = "RUNNING" }
        compose.onNodeWithText("Investigación en curso").assertIsDisplayed()
        compose.runOnIdle { current.value = "SUCCEEDED" }
        compose.onNodeWithText("Investigación terminada").assertIsDisplayed()
        compose.onNodeWithText("Ver diagnóstico en conversación").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Ir a Validar cambio").assertDoesNotExist()
    }
    @Test fun onlyANewRevisionWithoutTheSameFailureOffersValidationAfterInvestigation() {
        compose.setContent { AteneaOperatorTheme { ValidationRecoveryPanel(
            value(known = false).copy(sourceRevision = 2, failureCode = null,
                investigation = ValidationRecoveryOperation(id, "INVESTIGATION", "INVESTIGATED", 1, 126, null, "SUCCEEDED")),
            false, null, {}, {}, {}, {}, {}, {}, {}, {}, {}) } }
        compose.onNodeWithText("Ir a Validar cambio").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Ver diagnóstico en conversación").assertDoesNotExist()
    }
    @Test fun wrongRunDetailCannotReplaceTheBoundInvestigationWithAnOlderSuccess() {
        val old = com.atenea.android.api.CodexRunDetail(125, 26, "SUCCEEDED", null, null, null, null, null, null,
            "COMPLETED", 0, 0, 100, "NONE", null)
        compose.setContent { AteneaOperatorTheme { ValidationRecoveryPanel(
            value(known = false).copy(investigation = ValidationRecoveryOperation(id, "INVESTIGATION", "INVESTIGATED", 1, 126, null, "QUEUED")),
            false, null, {}, {}, {}, {}, {}, {}, {}, {}, {}, investigationDetail = old,
            investigationAnswer = "Respuesta de la tarea anterior") } }
        compose.onNodeWithText("Pendiente de arrancar").assertIsDisplayed()
        compose.onNodeWithText("Respuesta de la tarea anterior").assertDoesNotExist()
        compose.onNodeWithText("Estado confirmado: SUCCEEDED").assertDoesNotExist()
    }
}
