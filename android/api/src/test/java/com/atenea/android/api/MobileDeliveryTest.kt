package com.atenea.android.api

import org.json.JSONObject
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
}
