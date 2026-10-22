package com.atenea.android.coreconsole

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import com.atenea.android.api.AteneaApiClient
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Counts physical HTTP requests through the real delivery state and lifecycle observer. */
class ReadCoordinationJourneyTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun manualConsultSharesSlowResumeReadAndBackgroundDoesNotPoll() {
        val reads = AtomicInteger()
        val effects = AtomicInteger()
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        val release = CountDownLatch(1)
        val extra = CountDownLatch(1)
        val server = JourneyHttpServer { request ->
            if (request.method != "GET") effects.incrementAndGet()
            if (request.method != "GET" || request.path != "/api/mobile/sessions/29/delivery") {
                JourneyHttpResponse(404, "{\"message\":\"Synthetic route absent\"}")
            } else {
                val number = reads.incrementAndGet()
                maximum.accumulateAndGet(active.incrementAndGet(), ::maxOf)
                if (number > 2) extra.countDown()
                try {
                    if (number == 2) check(release.await(30, TimeUnit.SECONDS))
                    JourneyHttpResponse(200, delivery().toString())
                } finally { active.decrementAndGet() }
            }
        }.also { it.start() }
        try {
            val api = AteneaApiClient(server.url("/"), { "synthetic-only" },
                operatorRoleProvider = { "PLATFORM_ADMINISTRATOR" })
            compose.setContent {
                AteneaOperatorTheme {
                    val scope = rememberCoroutineScope()
                    val state = rememberMobileDeliveryUiState(api, 29, enabled = true)
                    Column {
                        Text(if (state.loaded) "Estado observado" else "Esperando estado")
                        AteneaButton("Consultar", enabled = state.canConsult,
                            onClick = { scope.launch { state.consult() } })
                        if (state.manualRefreshing) Text("Consulta manual en curso")
                        state.consultationMessage?.let { Text(it) }
                    }
                }
            }
            waitFor("Estado observado")
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            compose.waitUntil(10_000) { reads.get() == 2 }
            compose.onNodeWithText("Consultar").performClick()
            waitFor("Consulta manual en curso")
            assertFalse("Manual consultation must join the in-flight request", extra.await(1, TimeUnit.SECONDS))
            assertEquals(1, maximum.get())
            release.countDown()
            compose.waitUntil(10_000) {
                compose.onAllNodes(hasText("Consulta manual en curso")).fetchSemanticsNodes().isEmpty()
            }
            assertEquals(2, reads.get())
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            assertFalse("No periodic request while stopped", extra.await(5500, TimeUnit.MILLISECONDS))
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            assertTrue("Resume must read fresh state", extra.await(10, TimeUnit.SECONDS))
            assertEquals(3, reads.get()); assertEquals(1, maximum.get()); assertEquals(0, effects.get())
        } finally { release.countDown(); server.shutdown() }
    }

    private fun waitFor(text: String) {
        compose.waitUntil(10_000) { compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(text).assertIsDisplayed()
    }

    private fun delivery() = JSONObject().put("enabled", true).put("operations", JSONArray().put(JSONObject()
        .put("id", "00000000-0000-4000-8000-000000000001").put("operationId", "00000000-0000-4000-8000-000000000002")
        .put("sessionId", 29).put("kind", "PUBLISH_PR").put("target", "APP_PROD").put("state", "WAITING_CI")))
        .put("validation", JSONObject().put("changeKey", "00000000-0000-4000-8000-000000000003")
            .put("workSessionId", 29).put("sourceRevision", 1).put("sourceFingerprintSha256", "1".repeat(64))
            .put("validationState", "NOT_STARTED").put("passedOperations", 0).put("requiredOperations", 4)
            .put("operations", JSONArray()))
}
