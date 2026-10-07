package com.atenea.android.coreconsole

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import com.atenea.android.api.MobileConversationTurn
import org.junit.Rule
import org.junit.Test

/** Exercises the real lazy transcript, including offscreen composition, without a backend. */
class ConversationScrollRegressionTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun longMarkdownWithBlankLinesSurvivesScrollingInBothDirections() {
        val turns = (1L..24L).map { turn(it, markdown(it)) }
        compose.setContent { Fixture(turns) }
        repeat(12) {
            compose.onNodeWithTag("conversation-transcript").performTouchInput { swipeDown() }
            compose.waitForIdle()
        }
        compose.onNodeWithTag("conversation-transcript").performScrollToIndex(0)
        compose.onNodeWithText("Respuesta 1").assertIsDisplayed()
        repeat(12) {
            compose.onNodeWithTag("conversation-transcript").performTouchInput { swipeUp() }
            compose.waitForIdle()
        }
        compose.onNodeWithTag("conversation-composer").assertIsDisplayed()
    }

    @Test
    fun sameTurnCanGrowAndShrinkWhileHistoryIsScrolled() {
        var turns by mutableStateOf((1L..18L).map { turn(it, markdown(it)) })
        compose.setContent { Fixture(turns) }
        repeat(8) { cycle ->
            compose.onNodeWithTag("conversation-transcript").performTouchInput { swipeDown() }
            compose.runOnIdle {
                turns = turns.map { if (it.id == 17L) turn(17, markdown(17).repeat(cycle % 3 + 1)) else it }
            }
            compose.onNodeWithTag("conversation-transcript").performTouchInput { swipeUp() }
            compose.waitForIdle()
        }
        compose.onNodeWithTag("conversation-composer").assertIsDisplayed()
    }

    @androidx.compose.runtime.Composable
    private fun Fixture(turns: List<MobileConversationTurn>) {
        ConversationSurface(
            title = "Prueba local de scroll", status = null, turns = turns,
            input = "Borrador intacto", pending = false, placeholder = "Instrucción",
            onInputChange = {}, onSend = {}, onBack = {}, onOpenCore = {}, onRefresh = {},
            error = null, surfaceKey = "scroll-regression"
        )
    }

    private fun turn(id: Long, text: String) = MobileConversationTurn(id, "AGENT", text, null)

    private fun markdown(id: Long) = buildString {
        append("## Respuesta $id\n\n")
        repeat(20) { line ->
            append("- Comprobación $line con **detalle** y `código`\n\n")
            append("\n> Resultado de la comprobación $line\n\n")
        }
        append("```text\nApache: comprobación de prueba\nHTTP 503: detalle simulado\n```\n\n")
        append("Fin de respuesta $id")
    }
}
