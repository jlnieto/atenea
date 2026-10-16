package com.atenea.android.api

import org.json.JSONObject
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.MockResponse

class MobileDeliveryTest {
    private val id=UUID.randomUUID()
    private fun plan(state: String="READY", expires: Long=100): MobileDeliveryOperation =
        parseMobileDeliveryOperation(JSONObject().put("id",id.toString()).put("operationId",UUID.randomUUID().toString())
            .put("sessionId",21).put("kind","RELEASE").put("target","APP_PROD").put("sourceCommit","1".repeat(40))
            .put("state",state).put("planSha256","2".repeat(64)).put("expiresAt",expires))
    @Test fun confirmationRequiresExactReadyUnexpiredPlan() {
        assertTrue(plan().canConfirm(99)); assertFalse(plan().canConfirm(100))
        for (state in listOf("PREPARING","ACCEPTED","APPLYING","SUCCEEDED","ROLLED_BACK","BLOCKED")) {
            assertFalse(plan(state).canConfirm(99))
        }
    }
    @Test fun operationIdentitySurvivesServerReconstructionAndIsNotACommand() {
        assertEquals(id,plan().id); assertEquals(21L,plan().sessionId)
        assertEquals(MobileDeliveryTarget.APP_PROD,plan().target)
    }
    @Test fun rollbackAndQuarantineAreTerminalButCannotBeCalledSuccess() {
        assertTrue(plan("ROLLED_BACK").terminal); assertTrue(plan("ROLLBACK_FAILED").terminal)
        assertFalse(plan("APPLYING").terminal)
        assertFalse(plan("QUARANTINED").terminal)
    }
    @Test fun legacyBackendDoesNotInventPermissionToIntegrate() {
        val state = parseMobileDeliveryState(JSONObject("{\"enabled\":true,\"operations\":[]}"))
        assertNull(state.integration)
        assertFalse(state.sourceUpdateEnabled)
        assertFalse(state.releaseRecoveryEnabled)
        assertNull(state.deployment)
        assertFalse(state.completionEnabled)
        assertNull(state.validation)
    }

    private fun validation(sessionId: Long = 21) = JSONObject().put("changeKey",id.toString()).put("workSessionId",sessionId)
        .put("sourceRevision",5).put("sourceFingerprintSha256","5".repeat(64)).put("validationState","STALE")
        .put("passedOperations",0).put("requiredOperations",4).put("operations",org.json.JSONArray())
        .put("lastAttempt",JSONObject.NULL)

    @Test fun deliveryIncludesRevisionBoundValidationAndRejectsMalformedOrFabricatedPassedCounts() {
        val root=JSONObject().put("enabled",true).put("operations",org.json.JSONArray()).put("validation",validation())
        val proof=parseMobileDeliveryState(root).validation!!
        assertEquals(5,proof.sourceRevision);assertEquals("STALE",proof.validationState);assertEquals(0,proof.passedOperations)
        assertFailsWith<IllegalArgumentException> { parseMobileDeliveryState(root.put("validation","invalid")) }
        assertFailsWith<IllegalArgumentException> { parseMobileDeliveryState(root.put("validation",validation().put("passedOperations",4))) }
    }

    @Test fun deliveryReadIsAuthenticatedAndForeignValidationCannotEnterTheSession() {
        val server=MockWebServer();server.start()
        try {
            val client=AteneaApiClient(server.url("/").toString().trimEnd('/'),{"synthetic-access"})
            val body=JSONObject().put("enabled",true).put("operations",org.json.JSONArray()).put("validation",validation())
            server.enqueue(MockResponse().setBody(body.toString()))
            assertEquals(5,runBlocking { client.fetchDelivery(21) }.validation!!.sourceRevision)
            val request=server.takeRequest()
            assertEquals("GET",request.method);assertEquals("/api/mobile/sessions/21/delivery",request.path)
            assertEquals("Bearer synthetic-access",request.getHeader("Authorization"))
            server.enqueue(MockResponse().setBody(body.put("validation",validation(22)).toString()))
            assertFailsWith<IllegalArgumentException> { runBlocking { client.fetchDelivery(21) } }
        } finally { server.shutdown() }
    }

