package com.atenea.android.coreconsole

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.lifecycle.Lifecycle
import com.atenea.android.api.AteneaApiClient
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Shell, conversation, HTTP and saved state; no FCM server, real device or production calls. */
class NotificationNavigationJourneyTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val notifications = AteneaNotificationState()

    @Test fun receivedNoticesAndBackgroundRestorationKeepChangeDraftAndScrollWithoutOpeningThem() {
        fixture { backend, restore ->
            compose.onNodeWithTag("conversation-input").performTextReplacement("Borrador conservado")
            compose.onNodeWithTag("conversation-transcript").performTouchInput { swipeDown() }
            compose.onNodeWithTag("conversation-transcript").performScrollToIndex(0)
            openChange()
            repeat(2) { index ->
                val before = backend.inboxReads.get()
                compose.runOnIdle { notifications.onReceived(AteneaNotificationRoute(29, event(index))) }
                compose.waitUntil(10_000) { backend.inboxReads.get() > before }
                assertChange(29)
            }
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            assertChange(29)
            restore.emulateSavedInstanceStateRestore()
            assertChange(29)
            compose.onNodeWithContentDescription("Volver").performClick()
            compose.onNodeWithTag("conversation-input").assertTextEquals("Borrador conservado")
            compose.onNodeWithText("Mensaje 1").assertIsDisplayed()
            assertEquals(emptyList<String>(), backend.opened.toList())
            assertEquals(0, backend.effects.get())
        }
    }

    @Test fun tappingNoticeForSameSessionMarksItOpenedWithoutRecreatingChange() {
        fixture { backend, _ ->
            compose.onNodeWithTag("conversation-input").performTextReplacement("No perder mi texto")
            openChange()
            compose.runOnIdle { notifications.onOpened(AteneaNotificationRoute(29, event(2))) }
            compose.waitUntil(10_000) { backend.opened.contains(event(2)) }
            assertChange(29)
            compose.onNodeWithContentDescription("Volver").performClick()
            compose.onNodeWithTag("conversation-input").assertTextEquals("No perder mi texto")
            assertEquals(listOf(event(2)), backend.opened.toList())
            assertEquals(0, backend.effects.get())
        }
    }

    @Test fun anotherSessionHasItsOwnStateAndReturningRestoresOriginalChange() {
        fixture { backend, _ ->
            compose.onNodeWithTag("conversation-input").performTextReplacement("Sólo para 29")
            openChange()
            compose.runOnIdle { notifications.onOpened(AteneaNotificationRoute(30, event(3))) }
            waitFor("Otro cambio")
            compose.onNodeWithTag("conversation-input").assertTextEquals("")
            compose.onNodeWithTag("conversation-input").performTextReplacement("Sólo para 30")
            compose.runOnIdle { notifications.onOpened(AteneaNotificationRoute(29, event(4))) }
            assertChange(29)
            compose.onNodeWithContentDescription("Volver").performClick()
            compose.onNodeWithTag("conversation-input").assertTextEquals("Sólo para 29")
            compose.runOnIdle { notifications.onOpened(AteneaNotificationRoute(30, event(5))) }
            waitFor("Otro cambio")
            compose.onNodeWithTag("conversation-input").assertTextEquals("Sólo para 30")
            compose.waitUntil(10_000) { backend.opened.size == 3 }
            assertEquals(listOf(event(3), event(4), event(5)), backend.opened.toList())
            assertEquals(0, backend.effects.get())
        }
    }

    private fun event(index: Int) = "00000000-0000-4000-8000-00000000000$index"

    private fun fixture(block: (Backend, StateRestorationTester) -> Unit) {
        val preferences = compose.activity.getSharedPreferences("atenea_navigation", Context.MODE_PRIVATE)
        preferences.edit().clear().putString("destination", "CONVERSATION")
            .putLong("projectId", 1).putLong("sessionId", 29).commit()
        val backend = Backend()
        val server = JourneyHttpServer(backend::dispatch).also { it.start() }
        try {
            val restore = StateRestorationTester(compose)
            val api = AteneaApiClient(server.url("/"), { "synthetic-only" },
                operatorRoleProvider = { "PLATFORM_ADMINISTRATOR" })
            restore.setContent {
                AteneaOperatorTheme {
                    AteneaShell(api, "Operador sintético", "", 168, "0.5.135", {},
                        requestedConversation = notifications.openedRoute, receivedNotificationKey = notifications.receivedKey)
                }
            }
            waitFor("Cambio de prueba")
            block(backend, restore)
        } finally {
            server.shutdown()
            preferences.edit().clear().commit()
        }
    }

    private fun openChange() {
        compose.onNodeWithContentDescription("Opciones de conversación").performClick()
        compose.onNode(hasText("Cambio") and hasClickAction()).performClick()
        assertChange(29)
    }

    private fun assertChange(session: Long) {
        waitFor("Cambio · sesión $session")
        compose.onNodeWithTag("conversation-input").assertDoesNotExist()
    }

    private fun waitFor(text: String) {
        compose.waitUntil(20_000) { compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(text).assertIsDisplayed()
    }

    private class Backend {
        val inboxReads = AtomicInteger()
        val effects = AtomicInteger()
        val opened = ConcurrentLinkedQueue<String>()

        fun dispatch(request: JourneyHttpRequest): JourneyHttpResponse {
            val path = request.path.substringBefore('?')
            if (request.method == "POST" && path.matches(Regex("/api/mobile/notifications/[0-9a-f-]+/opened"))) {
                opened.add(path.split('/')[4])
                return JourneyHttpResponse(204, "")
            }
            if (request.method != "GET") effects.incrementAndGet()
            if (path == "/api/mobile/notifications") {
                inboxReads.incrementAndGet()
                return JourneyHttpResponse(200, JSONObject().put("protocolVersion", "atenea-mobile-notification-inbox/v1")
                    .put("items", JSONArray()).put("unreadCount", 1).put("nextPage", JSONObject.NULL).toString())
            }
            val session = Regex("/api/mobile/sessions/(29|30)/(summary|delivery)").matchEntire(path)
                ?: return JourneyHttpResponse(404, "{\"message\":\"Synthetic route absent\"}")
            val id = session.groupValues[1].toLong()
            val response = if (session.groupValues[2] == "delivery") {
                JSONObject().put("enabled", false).put("operations", JSONArray())
            } else {
                val turns = JSONArray()
                (1..35).forEach { index -> turns.put(JSONObject().put("id", index).put("actor", "CODEX")
                    .put("messageText", "Mensaje $index").put("status", "COMPLETED")) }
                JSONObject().put("conversation", JSONObject().put("view", JSONObject()
                    .put("session", JSONObject().put("id", id).put("projectId", 1)
                        .put("title", if (id == 29L) "Cambio de prueba" else "Otro cambio").put("status", "OPEN")
                        .put("developmentChangeKey", "00000000-0000-4000-8000-0000000000$id")
                        .put("developmentChangeValidationState", "NOT_STARTED").put("developmentChangeSourceState", "DIRTY"))
                    .put("runInProgress", false).put("canCreateTurn", true)).put("recentTurns", turns))
            }
            return JourneyHttpResponse(200, response.toString())
        }
    }
}
