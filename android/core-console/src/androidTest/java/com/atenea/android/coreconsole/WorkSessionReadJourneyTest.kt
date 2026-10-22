package com.atenea.android.coreconsole

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import com.atenea.android.api.AteneaApiClient
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** The retained session surface must follow the same lifecycle/read ownership rules. */
class WorkSessionReadJourneyTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun resumeAndManualRefreshShareSlowSnapshotAndStopDoesNotPoll() {
        val summaries = AtomicInteger(); val events = AtomicInteger(); val effects = AtomicInteger()
        val active = AtomicInteger(); val maximum = AtomicInteger()
        val release = CountDownLatch(1); val extraSummary = CountDownLatch(1)
        val stopped = AtomicBoolean(false); val backgroundRequest = CountDownLatch(1)
        val closed = AtomicBoolean(false)
        val server = JourneyHttpServer { request ->
            if (request.method != "GET") effects.incrementAndGet()
            if (stopped.get()) backgroundRequest.countDown()
            when {
                request.path == "/api/mobile/sessions/29/summary" -> {
                    val number = summaries.incrementAndGet()
                    maximum.accumulateAndGet(active.incrementAndGet(), ::maxOf)
                    if (number > 2) extraSummary.countDown()
                    try {
                        if (number == 2) check(release.await(30, TimeUnit.SECONDS))
                        ok(JSONObject().put("conversation", JSONObject().put("view", JSONObject()
                            .put("session", JSONObject().put("id", 29).put("title", "WorkSession retenida")
                                .put("status", if (closed.get()) "CLOSED" else "OPEN"))
                            .put("latestRun", JSONObject().put("id", 103).put("status", if (closed.get()) "SUCCEEDED" else "RUNNING"))
                            .put("runInProgress", !closed.get()))))
                    } finally { active.decrementAndGet() }
                }
                request.path == "/api/mobile/sessions/29/deliverables" -> ok(JSONObject().put("sessionId", 29).put("deliverables", JSONArray()))
                request.path.startsWith("/api/mobile/sessions/29/events?") -> {
                    events.incrementAndGet(); ok(JSONObject().put("sessionId", 29).put("events", JSONArray()))
                }
                else -> JourneyHttpResponse(404, "{}")
            }
        }.also { it.start() }
        try {
            val api = AteneaApiClient(server.url("/"), { "synthetic-only" })
            compose.setContent { AteneaOperatorTheme { WorkSessionScreen(api, 1, 29, {}, {}, {}) } }
            compose.waitUntil(10_000) { events.get() == 1 }
            compose.onNodeWithText("WorkSession retenida").assertExists()
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            compose.waitUntil(10_000) { summaries.get() == 2 }
            compose.onNodeWithText("Actualizar estado").performScrollTo().performClick()
            assertFalse("Manual and resume must share the slow snapshot", extraSummary.await(1, TimeUnit.SECONDS))
            release.countDown()
            compose.waitUntil(10_000) { events.get() >= 2 && compose.onAllNodes(hasText("Sin cambios")).fetchSemanticsNodes().isNotEmpty() }
            compose.waitForIdle()
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            stopped.set(true)
            assertFalse("No periodic HTTP while stopped", backgroundRequest.await(3500, TimeUnit.MILLISECONDS))
            stopped.set(false)
            closed.set(true)
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            assertTrue("Resume must load a fresh snapshot", extraSummary.await(10, TimeUnit.SECONDS))
            compose.waitUntil(10_000) { events.get() >= 3 && compose.onAllNodes(hasText("Sin cambios")).fetchSemanticsNodes().isNotEmpty() }
            val historyEvents = events.get()
            compose.mainClock.advanceTimeBy(30_000)
            compose.waitForIdle()
            assertEquals("Completed history must not keep polling", historyEvents, events.get())
            assertEquals(3, summaries.get()); assertEquals(1, maximum.get()); assertEquals(0, effects.get())
        } finally { release.countDown(); server.shutdown() }
    }

    private fun ok(value: JSONObject) = JourneyHttpResponse(200, value.toString())
}
