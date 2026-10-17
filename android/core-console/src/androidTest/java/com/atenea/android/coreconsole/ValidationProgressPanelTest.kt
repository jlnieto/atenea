package com.atenea.android.coreconsole

import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import java.io.File
import com.atenea.android.api.DevelopmentChangeValidationAttempt
import com.atenea.android.api.DevelopmentChangeValidationEvidence
import java.time.Instant
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ValidationProgressPanelTest {
    @get:Rule val compose = createComposeRule()
    private val tree = "4".repeat(64)
    private fun attempt(kind: String, state: String, phase: String? = null, secondsAgo: Long = 1) =
        DevelopmentChangeValidationAttempt(UUID.randomUUID(), 26, kind, state, tree, "synthetic", null, "Synthetic result",
            Instant.now().minusSeconds(90).toString(), if (state == "SUCCEEDED") Instant.now().minusSeconds(30).toString() else null,
            if (state == "SUCCEEDED") 60_000 else null, phase, phase?.let { Instant.now().minusSeconds(secondsAgo).toString() },
            if (phase == "RUNNING") Instant.now().minusSeconds(45).toString() else null)
    private fun evidence(active: DevelopmentChangeValidationAttempt) = DevelopmentChangeValidationEvidence(UUID.randomUUID(),
        26, 6, tree, "STALE", 2, 4, listOf(attempt("BACKEND_TEST", "SUCCEEDED"), attempt("WEB_BUILD", "SUCCEEDED"), active), active)

    @Test fun actualChangePanelShowsCompletedQueuedAndPendingThenSameOperationExecuting() {
        var validations = 0; var reads = 0
        val queued = attempt("ANDROID_BUILD", "RUNNING", "QUEUED")
        val proof = mutableStateOf(evidence(queued))
        compose.setContent { AteneaOperatorTheme {
            WorkSessionChangePanel(26, "Mismo cambio", DevelopmentChangeValidationUiState(true, false, "Validando…", "", inProgress = true),
                proof.value, false, null, { reads++ }, { validations++ }) { Text("Crear PR") }
        } }
        compose.onNodeWithText("Atenea está trabajando").assertIsDisplayed()
        compose.onNodeWithText("2 de 4 comprobaciones correctas · revisión 6").assertIsDisplayed()
        compose.onNodeWithText("Pruebas de backend").assertIsDisplayed()
        compose.onNodeWithText("Compilación Android").assertIsDisplayed()
        compose.onNodeWithText("En cola").assertIsDisplayed()
        compose.onNodeWithText("Pendiente").assertExists()
        compose.onNode(hasText("Validando…") and hasClickAction()).assertIsNotEnabled()
        compose.onNodeWithText("Crear PR").assertDoesNotExist()
        screenshot("queued")
        compose.runOnIdle {
            val running = queued.copy(workerState = "RUNNING", workerObservedAt = Instant.now().toString(),
                workerStartedAt = Instant.now().minusSeconds(30).toString())
            proof.value = proof.value.copy(operations = proof.value.operations.dropLast(1) + running, lastAttempt = running)
        }
        compose.onNodeWithText("En ejecución").assertExists()
        compose.onNodeWithText("En cola").assertDoesNotExist()
        screenshot("running")
        compose.runOnIdle { assertEquals(0, validations); assertEquals(0, reads) }
    }

    @Test fun staleWorkerConfirmationDoesNotPretendExecutionIsCurrentlyConfirmed() {
        val proof = evidence(attempt("ANDROID_BUILD", "RUNNING", "RUNNING", secondsAgo = 120))
        compose.setContent { AteneaOperatorTheme { ValidationProgressPanel(proof, 26, true, true) } }
        compose.onNodeWithText("Último estado: en ejecución").assertIsDisplayed()
        compose.onNodeWithText("Sin confirmación reciente del worker", substring = true).assertExists()
        compose.onNodeWithText("En ejecución").assertDoesNotExist()
        screenshot("unconfirmed")
    }

    @Test fun completedProgressRemainsInReadOnlyDetailsWithoutPushingDeliveryOutOfTheWay() {
        var reads = 0; var validations = 0
        val active = attempt("ANDROID_BUILD", "SUCCEEDED")
        val initial = evidence(active)
        val done = initial.copy(validationState = "CURRENT", passedOperations = 4,
            operations = initial.operations + attempt("PLAYWRIGHT_ACCEPTANCE", "SUCCEEDED"))
        compose.setContent { AteneaOperatorTheme {
            WorkSessionChangePanel(26, "Mismo cambio", DevelopmentChangeValidationUiState(true, false, "Validar cambio", "", current = true),
                done, false, null, { reads++ }, { validations++ }) { Text("Siguiente paso de entrega") }
        } }
        compose.onNodeWithText("Siguiente paso de entrega").assertIsDisplayed()
        compose.onNodeWithText("4 de 4 comprobaciones correctas · revisión 6").assertDoesNotExist()
        compose.onNodeWithText("Ver pruebas y detalles").performClick()
        compose.onNodeWithText("4 de 4 comprobaciones correctas · revisión 6").assertExists()
        compose.runOnIdle { assertEquals(0, validations); assertEquals(0, reads) }
    }

    private fun screenshot(name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "validation-progress-$name.png")
            .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
