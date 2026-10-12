package com.atenea.android.coreconsole

import com.atenea.android.api.AteneaApiClient
import com.atenea.android.api.AteneaApiException
import com.atenea.android.api.MobileSessionOperatorState
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RemoteCloseOperatorCoordinatorTest {
    @Test fun closingReconcilesOnlyTheCurrentSessionAndNeverRetriesItsPrompt() = runBlocking {
        val gateway = FakeGateway()
        val coordinator = coordinator(gateway)
        coordinator.accept(closing())
        assertTrue(gateway.closes.isEmpty())
        assertTrue(coordinator.runPrimaryAction(closing()))
        assertEquals(listOf(21L), gateway.closes)
        assertTrue(gateway.retries.isEmpty())
    }

    @Test fun foreignClosingTargetIsRejectedEvenForAdministrator() = runBlocking {
        val gateway = FakeGateway()
        assertFalse(coordinator(gateway).runPrimaryAction(closing().copy(targetWorkSessionId = 19)))
        assertTrue(gateway.closes.isEmpty())
    }

    @Test fun legacyBlockedStatesNeverProduceAdoptionOrEffects() = runBlocking {
        for (state in listOf("LEGACY_CLOSE_REQUIRED", "CLOSED_OWNER_BLOCKS_CAPACITY", "REMOTE_CLOSE_BLOCKED")) {
            val gateway = FakeGateway()
            assertFalse(coordinator(gateway).runPrimaryAction(closing().copy(state = state)))
            assertTrue(gateway.closes.isEmpty())
            assertTrue(gateway.retries.isEmpty())
        }
    }

    @Test fun independentSuccessorAndInconsistentRetryAreRejected() = runBlocking {
        val gateway = FakeGateway()
        val coordinator = coordinator(gateway)
        assertFalse(coordinator.runPrimaryAction(closing().copy(primaryAction = "START_FRESH_SESSION")))
        assertFalse(coordinator.runPrimaryAction(closing().copy(primaryAction = "RETRY_AGENT_RUN", targetAgentRunId = 103)))
        assertTrue(gateway.closes.isEmpty())
        assertTrue(gateway.retries.isEmpty())
    }

    @Test fun releasedCapacityRetriesExplicitlyInTheSameConversation() = runBlocking {
        val gateway = FakeGateway()
        val coordinator = coordinator(gateway, "ROUTINE_OPERATOR")
        val state = closing().copy(state = "CAPACITY_RELEASED", primaryAction = "RETRY_AGENT_RUN",
            targetWorkSessionId = 19, targetAgentRunId = 103)
        coordinator.accept(state)
        assertTrue(gateway.retries.isEmpty())
        assertTrue(coordinator.runPrimaryAction(state))
        assertEquals(listOf(103L to 21L), gateway.retries)
        assertTrue(gateway.closes.isEmpty())
        assertTrue(coordinator.state.value.notice!!.contains("tarea original"))
    }

    @Test fun unavailableOrUnauthorizedActionHasNoEffects() = runBlocking {
        val gateway = FakeGateway()
        assertFalse(coordinator(gateway, "ROUTINE_OPERATOR").runPrimaryAction(
            closing().copy(requiredRole = "PLATFORM_ADMINISTRATOR")))
        assertFalse(coordinator(gateway).runPrimaryAction(closing().copy(primaryActionAvailable = false)))
        assertFalse(coordinator(gateway).runPrimaryAction(closing().copy(surfaceEnabled = false)))
        assertTrue(gateway.closes.isEmpty())
    }

    @Test fun conflictRequiresRefreshWithoutInventingAnotherOperation() = runBlocking {
        val gateway = FakeGateway(AteneaApiException(409, "raw-infrastructure"))
        val coordinator = coordinator(gateway)
        coordinator.accept(closing())
        assertFalse(coordinator.runPrimaryAction(closing()))
        assertTrue(coordinator.state.value.requiresRefresh)
        assertFalse(coordinator.runPrimaryAction(closing()))
        assertEquals(listOf(21L), gateway.closes)
        coordinator.accept(closing())
        assertFalse(coordinator.state.value.requiresRefresh)
        gateway.failure = null
        assertTrue(coordinator.runPrimaryAction(closing()))
        assertEquals(listOf(21L, 21L), gateway.closes)
    }

    @Test fun transportFailureKeepsSafeCopyAndDoesNotAutomaticallyRetry() = runBlocking {
        val gateway = FakeGateway(AteneaApiException(503, "socket-secret"))
        val coordinator = coordinator(gateway)
        assertFalse(coordinator.runPrimaryAction(closing()))
        assertEquals(listOf(21L), gateway.closes)
        assertFalse(coordinator.state.value.error!!.contains("socket"))
        assertFalse(coordinator.state.value.pending)
    }

    @Test fun authorizationHierarchyMatchesTheSharedOperatorRoles() {
        assertTrue(operatorHasRequiredRole("ROUTINE_OPERATOR", "ROUTINE_OPERATOR"))
        assertTrue(operatorHasRequiredRole("PLATFORM_ADMINISTRATOR", "PRIVILEGED_OPERATOR"))
        assertFalse(operatorHasRequiredRole("ROUTINE_OPERATOR", "PRIVILEGED_OPERATOR"))
        assertFalse(operatorHasRequiredRole(null, "ROUTINE_OPERATOR"))
    }

    private fun coordinator(gateway: FakeGateway, role: String = "PLATFORM_ADMINISTRATOR") =
        RemoteCloseOperatorCoordinator(AteneaApiClient("https://example.invalid", { "unused" }), 21,
            gateway, { role })

    private class FakeGateway(var failure: Exception? = null) : RemoteCloseGateway {
        val closes = mutableListOf<Long>()
        val retries = mutableListOf<Pair<Long, Long>>()
        override suspend fun resumeClose(sessionId: Long) {
            closes += sessionId
            failure?.let { throw it }
        }
        override suspend fun retryRun(runId: Long, workSessionId: Long): Boolean {
            retries += runId to workSessionId
            return true
        }
    }

    private fun closing() = MobileSessionOperatorState(true, "CLOSING_REMOTE",
        "Cerrando el cambio", "Confirmación pendiente.", "RECONCILE_REMOTE_CLOSE",
        "Reconciliar cierre", true, "ROUTINE_OPERATOR", 21, null)
}
