package com.atenea.android.coreconsole

import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import com.atenea.android.api.AteneaApiClient
import android.graphics.Bitmap
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Real conversation screen + API parser + HTTP. The conversation summary deliberately stays stale. */
class ChangeWorkflowJourneyTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun missingObservationOffersReadOnlyConsultationNotPublicationFromCachedConversation() {
        val backend=JourneyBackend();backend.stage="NO_PROOF"
        val server=JourneyHttpServer(backend::dispatch);server.start()
        try {
            val api=AteneaApiClient(server.url("/").toString(),{"synthetic-only"},operatorRoleProvider={"PLATFORM_ADMINISTRATOR"})
            compose.setContent { AteneaOperatorTheme { WorkSessionConversationScreen(api,1,26,{}) } }
            compose.waitUntil(20_000) { compose.onAllNodes(hasText("Mismo cambio")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Opciones de conversación").performClick()
            compose.onNode(hasText("Cambio",substring=true) and hasClickAction()).performClick()
            waitForButton("Consultar estado")
            compose.onNode(hasText("Validar cambio") and hasClickAction()).assertDoesNotExist()
            compose.onNode(hasText("Actualizar la misma PR") and hasClickAction()).assertDoesNotExist()
            compose.onNode(hasText("Consultar estado") and hasClickAction()).performClick()
            assertEquals(0,backend.preparations.get());assertEquals(0,backend.checks.get());assertEquals(0,backend.publications.get())
            backend.stage="PREPARED"
            waitForButton("Validar cambio")
            assertEquals(0,backend.preparations.get());assertEquals(0,backend.checks.get());assertEquals(0,backend.publications.get())
        } finally { server.shutdown() }
    }

    @Test fun sameScreenUsesFreshRevisionThroughSlowPreparationBackgroundValidationAndSamePrUpdate() {
        val backend=JourneyBackend()
        val server=JourneyHttpServer(backend::dispatch);server.start()
        try {
            val api=AteneaApiClient(server.url("/").toString(),{"synthetic-only"},operatorRoleProvider={"PLATFORM_ADMINISTRATOR"})
            compose.setContent { AteneaOperatorTheme { WorkSessionConversationScreen(api,1,26,{}) } }
            compose.waitUntil(20_000) { compose.onAllNodes(hasText("Mismo cambio") and hasClickAction()).fetchSemanticsNodes().isNotEmpty()
                || compose.onAllNodes(hasText("Mismo cambio")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Opciones de conversación").performClick()
            compose.onNode(hasText("Cambio",substring=true) and hasClickAction()).performClick()
            waitForButton("Actualizar base con main")
            compose.onNode(hasText("Actualizar base con main") and hasClickAction()).performClick()
            assertTrue(backend.preparationStarted.await(10,TimeUnit.SECONDS))
            compose.onNode(hasText("Actualizar base con main") and hasClickAction()).assertDoesNotExist()
            compose.onNode(hasText("Actualizar la misma PR") and hasClickAction()).assertDoesNotExist()
            compose.onNodeWithText("Actualizando base con main").assertIsDisplayed()
            screenshot("request-pending")
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            assertEquals(1,backend.preparations.get())
            backend.allowPreparationReply.countDown()
            waitForButton("Validar cambio")
            compose.onNode(hasText("Actualizar la misma PR") and hasClickAction()).assertDoesNotExist()
            compose.onNodeWithText("Revisión 5 pendiente de validar · 0/4 comprobaciones.").assertIsDisplayed()
            screenshot("revision-5-needs-validation")
            compose.onNode(hasText("Validar cambio") and hasClickAction()).performClick()
            compose.waitUntil(15_000) { backend.validationRequests.get()==1 }
            compose.onNode(hasText("Actualizar la misma PR") and hasClickAction()).assertDoesNotExist()
            compose.waitUntil(15_000) { compose.onAllNodes(hasText("Validando cambio")).fetchSemanticsNodes().isNotEmpty() }
            screenshot("validating")
            // Server ticks, not four mobile POSTs, finish this workflow. The
            // app may leave/resume without resubmitting or driving the checks.
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            backend.completeValidation()
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            waitForButton("Actualizar la misma PR",30_000)
            assertEquals(4,backend.checks.get())
            assertEquals(1,backend.validationRequests.get())
            compose.onNode(hasText("Actualizar la misma PR") and hasClickAction()).performClick()
            compose.waitUntil(15_000) { backend.publications.get()==1 }
            compose.onNode(hasText("Actualizar la misma PR") and hasClickAction()).assertDoesNotExist()
            compose.waitUntil(15_000) { compose.onAllNodes(hasText("Esperando comprobaciones de GitHub")).fetchSemanticsNodes().isNotEmpty() }
            screenshot("same-pr-waiting-ci")
            backend.stage="PR_READY"
            waitForButton("Integrar cambio")
            assertEquals(1,backend.preparations.get());assertEquals(1,backend.publications.get())
            compose.onNodeWithText("Versiones y publicaciones").assertIsDisplayed()
            compose.onNodeWithText("APK publicada",substring=true).assertDoesNotExist()
            screenshot("same-pr-ready")
        } finally { backend.allowPreparationReply.countDown();server.shutdown() }
    }

    private fun waitForButton(text:String,timeout:Long=20_000) {
        compose.waitUntil(timeout) { compose.onAllNodes(hasText(text) and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText(text) and hasClickAction()).assertIsDisplayed()
    }
    private fun screenshot(name:String) {
        val bitmap=compose.onRoot().captureToImage().asAndroidBitmap()
        File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,"journey-$name.png")
            .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
    }

    private class JourneyBackend {
        @Volatile var stage="OLD_BASE"
        val preparations=AtomicInteger();val checks=AtomicInteger();val publications=AtomicInteger()
        val validationRequests=AtomicInteger()
        val preparationStarted=CountDownLatch(1);val allowPreparationReply=CountDownLatch(1)
        val change="00000000-0000-4000-8000-000000000001"
        val oldSource="00000000-0000-4000-8000-000000000004"
        val newSource="00000000-0000-4000-8000-000000000005"
        val publication="00000000-0000-4000-8000-000000000072"
        fun dispatch(request:JourneyHttpRequest):JourneyHttpResponse {
            val path=request.path
            val value=when {
                request.method=="GET" && path=="/api/mobile/sessions/26/summary" -> summary()
                request.method=="GET" && path=="/api/mobile/sessions/26/delivery" -> delivery()
                request.method=="POST" && path=="/api/mobile/sessions/26/delivery/resolve-conflicts" -> {
                    preparations.incrementAndGet();preparationStarted.countDown()
                    check(allowPreparationReply.await(30,TimeUnit.SECONDS))
                    stage="PREPARED"
                    source(5,"READY_TO_FINALIZE","CURRENT")
                }
                request.method=="POST" && path=="/api/sessions/26/validation-workflows" -> {
                    validationRequests.incrementAndGet();stage="VALIDATING"
                    workflow("RUNNING")
                }
                request.method=="POST" && path=="/api/mobile/sessions/26/delivery/pr" -> {
                    publications.incrementAndGet();stage="PR_WAITING";operation("QUEUED")
                }
                else -> return JourneyHttpResponse(404,"{\"message\":\"Synthetic route absent\"}")
            }
            return JourneyHttpResponse(200,value.toString())
        }
        fun completeValidation() { checks.set(4);stage="VALIDATED" }
        private fun workflow(state:String)=JSONObject().put("id","00000000-0000-4000-8000-000000000003")
            .put("workSessionId",26).put("sourceRevision",5).put("sourceFingerprintSha256","5".repeat(64))
            .put("state",state).put("phase",if(state=="SUCCEEDED") "FINISHED" else "QUEUED")
            .put("startedAt","2026-10-16T15:17:00Z").put("updatedAt","2026-10-16T15:18:00Z")
            .put("finishedAt",if(state=="SUCCEEDED") "2026-10-16T15:18:00Z" else JSONObject.NULL)
        private fun summary()=JSONObject().put("conversation",JSONObject().put("view",JSONObject()
            .put("session",JSONObject().put("id",26).put("projectId",1).put("title","Mismo cambio").put("status","OPEN")
                .put("developmentChangeKey",change).put("developmentChangeValidationState","CURRENT")
                .put("developmentChangeSourceState","DIRTY"))
            .put("runInProgress",false).put("canCreateTurn",true)).put("recentTurns",JSONArray()))
        private fun source(revision:Int,state:String,base:String)=JSONObject().put("id",if(revision==4) oldSource else newSource)
            .put("sessionId",26).put("state",state).put("targetMainCommit","1".repeat(40)).put("sourceRevision",revision)
            .put("resolverRunId",JSONObject.NULL).put("baseStatus",base).put("updatedAt","2026-10-16T15:16:25Z")
        private fun proof(revision:Int,passed:Int,current:Boolean):JSONObject {
            val operations=JSONArray()
            listOf("BACKEND_TEST","WEB_BUILD","ANDROID_BUILD","PLAYWRIGHT_ACCEPTANCE").take(passed).forEachIndexed { index,kind ->
                operations.put(JSONObject().put("id","00000000-0000-4000-8000-00000000010$index").put("workSessionId",26)
                    .put("operation",kind).put("status","SUCCEEDED").put("sourceTreeFingerprintSha256",revision.toString().repeat(64))
                    .put("definitionRevision","synthetic-v1").put("exitCode",0).put("summary","PASS"))
            }
            return JSONObject().put("changeKey",change).put("workSessionId",26).put("sourceRevision",revision)
                .put("sourceFingerprintSha256",revision.toString().repeat(64)).put("validationState",if(current) "CURRENT" else "STALE")
                .put("passedOperations",passed).put("requiredOperations",4).put("operations",operations)
        }
        private fun operation(state:String)=JSONObject().put("id",publication).put("operationId",publication).put("sessionId",26)
            .put("kind","PUBLISH_PR").put("target","APP_PROD").put("state",state).put("updatedAt","2026-10-16T15:30:00Z")
            .put("pullRequestUrl","https://github.com/jlnieto/atenea/pull/72")
        private fun delivery():JSONObject {
            val s=stage
            val old=s=="OLD_BASE"
            val validated=s in setOf("VALIDATED","PR_WAITING","PR_READY")
            val revision=if(old) 4 else 5
            val operations=JSONArray()
            if(s in setOf("PR_WAITING","PR_READY")) operations.put(operation(if(s=="PR_READY") "SUCCEEDED" else "WAITING_CI"))
            return JSONObject().put("enabled",true).put("operations",operations).put("sourceUpdateEnabled",true)
                .put("sourceUpdate",source(revision,if(s in setOf("PR_WAITING","PR_READY")) "PUBLISHED"
                    else if(old) "RESOLVER_COMPLETED" else "READY_TO_FINALIZE",if(old) "UPDATE_REQUIRED" else "CURRENT"))
                .put("validation",proof(revision,if(old || validated) 4 else checks.get(),old || validated).also {
                    if(s in setOf("VALIDATING","VALIDATED","PR_WAITING","PR_READY"))
                        it.put("workflow",workflow(if(validated) "SUCCEEDED" else "RUNNING"))
                })
                .put("integration",JSONObject().put("sessionId",26).put("sourceCommit","2".repeat(40))
                    .put("mergeState",if(s=="PR_READY") "MERGEABLE" else "STALE_SOURCE").put("canRequestIntegration",s=="PR_READY"))
                .also { if(s=="NO_PROOF") it.remove("validation") }
        }
    }
}
