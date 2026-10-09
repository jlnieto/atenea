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
    }

    private fun sourceUpdate() = JSONObject().put("id", id.toString()).put("sessionId",21)
        .put("state","RESOLVING").put("targetMainCommit","1".repeat(40))
        .put("sourceRevision",4).put("resolverRunId",105)

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
