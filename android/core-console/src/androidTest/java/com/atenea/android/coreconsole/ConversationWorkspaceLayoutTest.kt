package com.atenea.android.coreconsole

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import android.graphics.Bitmap
import androidx.core.graphics.ColorUtils
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.atenea.android.api.DevelopmentChangeValidationAttempt
import com.atenea.android.api.DevelopmentChangeValidationEvidence
import com.atenea.android.api.MobileConversationTurn
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ConversationWorkspaceLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun chatReservesSpaceForMessagesInsteadOfMountingOperationalPanels() {
        compose.setContent { Fixture() }
        compose.onNodeWithText("Respuesta de Codex").assertIsDisplayed()
        compose.onNodeWithText("Validación del cambio").assertDoesNotExist()
        compose.onNodeWithText("Ejecución durable").assertDoesNotExist()
        compose.onNodeWithText("Perfil siguiente ejecución").assertDoesNotExist()
        compose.onNodeWithTag("conversation-composer").assertIsDisplayed()
        val chat = compose.onNodeWithTag("conversation-content").fetchSemanticsNode().boundsInRoot
        val toolbar = compose.onNodeWithTag("conversation-toolbar").fetchSemanticsNode().boundsInRoot
        val composer = compose.onNodeWithTag("conversation-composer").fetchSemanticsNode().boundsInRoot
        assertTrue(chat.height / (chat.height + toolbar.height + composer.height) > 0.75f)
    }

    @Test
    fun changeIsSameSessionAndReturningPreservesDraftWithoutSendingOrValidating() {
        var sent = 0
        var exited = 0
        compose.setContent { Fixture(onSend = { sent++ }, onBack = { exited++ }) }
        compose.onNodeWithTag("conversation-input").performTextReplacement("Borrador sin enviar")
        compose.onNodeWithText("Cambio").performClick()
        compose.onNodeWithText("Validación del cambio").assertIsDisplayed()
        compose.onNodeWithTag("conversation-composer").assertDoesNotExist()
        compose.onNodeWithContentDescription("Volver").performClick()
        compose.onNodeWithTag("conversation-input").assertTextEquals("Borrador sin enviar")
        compose.onNodeWithText("Respuesta de Codex").assertIsDisplayed()
        assertEquals(0, sent)
        assertEquals(0, exited)
    }

    @Test
    fun executionAndProfileAreAccessibleThroughMenuOnly() {
        compose.setContent { Fixture() }
        compose.onNodeWithContentDescription("Opciones de conversación").performClick()
        compose.onNodeWithText("Detalle de ejecución").performClick()
        compose.onNodeWithText("Ejecución durable").assertIsDisplayed()
        compose.onNodeWithText("Perfil siguiente ejecución").assertDoesNotExist()
        compose.onNodeWithContentDescription("Volver").performClick()
        compose.onNodeWithContentDescription("Opciones de conversación").performClick()
        compose.onNodeWithText("Configuración de Codex").performClick()
        compose.onNodeWithText("Perfil siguiente ejecución").assertIsDisplayed()
        compose.onNodeWithText("Ejecución durable").assertDoesNotExist()
    }

    @Test
    fun configurationRestorationPreservesDraftAndPane() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { Fixture() }
        compose.onNodeWithTag("conversation-input").performTextReplacement("Borrador tras rotación")
        compose.onNodeWithText("Cambio").performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Validación del cambio").assertIsDisplayed()
        compose.onNodeWithContentDescription("Volver").performClick()
        compose.onNodeWithTag("conversation-input").assertTextEquals("Borrador tras rotación")
    }

    @Test
    fun consumingScaffoldInsetsAvoidsExtraTopAndBottomBands() {
        compose.setContent {
            AteneaOperatorTheme {
                Scaffold { padding ->
                    Box(Modifier.padding(padding).consumeWindowInsets(padding)) { Fixture() }
                }
            }
        }
        val surface = compose.onNodeWithTag("conversation-surface").fetchSemanticsNode().boundsInRoot
        val toolbar = compose.onNodeWithTag("conversation-toolbar").fetchSemanticsNode().boundsInRoot
        val composer = compose.onNodeWithTag("conversation-composer").fetchSemanticsNode().boundsInRoot
        assertEquals(surface.top, toolbar.top, 1f)
        assertEquals(surface.bottom, composer.bottom, 1f)
    }

    @Test
    fun lightScaffoldKeepsConversationAndValidationTextReadable() {
        val attempt = DevelopmentChangeValidationAttempt(
            UUID.fromString("0cc7815a-f703-46ee-938a-8ef4d00e68a2"), 21, "BACKEND_TEST", "BLOCKED",
            "4".repeat(64), "atenea-backend-test-v2", 2,
            "PostgreSQL de pruebas no disponible.", null, null)
        val evidence = DevelopmentChangeValidationEvidence(UUID.randomUUID(), 21, 2, "4".repeat(64),
            "BLOCKED", 0, 4, listOf(attempt), attempt)
        compose.setContent {
            AteneaOperatorTheme {
                Scaffold(containerColor = ConversationColors.background) { padding ->
                    Box(Modifier.padding(padding).consumeWindowInsets(padding)) {
                        Fixture(change = {
                            WorkSessionChangePanel(21, "Ticket original",
                                DevelopmentChangeValidationUiState(true, true, "Validar cambio", "La validación necesita atención."),
                                evidence, false, null, {}, {}, { Text("Entrega") })
                        })
                    }
                }
            }
        }
        assertReadableText("Monitorizar degradación de Apache cada 5 minutos")
        saveContrastScreenshot("contrast-chat.png")
        compose.onNodeWithText("Cambio").performClick()
        assertReadableText("La validación necesita atención.")
        assertReadableText("0/4 comprobaciones de la revisión 2")
        assertReadableText(attempt.summary)
        saveContrastScreenshot("contrast-change.png")
    }

    private fun assertReadableText(text: String) {
        val pixels = compose.onNodeWithText(text).assertIsDisplayed().captureToImage().toPixelMap()
        var readablePixels = 0
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
            if (ColorUtils.calculateContrast(pixels[x, y].toArgb(), ConversationColors.background.toArgb()) >= 4.5) {
                readablePixels++
            }
        }
        assertTrue("El texto '$text' debe dibujar glifos con contraste legible sobre el fondo oscuro",
            readablePixels > pixels.width * pixels.height / 100)
    }

    private fun saveContrastScreenshot(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        java.io.File(context.getExternalFilesDir(null), name).outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test
    fun smallWindowAndLargeFontKeepReplyAndComposerVisible() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.4f)) {
                Box(Modifier.height(400.dp)) { Fixture() }
            }
        }
        compose.onNodeWithText("Respuesta de Codex").assertIsDisplayed()
        compose.onNodeWithContentDescription("Enviar").assertIsDisplayed()
        compose.onNodeWithTag("conversation-input").assertIsDisplayed()
        compose.onNodeWithText("Validación del cambio").assertDoesNotExist()
    }

    @Test
    fun newMessagesDoNotDragOperatorAwayFromHistoryAndPaneSwitchKeepsPosition() {
        var turns by mutableStateOf((1L..30L).map { turn(it, "Mensaje $it") })
        compose.setContent { Fixture(turns = turns) }
        compose.onNodeWithTag("conversation-transcript").performTouchInput { swipeDown() }
        compose.waitForIdle()
        compose.onNodeWithTag("conversation-transcript").performScrollToIndex(0)
        compose.runOnIdle { turns = turns + turn(31, "Mensaje nuevo") }
        compose.onNodeWithText("Mensaje 1").assertIsDisplayed()
        compose.onNodeWithText("Ir a los mensajes nuevos").assertIsDisplayed()
        compose.onNodeWithText("Cambio").performClick()
        compose.onNodeWithContentDescription("Volver").performClick()
        compose.onNodeWithText("Mensaje 1").assertIsDisplayed()
        compose.onNodeWithText("Ir a los mensajes nuevos").performClick()
        compose.onNodeWithText("Mensaje nuevo").assertIsDisplayed()
    }

    @Test
    fun validationQueryDisplaysFailureAndOperationIdentityWithoutRunningValidation() {
        var reads = 0
        var validations = 0
        val attempt = DevelopmentChangeValidationAttempt(
            UUID.fromString("0cc7815a-f703-46ee-938a-8ef4d00e68a2"), 21, "BACKEND_TEST", "BLOCKED",
            "4".repeat(64), "atenea-backend-test-v2", 2,
            "[TEST_DATABASE_SETUP_FAILED] PostgreSQL de pruebas no disponible.", null, null)
        val evidence = DevelopmentChangeValidationEvidence(UUID.randomUUID(), 21, 2, "4".repeat(64),
            "BLOCKED", 0, 4, listOf(attempt), attempt)
        compose.setContent {
            Fixture(change = {
                WorkSessionChangePanel(21, "Ticket original",
                    DevelopmentChangeValidationUiState(true, true, "Validar cambio", "La validación necesita atención."),
                    evidence, false, null, { reads++ }, { validations++ }, { Text("Entrega") })
            })
        }
        compose.onNodeWithText("Cambio").performClick()
        compose.onNodeWithText("Código: TEST_DATABASE_SETUP_FAILED").assertIsDisplayed()
        compose.onNodeWithText("Operation ID: ${attempt.id}").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Consultar resultado").performScrollTo().performClick()
        assertEquals(1, reads)
        assertEquals(0, validations)
    }

    @Composable
    private fun Fixture(
        turns: List<MobileConversationTurn> = listOf(turn(1, "Respuesta de Codex")),
        onSend: () -> Unit = {},
        onBack: () -> Unit = {},
        change: @Composable () -> Unit = { Text("Validación del cambio", Modifier.height(300.dp)) }
    ) {
        var draft by rememberSaveable { mutableStateOf("Borrador") }
        ConversationSurface(
            title = "Monitorizar degradación de Apache cada 5 minutos",
            status = "Validación bloqueada",
            turns = turns, input = draft, pending = false, placeholder = "Escribe una instrucción",
            onInputChange = { draft = it }, onSend = onSend, onBack = onBack,
            onOpenCore = {}, onRefresh = {}, error = null, surfaceKey = "work-session-21",
            changeContent = change,
            runContent = { Text("Ejecución durable", Modifier.height(300.dp)) },
            profileContent = { Text("Perfil siguiente ejecución", Modifier.height(300.dp)) }
        )
    }

    private fun turn(id: Long, text: String) = MobileConversationTurn(id, "AGENT", text, null)
}