    @Test fun completionIsAnEmptyAuthenticatedIntentAndReconciliationKeepsTheSameOperation() {
        val server=MockWebServer();server.start()
        try {
            val client=AteneaApiClient(server.url("/").toString().trimEnd('/'), { "synthetic-access" })
            val value=JSONObject().put("id",id.toString()).put("operationId",UUID.randomUUID().toString())
                .put("sessionId",21).put("kind","FINALIZE").put("target","APP_PROD").put("state","QUEUED")
            server.enqueue(MockResponse().setHeader("Content-Type","application/json").setBody(value.toString()))
            assertEquals(id,runBlocking { client.finalizeDelivery(21) }.id)
            var request=server.takeRequest()
            assertEquals("/api/mobile/sessions/21/delivery/finalize",request.path)
            assertEquals("{}",request.body.readUtf8());assertEquals("Bearer synthetic-access",request.getHeader("Authorization"))
            server.enqueue(MockResponse().setHeader("Content-Type","application/json").setBody(value.put("state","APPLYING").toString()))
            assertEquals(id,runBlocking { client.finalizeDelivery(21,reconcile=true) }.id)
            request=server.takeRequest();assertEquals("/api/mobile/sessions/21/delivery/finalize/reconcile",request.path)
            assertEquals("{}",request.body.readUtf8())
            server.enqueue(MockResponse().setHeader("Content-Type","application/json").setBody(value.put("sessionId",22).toString()))
            assertFailsWith<IllegalArgumentException> { runBlocking { client.finalizeDelivery(21) } }
        } finally { server.shutdown() }
    }
    @Test fun completionCapabilityRequiresActualBooleanNotLegacyOrCoercedValue() {
        val state=JSONObject().put("enabled",true).put("operations",org.json.JSONArray())
        assertFalse(parseMobileDeliveryState(state).completionEnabled)
        assertFalse(parseMobileDeliveryState(state.put("completionEnabled","true")).completionEnabled)
        assertTrue(parseMobileDeliveryState(state.put("completionEnabled",true)).completionEnabled)
    }

    private fun deployment() = JSONObject().put("sessionId",21).put("status","DEPLOYED").put("origin","OPERATOR")
        .put("sourceCommit","1".repeat(40)).put("integratedMergeCommit","2".repeat(40)).put("healthy",true)
        .put("observedAt",100L).put("planId",id.toString()).put("operationId",UUID.randomUUID().toString())
        .put("receiptSha256","3".repeat(64)).put("errorCode",JSONObject.NULL)

    @Test fun deploymentObservationIsSeparateFromMobileReceiptAndRequiresFreshCompleteEvidence() {
        val root=JSONObject().put("enabled",true).put("operations",org.json.JSONArray()).put("deployment",deployment())
        val state=parseMobileDeliveryState(root)
        assertTrue(state.operations.isEmpty()); assertEquals("OPERATOR",state.deployment!!.origin)
        assertTrue(state.deployment!!.isDeployed(120)); assertFalse(state.deployment!!.isDeployed(161))
        assertFalse(state.deployment!!.isDeployed(90))
        for ((key,value) in listOf("healthy" to "true", "sessionId" to "21", "origin" to "MOBILE_SYNTHESIZED",
                "sourceCommit" to "/path", "planId" to "foreign", "observedAt" to "100", "command" to "foreign",
                "status" to "SUCCEEDED")) {
            assertFailsWith<IllegalArgumentException> { parseDeploymentObservation(deployment().put(key,value)) }
        }
        val missing=deployment(); missing.remove("receiptSha256")
        assertFailsWith<IllegalArgumentException> { parseDeploymentObservation(missing) }
        assertFailsWith<IllegalArgumentException> { parseMobileDeliveryState(root.put("deployment","invalid")) }
    }

    private fun sourceUpdate() = JSONObject().put("id", id.toString()).put("sessionId",21)
        .put("state","RESOLVING").put("targetMainCommit","1".repeat(40))
        .put("sourceRevision",4).put("resolverRunId",105)

    private fun recoveryPlan() = JSONObject().put("id",id.toString()).put("operationId",UUID.randomUUID().toString())
        .put("sessionId",21).put("kind","RELEASE").put("target","APP_PROD").put("sourceCommit","1".repeat(40))
        .put("state","READY").put("planSha256","2".repeat(64)).put("expiresAt",100)
        .put("releaseRecovery",JSONObject().put("protocol","integrated-release-recovery/v1")
            .put("integrationOperationId",UUID.randomUUID().toString()).put("publishedHeadCommit","3".repeat(40))
            .put("integratedMergeCommit","4".repeat(40)).put("selectedMainCommit","1".repeat(40)))

