package com.atenea.android.coreconsole

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import com.atenea.android.api.AteneaApiClient
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Real screen, HTTP and saved-state restoration; loopback fixtures only. */
class ValidationAdmissionJourneyTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun rejectedFirstRequestKeepsItsReasonAfterPassiveRefreshBackgroundAndRestoration() {
        val backend = Backend(reject = true)
        val server = JourneyHttpServer(backend::dispatch).also { it.start() }
        try {
            val restore = StateRestorationTester(compose)
            val api = AteneaApiClient(server.url("/"), { "synthetic-only" }, operatorRoleProvider = { "PLATFORM_ADMINISTRATOR" })
            restore.setContent { AteneaOperatorTheme { WorkSessionConversationScreen(api, 1, 29, {}) } }
            openChange(); waitFor("Validar cambio", button = true)
            compose.onNode(hasText("Validar cambio") and hasClickAction()).performClick()
            compose.waitUntil(10_000) { backend.requests.get() == 1 }
            waitFor("Solicitando validación")
            backgroundAndResume()
            backend.allowResponse.countDown()
            waitFor("El servidor no aceptó esta solicitud", substring = true)
            waitFor("Volver a solicitar", button = true)
            assertEquals(1, backend.requests.get())
            val before = backend.reads.get()
            // Rejected admission is idle, so the production poll interval is 30 s.
            // Advance the Compose coroutine clock, not the HTTP deadline or a real sleep.
            compose.mainClock.advanceTimeBy(30_000)
            compose.waitUntil(15_000) { backend.reads.get() > before }
            waitFor("El servidor no aceptó esta solicitud", substring = true)
            backgroundAndResume()
            waitFor("El servidor no aceptó esta solicitud", substring = true)
            restore.emulateSavedInstanceStateRestore()
            openChange()
            waitFor("El servidor no aceptó esta solicitud", substring = true)
            waitFor("Volver a solicitar", button = true)
            compose.onNode(hasText("Validando cambio")).assertDoesNotExist()
            assertEquals(1, backend.requests.get())
            assertEquals("NOT_STARTED", backend.state)
        } finally { backend.allowResponse.countDown(); server.shutdown() }
    }

    @Test fun lostAcknowledgementUsesServerProgressAfterBackgroundWithoutASecondRequest() {
        val backend = Backend(reject = false)
        val server = JourneyHttpServer(backend::dispatch).also { it.start() }
        try {
            val restore = StateRestorationTester(compose)
            val api = AteneaApiClient(server.url("/"), { "synthetic-only" }, operatorRoleProvider = { "PLATFORM_ADMINISTRATOR" })
            restore.setContent { AteneaOperatorTheme { WorkSessionConversationScreen(api, 1, 29, {}) } }
            openChange(); waitFor("Validar cambio", button = true)
            compose.onNode(hasText("Validar cambio") and hasClickAction()).performClick()
            waitFor("Validando cambio")
            backgroundAndResume()
            restore.emulateSavedInstanceStateRestore()
            openChange(); waitFor("Validando cambio")
            compose.onNode(hasText("Validar cambio") and hasClickAction()).assertDoesNotExist()
            assertEquals(1, backend.requests.get())
            backend.state = "SUCCEEDED"
            waitFor("Crear PR", button = true)
            assertEquals(1, backend.requests.get())
        } finally { server.shutdown() }
    }

    private fun openChange() {
        compose.waitUntil(20_000) { compose.onAllNodes(hasText("Cambio nuevo de prueba")).fetchSemanticsNodes().isNotEmpty() }
        if (compose.onAllNodes(hasText("Ver pruebas y detalles")).fetchSemanticsNodes().isEmpty()) {
            compose.onNodeWithContentDescription("Opciones de conversación").performClick()
            compose.onNode(hasText("Cambio", substring = true) and hasClickAction()).performClick()
        }
    }

    private fun backgroundAndResume() {
        val before = compose.activityRule.scenario
        before.moveToState(Lifecycle.State.CREATED)
        before.moveToState(Lifecycle.State.RESUMED)
    }

    private fun waitFor(text: String, button: Boolean = false, substring: Boolean = false) {
        val matcher = hasText(text, substring = substring).let { if (button) it and hasClickAction() else it }
        compose.waitUntil(20_000) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(matcher).assertIsDisplayed()
    }

    private class Backend(val reject: Boolean) {
        val requests = AtomicInteger()
        val reads = AtomicInteger()
        val allowResponse = CountDownLatch(1)
        @Volatile var state = "NOT_STARTED"
        val change = "00000000-0000-4000-8000-000000000001"
        val fingerprint = "a".repeat(64)
        fun dispatch(request: JourneyHttpRequest): JourneyHttpResponse {
            val value = when {
                request.method == "GET" && request.path == "/api/mobile/sessions/29/summary" -> summary()
                request.method == "GET" && request.path == "/api/mobile/sessions/29/delivery" -> { reads.incrementAndGet(); delivery() }
                request.method == "POST" && request.path == "/api/sessions/29/validation-workflows" -> {
                    requests.incrementAndGet()
                    if (reject) {
                        check(allowResponse.await(30, TimeUnit.SECONDS))
                        return JourneyHttpResponse(422, JSONObject().put("message",
                            "Acceptance projection does not own the current source tree; validation state was not promoted").toString())
                    }
                    state = "RUNNING"
                    return JourneyHttpResponse(503, "{\"message\":\"Synthetic lost acknowledgement\"}")
                }
                else -> return JourneyHttpResponse(404, "{\"message\":\"Synthetic route absent\"}")
            }
            return JourneyHttpResponse(200, value.toString())
        }
        private fun summary() = JSONObject().put("conversation", JSONObject().put("view", JSONObject()
            .put("session", JSONObject().put("id", 29).put("projectId", 1).put("title", "Cambio nuevo de prueba")
                .put("status", "OPEN").put("developmentChangeKey", change).put("developmentChangeValidationState", "NOT_STARTED")
                .put("developmentChangeSourceState", "DIRTY"))
            .put("runInProgress", false).put("canCreateTurn", true)).put("recentTurns", JSONArray()))

        private fun delivery(): JSONObject {
            val observed = state
            val passed = if (observed == "SUCCEEDED") 4 else 0
            val attempts = JSONArray()
            listOf("BACKEND_TEST", "WEB_BUILD", "ANDROID_BUILD", "PLAYWRIGHT_ACCEPTANCE").take(passed).forEachIndexed { i, kind ->
                attempts.put(JSONObject().put("id", "00000000-0000-4000-8000-00000000010$i").put("workSessionId", 29)
                    .put("operation", kind).put("status", "SUCCEEDED").put("sourceTreeFingerprintSha256", fingerprint)
                    .put("definitionRevision", "synthetic-v1").put("exitCode", 0).put("summary", "PASS"))
            }
            val proof = JSONObject().put("changeKey", change).put("workSessionId", 29).put("sourceRevision", 1)
                .put("sourceFingerprintSha256", fingerprint).put("validationState", if (passed == 4) "CURRENT" else "NOT_STARTED")
                .put("passedOperations", passed).put("requiredOperations", 4).put("operations", attempts)
            if (observed != "NOT_STARTED") proof.put("workflow", JSONObject()
                .put("id", "00000000-0000-4000-8000-000000000002").put("workSessionId", 29).put("sourceRevision", 1)
                .put("sourceFingerprintSha256", fingerprint).put("state", observed)
                .put("phase", if (passed == 4) "FINISHED" else "QUEUED")
                .put("startedAt", "2026-10-20T10:00:00Z").put("updatedAt", "2026-10-20T10:01:00Z")
                .put("finishedAt", if (passed == 4) "2026-10-20T10:01:00Z" else JSONObject.NULL))
            return JSONObject().put("enabled", true).put("operations", JSONArray()).put("validation", proof)
        }
    }
}
