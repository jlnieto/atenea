package com.atenea.android.coreconsole

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import com.atenea.android.api.AteneaApiClient
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Recovery UI and two consumers of the same reader, over real loopback HTTP only. */
class RecoveryReadJourneyTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun slowReadIsSharedAndCursorSurvivesTerminalResumeAndNewExecution() {
        val backend = Backend()
        val server = JourneyHttpServer(backend::dispatch).also { it.start() }
        try {
            val api = AteneaApiClient(server.url("/"), { "synthetic-only" })
            compose.setContent {
                val scope = rememberCoroutineScope()
                val reader = remember(api) { CodexRunReader(api, 29, scope) }
                var observed by remember { mutableStateOf("") }
                AteneaOperatorTheme { Column {
                    Text(observed)
                    ValidationRecoveryScreen(api, 29, backend.change, reader, {}, {}, onRunChanged = { id ->
                        scope.launch {
                            val snapshot = reader.observe(id)
                            observed = "Observador $id · ${snapshot.progress.latestObservedSequence()}"
                        }
                    })
                } }
            }
            assertTrue(backend.firstProgress.await(10, TimeUnit.SECONDS))
            consult()
            assertFalse("A slow GET must not spawn another recovery/detail/progress bundle", backend.extraRead.await(2, TimeUnit.SECONDS))
            assertEquals(1, backend.details.get()); assertEquals(1, backend.recoveries.get())
            backend.releaseProgress.countDown()
            waitFor("Observador 103 · 1")
            compose.waitUntil(10_000) { compose.onAllNodes(hasText("Enviando la acción.", substring = true)).fetchSemanticsNodes().isEmpty() }
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            backend.unexpectedBackground = true
            assertFalse("An active investigation must not poll while stopped", backend.backgroundRead.await(2, TimeUnit.SECONDS))
            backend.unexpectedBackground = false
            backend.terminal = true
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            waitFor("Investigación terminada")
            waitFor("Respuesta sintética de 103")
            waitFor("Observador 103 · 2")
            assertEquals(listOf(103L to 0L, 103L to 1L), backend.cursors.toList())
            assertEquals(2, backend.details.get())
            repeat(2) {
                val before = backend.recoveries.get()
                consult()
                compose.waitUntil(10_000) { backend.recoveries.get() > before }
                compose.waitUntil(10_000) { compose.onAllNodes(hasText("Enviando la acción.", substring = true)).fetchSemanticsNodes().isEmpty() }
            }
            assertEquals(2, backend.details.get())
            assertEquals(2, backend.cursors.size)
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            val before = backend.recoveries.get()
            backend.unexpectedBackground = true
            assertFalse("Recovery must not poll while stopped", backend.backgroundRead.await(2, TimeUnit.SECONDS))
            backend.unexpectedBackground = false
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            compose.waitUntil(10_000) { backend.recoveries.get() > before }
            assertEquals(2, backend.details.get()); assertEquals(2, backend.cursors.size)
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            backend.run = 104; backend.terminal = false
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            waitFor("Investigación · tarea 104 · revisión 2")
            waitFor("Observador 104 · 1")
            assertEquals(104L to 0L, backend.cursors.last())
            assertEquals(0, backend.effects.get())
        } finally { backend.releaseProgress.countDown(); server.shutdown() }
    }

    @Test fun lateOldSessionAndForeignDetailCannotReplaceNewSessionAndReadErrorCanRecover() {
        val releaseOld = CountDownLatch(1)
        val oldStarted = CountDownLatch(1)
        val effects = AtomicInteger()
        val newProgress = AtomicInteger()
        val cursors = Collections.synchronizedList(mutableListOf<Long>())
        var session by mutableStateOf(29L)
        val foreign = AtomicBoolean(true)
        val server = JourneyHttpServer { request ->
            if (request.method != "GET") effects.incrementAndGet()
            val path = request.path
            when {
                path == "/api/mobile/sessions/29/validation-recovery" -> ok(recovery(29, 103, false))
                path == "/api/mobile/sessions/30/validation-recovery" -> ok(recovery(30, 201, false))
                path == "/api/runs/103/codex-detail" -> {
                    oldStarted.countDown(); check(releaseOld.await(30, TimeUnit.SECONDS)); ok(detail(29, 103, false))
                }
                path == "/api/runs/201/codex-detail" -> ok(detail(if (foreign.get()) 29 else 30, 201, false))
                path.startsWith("/api/runs/201/progress?") -> {
                    val after = path.substringAfter("afterSequence=").toLong()
                    cursors += after
                    if (newProgress.incrementAndGet() == 1) JourneyHttpResponse(503, "{\"message\":\"Synthetic disconnection\"}")
                    else ok(progress(after, after + 1, false))
                }
                path.startsWith("/api/runs/103/progress?") -> ok(progress(0, 1, false))
                else -> JourneyHttpResponse(404, "{}")
            }
        }.also { it.start() }
        try {
            val api = AteneaApiClient(server.url("/"), { "synthetic-only" })
            compose.setContent {
                val scope = rememberCoroutineScope()
                val reader = remember(api, session) { CodexRunReader(api, session, scope) }
                AteneaOperatorTheme { ValidationRecoveryScreen(api, session, CHANGE, reader, {}, {}) }
            }
            assertTrue(oldStarted.await(10, TimeUnit.SECONDS))
            compose.runOnIdle { session = 30 }
            waitFor("Investigación · tarea 201 · revisión 2")
            waitFor("No se pudo consultar la recuperación. Tu cambio se conserva.")
            assertEquals(0, newProgress.get())
            releaseOld.countDown()
            compose.onNodeWithText("Estado confirmado: RUNNING").assertDoesNotExist()
            foreign.set(false)
            consult()
            waitFor("No se pudo confirmar el resultado.", substring = true)
            consult()
            waitFor("Estado confirmado: RUNNING")
            assertEquals(listOf(0L, 0L), cursors.toList())
            compose.onNodeWithText("Investigación · tarea 103 · revisión 2").assertDoesNotExist()
            assertEquals(0, effects.get())
        } finally { releaseOld.countDown(); server.shutdown() }
    }

    @Test fun conversationHelpAndRecoveryUseTheSameTerminalReader() {
        val details = AtomicInteger(); val replays = AtomicInteger(); val effects = AtomicInteger()
        val server = JourneyHttpServer { request ->
            if (request.method != "GET") effects.incrementAndGet()
            when {
                request.path == "/api/mobile/sessions/29/summary" -> ok(JSONObject().put("conversation", JSONObject()
                    .put("view", JSONObject().put("session", JSONObject().put("id", 29).put("projectId", 1)
                        .put("title", "Conversación con lector compartido").put("status", "OPEN").put("developmentChangeKey", CHANGE))
                        .put("latestRun", JSONObject().put("id", 103).put("status", "SUCCEEDED")))))
                request.path == "/api/mobile/sessions/29/delivery" -> ok(JSONObject().put("enabled", false).put("operations", JSONArray()))
                request.path == "/api/mobile/sessions/29/help" -> ok(JSONObject()
                    .put("protocolVersion", "change-help/v1").put("workSessionId", 29).put("changeKey", CHANGE)
                    .put("sourceRevision", 2).put("observedAt", "2026-10-22T13:00:00Z").put("stage", "VALIDATION")
                    .put("answer", "Resultado sintético disponible").put("simpleAnswer", "Resultado disponible")
                    .put("facts", JSONArray()).put("suggestedQuestions", JSONArray()).put("explanationSource", "RULES")
                    .put("nextAction", JSONObject().put("destination", "VALIDATION_RECOVERY")
                        .put("label", "Revisar recuperación").put("reason", "Consultar sin ejecutar")))
                request.path == "/api/mobile/sessions/29/validation-recovery" -> ok(recovery(29, 103, true))
                request.path == "/api/runs/103/codex-detail" -> { details.incrementAndGet(); ok(detail(29, 103, true)) }
                request.path.startsWith("/api/runs/103/progress?") -> { replays.incrementAndGet(); ok(progress(0, 2, true)) }
                request.path == "/api/mobile/sessions/29/conversation" -> ok(JSONObject().put("session", JSONObject().put("id", 29)))
                else -> JourneyHttpResponse(404, "{}")
            }
        }.also { it.start() }
        try {
            val api = AteneaApiClient(server.url("/"), { "synthetic-only" })
            compose.setContent { AteneaOperatorTheme { WorkSessionConversationScreen(api, 1, 29, {}) } }
            compose.waitUntil(15_000) { replays.get() == 1 }
            waitFor("Conversación con lector compartido")
            compose.onNodeWithContentDescription("Opciones de conversación").performClick()
            compose.onNodeWithText("Ayuda con este cambio").performClick()
            waitFor("Revisar recuperación")
            compose.onNodeWithText("Revisar recuperación").performClick()
            waitFor("Estado confirmado: SUCCEEDED")
            consult()
            compose.waitUntil(10_000) { compose.onAllNodes(hasText("Enviando la acción.", substring = true)).fetchSemanticsNodes().isEmpty() }
            assertEquals(1, details.get()); assertEquals(1, replays.get()); assertEquals(0, effects.get())
        } finally { server.shutdown() }
    }

    private fun consult() {
        compose.onNodeWithText("Consultar resultado").performScrollTo().performClick()
    }

    private fun waitFor(text: String, substring: Boolean = false) {
        compose.waitUntil(15_000) { compose.onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty() }
    }

    private class Backend {
        val change = CHANGE
        @Volatile var terminal = false
        @Volatile var run = 103L
        @Volatile var unexpectedBackground = false
        val recoveries = AtomicInteger(); val details = AtomicInteger(); val effects = AtomicInteger()
        val cursors = Collections.synchronizedList(mutableListOf<Pair<Long, Long>>())
        val firstProgress = CountDownLatch(1); val releaseProgress = CountDownLatch(1)
        val extraRead = CountDownLatch(1); val backgroundRead = CountDownLatch(1)
        fun dispatch(request: JourneyHttpRequest): JourneyHttpResponse {
            if (request.method != "GET") effects.incrementAndGet()
            if (unexpectedBackground) backgroundRead.countDown()
            val value = when {
                request.path == "/api/mobile/sessions/29/validation-recovery" -> {
                    if (recoveries.incrementAndGet() > 1) extraRead.countDown()
                    recovery(29, run, terminal)
                }
                request.path == "/api/runs/$run/codex-detail" -> {
                    if (details.incrementAndGet() > 1) extraRead.countDown()
                    detail(29, run, terminal)
                }
                request.path.startsWith("/api/runs/$run/progress?") -> {
                    val current = run
                    val after = request.path.substringAfter("afterSequence=").toLong()
                    cursors += current to after
                    if (cursors.size == 1) { firstProgress.countDown(); check(releaseProgress.await(30, TimeUnit.SECONDS)) }
                    progress(after, after + 1, terminal)
                }
                request.path == "/api/mobile/sessions/29/conversation" -> JSONObject().put("view", JSONObject()
                    .put("session", JSONObject().put("id", 29).put("status", "OPEN"))).put("recentTurns", JSONArray().put(
                        JSONObject().put("id", 1).put("actor", "CODEX").put("messageText", "Respuesta sintética de $run")
                            .put("executionProfile", JSONObject().put("runId", run))))
                else -> return JourneyHttpResponse(404, "{}")
            }
            return ok(value)
        }
    }

    companion object {
        private const val CHANGE = "00000000-0000-4000-8000-000000000029"
        private fun ok(value: JSONObject) = JourneyHttpResponse(200, value.toString())
        private fun recovery(session: Long, run: Long, terminal: Boolean) = JSONObject()
            .put("protocolVersion", "validation-recovery/v1").put("workSessionId", session).put("changeKey", CHANGE)
            .put("sourceRevision", 2).put("failureCode", "TESTS_FAILED").put("canPrepareRepair", false).put("canInvestigate", false)
            .put("investigation", JSONObject().put("protocolVersion", "validation-recovery/v1")
                .put("id", "00000000-0000-4000-8000-000000000103").put("kind", "INVESTIGATION")
                .put("state", if (terminal) "INVESTIGATED" else "INVESTIGATING").put("sourceRevision", 2)
                .put("runId", run).put("runStatus", if (terminal) "SUCCEEDED" else "RUNNING"))
        private fun detail(session: Long, run: Long, terminal: Boolean) = JSONObject().put("runId", run)
            .put("workSessionId", session).put("status", if (terminal) "SUCCEEDED" else "RUNNING")
            .put("latestSequence", if (terminal) 2 else 1).put("retainedFloor", 1)
        private fun progress(after: Long, sequence: Long, terminal: Boolean): JSONObject {
            val event = JSONObject().put("sequence", sequence).put("category", "CHECKING").put("message", "Progreso sintético $sequence")
            return JSONObject().put("requestedAfterSequence", after).put("retainedFloor", 1)
                .put("currentState", if (terminal) "COMPLETED" else "CHECKING").put("latestEvent", event)
                .put("terminalOutcome", if (terminal) "SUCCEEDED" else JSONObject.NULL).put("events", JSONArray().put(event))
        }
    }
}