    @Test fun publicationRecoverySendsOnlyEmptyAuthenticatedIntentAndRejectsForeignSession() {
        val server=MockWebServer(); server.start()
        try {
            val client=AteneaApiClient(server.url("/").toString().trimEnd('/'), { "synthetic-access" })
            server.enqueue(MockResponse().setHeader("Content-Type","application/json").setBody(recoveryPlan().toString()))
            val plan=runBlocking { client.prepareReleaseRecovery(21) }
            assertEquals("1".repeat(40),plan.sourceCommit)
            assertEquals("4".repeat(40),plan.releaseRecovery!!.integratedMergeCommit)
            val request=server.takeRequest()
            assertEquals("/api/mobile/sessions/21/delivery/release-recovery-plan",request.path)
            assertEquals("{}",request.body.readUtf8()); assertEquals("Bearer synthetic-access",request.getHeader("Authorization"))
            server.enqueue(MockResponse().setHeader("Content-Type","application/json").setBody(recoveryPlan().put("sessionId",22).toString()))
            assertFailsWith<IllegalArgumentException> { runBlocking { client.prepareReleaseRecovery(21) } }
        } finally { server.shutdown() }
    }
    @Test fun recoveryProofCannotBeContradictoryUnversionedOrContainArbitraryPaths() {
        for ((key,value) in listOf("protocol" to "foreign/v1", "selectedMainCommit" to "5".repeat(40),
                "integratedMergeCommit" to "/arbitrary/path", "integrationOperationId" to "foreign")) {
            val plan=recoveryPlan(); plan.getJSONObject("releaseRecovery").put(key,value)
            assertFailsWith<IllegalArgumentException> { parseMobileDeliveryOperation(plan) }
        }
        val plan=recoveryPlan(); plan.getJSONObject("releaseRecovery").put("command","foreign")
        assertFailsWith<IllegalArgumentException> { parseMobileDeliveryOperation(plan) }
        assertFailsWith<IllegalArgumentException> { parseMobileDeliveryOperation(recoveryPlan().put("releaseRecovery","foreign")) }
    }
    @Test fun publicationRecoveryCapabilityRequiresActualBooleanFromCurrentBackend() {
        val state=JSONObject().put("enabled",true).put("operations",org.json.JSONArray())
        assertFalse(parseMobileDeliveryState(state).releaseRecoveryEnabled)
        assertFalse(parseMobileDeliveryState(state.put("releaseRecoveryEnabled","true")).releaseRecoveryEnabled)
        assertTrue(parseMobileDeliveryState(state.put("releaseRecoveryEnabled",true)).releaseRecoveryEnabled)
    }

    @Test fun retryUsesExactFailedAttemptEmptyBodyAndRejectsForeignOperation() {
        val server=MockWebServer();server.start()
        try {
            val client=AteneaApiClient(server.url("/").toString().trimEnd('/'), { "synthetic-access" })
            server.enqueue(MockResponse().setHeader("Content-Type","application/json").setBody(sourceUpdate().put("resolverRunId",106).toString()))
            assertEquals(106L,runBlocking { client.retryDeliveryResolver(21,id,105) }.resolverRunId)
            val request=server.takeRequest()
            assertEquals("/api/mobile/sessions/21/delivery/source-updates/$id/resolver-runs/105/retry",request.path)
            assertEquals("{}",request.body.readUtf8());assertEquals("Bearer synthetic-access",request.getHeader("Authorization"))
            server.enqueue(MockResponse().setHeader("Content-Type","application/json").setBody(sourceUpdate().put("id",UUID.randomUUID().toString()).toString()))
            assertFailsWith<IllegalArgumentException> { runBlocking { client.retryDeliveryResolver(21,id,105) } }
        } finally { server.shutdown() }
    }

    @Test fun recoveryUsesSameOperationEmptyAuthenticatedRequestAndRejectsForeignIdentity() {
        val server=MockWebServer();server.start()
        try {
            val client=AteneaApiClient(server.url("/").toString().trimEnd('/'), { "synthetic-access" })
            server.enqueue(MockResponse().setHeader("Content-Type","application/json").setBody(sourceUpdate().put("state","UNCERTAIN").toString()))
            assertEquals(id,runBlocking { client.recoverDeliverySource(21,id) }.id)
            val request=server.takeRequest()
            assertEquals("/api/mobile/sessions/21/delivery/source-updates/$id/recover",request.path)
            assertEquals("{}",request.body.readUtf8());assertEquals("Bearer synthetic-access",request.getHeader("Authorization"))
            server.enqueue(MockResponse().setHeader("Content-Type","application/json").setBody(sourceUpdate().put("sessionId",22).toString()))
            assertFailsWith<IllegalArgumentException> { runBlocking { client.recoverDeliverySource(21,id) } }
        } finally { server.shutdown() }
    }

    @Test fun sourceUpdateUsesDurableIdentityAndRejectsInvalidStateOrCoercedIds() {
        val parsed=parseMobileSourceUpdate(sourceUpdate())
        assertFalse(parsed.recoveryAvailable)
        assertTrue(parseMobileSourceUpdate(sourceUpdate().put("recoveryAvailable",true)).recoveryAvailable)
        assertFalse(parseMobileSourceUpdate(sourceUpdate().put("recoveryAvailable","true")).recoveryAvailable)
        assertEquals(id,parsed.id); assertEquals(105L,parsed.resolverRunId)
        assertEquals(4L,parsed.sourceRevision)
        for ((key,value) in listOf("state" to "SUCCEEDED", "sessionId" to "21", "sourceRevision" to "4",
                "targetMainCommit" to "/path", "resolverRunId" to false)) {
            assertFailsWith<IllegalArgumentException> { parseMobileSourceUpdate(sourceUpdate().put(key,value)) }
        }
    }
    @Test fun baseObservationIsClosedAndMissingFieldNeverAssumesCurrent() {
        assertEquals("UNAVAILABLE",parseMobileSourceUpdate(sourceUpdate()).baseStatus)
        for (status in listOf("CURRENT","UPDATE_REQUIRED","UNAVAILABLE","NOT_REQUIRED")) {
            assertEquals(status,parseMobileSourceUpdate(sourceUpdate().put("baseStatus",status)).baseStatus)
        }
        assertFailsWith<IllegalArgumentException> { parseMobileSourceUpdate(sourceUpdate().put("baseStatus","ALLOW_PUSH")) }
    }
    @Test fun malformedSourceUpdateCannotBeInterpretedAsPermissionToStartAnotherOne() {
        val root=JSONObject().put("enabled",true).put("operations",org.json.JSONArray())
            .put("sourceUpdateEnabled",true).put("sourceUpdate","invalid")
        assertFailsWith<IllegalArgumentException> { parseMobileDeliveryState(root) }
        root.put("sourceUpdate",sourceUpdate()).put("sourceUpdateEnabled","true")
        assertFalse(parseMobileDeliveryState(root).sourceUpdateEnabled)
    }
    @Test fun mobileResolverRequestIsEmptyClosedAndBoundToSameSession() {
        val server=MockWebServer(); server.start()
        try {
            val client=AteneaApiClient(server.url("/").toString().trimEnd('/'), { "synthetic-access" })
            server.enqueue(MockResponse().setHeader("Content-Type","application/json").setBody(sourceUpdate().toString()))
            assertEquals(id,runBlocking { client.resolveDeliveryConflicts(21) }.id)
            val request=server.takeRequest()
            assertEquals("POST",request.method)
            assertEquals("/api/mobile/sessions/21/delivery/resolve-conflicts",request.path)
            assertEquals("{}",request.body.readUtf8())
            assertEquals("Bearer synthetic-access",request.getHeader("Authorization"))
            server.enqueue(MockResponse().setHeader("Content-Type","application/json").setBody(sourceUpdate().put("sessionId",22).toString()))
            assertFailsWith<IllegalArgumentException> { runBlocking { client.resolveDeliveryConflicts(21) } }
        } finally { server.shutdown() }
    }
    @Test fun mergeObservationIsStrictAndFailsClosedForConflictOrUnknownState() {
        val root = JSONObject().put("enabled",true).put("operations",org.json.JSONArray())
        val observed = JSONObject().put("sessionId",21).put("sourceCommit","1".repeat(40))
            .put("mergeState","CONFLICTS").put("canRequestIntegration",true)
        root.put("integration",observed)
        assertFalse(parseMobileDeliveryState(root).integration!!.allowsRequest)
        observed.put("mergeState","MERGEABLE")
        assertTrue(parseMobileDeliveryState(root).integration!!.allowsRequest)
        observed.put("canRequestIntegration","true")
        assertFalse(parseMobileDeliveryState(root).integration!!.allowsRequest)
        observed.put("canRequestIntegration",true).put("sourceCommit","not-a-commit")
        assertFalse(parseMobileDeliveryState(root).integration!!.allowsRequest)
        observed.put("sourceCommit","1".repeat(40)).put("mergeState","NEW_UNKNOWN_STATE")
        assertFalse(parseMobileDeliveryState(root).integration!!.allowsRequest)
    }
}
