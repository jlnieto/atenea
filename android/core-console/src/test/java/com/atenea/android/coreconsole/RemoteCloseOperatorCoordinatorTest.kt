package com.atenea.android.coreconsole

import com.atenea.android.api.AteneaApiClient
import com.atenea.android.api.AteneaApiException
import com.atenea.android.api.LegacyRemoteCloseOperation
import com.atenea.android.api.LegacyRemoteClosePlan
import com.atenea.android.api.MobileSessionOperatorState
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RemoteCloseOperatorCoordinatorTest {

    @Test
    fun enforcesRoleAndClosedStatePairBeforeCallingGateway() = runBlocking {
        val gateway = FakeGateway()
        val coordinator = coordinator(gateway, role = "ROUTINE_OPERATOR")

        assertFalse(coordinator.runPrimaryAction(blockedCapacityState()))
        assertTrue(gateway.planKeys.isEmpty())

        val inconsistent = blockedCapacityState().copy(
            state = "CLOSED_OWNER_BLOCKS_CAPACITY",
            primaryAction = "RETRY_AGENT_RUN",
            targetAgentRunId = 96
        )
        val administrator = coordinator(gateway, role = "PLATFORM_ADMINISTRATOR")
        assertFalse(administrator.runPrimaryAction(inconsistent))
        assertTrue(gateway.retryRuns.isEmpty())
    }

    @Test
    fun reusesPlanKeyAfterTransportFailureAndNeverConfirmsImplicitly() = runBlocking {
        val gateway = FakeGateway(failFirstPlan = true)
        val coordinator = coordinator(gateway, role = "PLATFORM_ADMINISTRATOR")
        val state = blockedCapacityState()

        assertFalse(coordinator.runPrimaryAction(state))
        assertNull(coordinator.state.value.plan)
        assertFalse(coordinator.runPrimaryAction(state))

        assertEquals(listOf("key-1", "key-1"), gateway.planKeys)
        assertEquals(plan(), coordinator.state.value.plan)
        assertTrue(gateway.confirmationKeys.isEmpty())
    }

    @Test
    fun reusesConfirmationKeyAfterLostResponseAndKeepsPlanRecoverable() = runBlocking {
        val gateway = FakeGateway(failFirstConfirmation = true)
        val coordinator = coordinator(gateway, role = "PLATFORM_ADMINISTRATOR")
        val state = blockedCapacityState()
        coordinator.runPrimaryAction(state)

        assertFalse(coordinator.confirmLegacyReconciliation(state))
        assertEquals(plan(), coordinator.state.value.plan)
        assertTrue(coordinator.confirmLegacyReconciliation(state))

        assertEquals(listOf("key-2", "key-2"), gateway.confirmationKeys)
        assertNull(coordinator.state.value.plan)
        assertTrue(coordinator.state.value.notice!!.startsWith("Capacidad liberada"))
    }

    @Test
    fun retryRequiresCapacityReleasedAndAnExplicitTap() = runBlocking {
        val gateway = FakeGateway()
        val coordinator = coordinator(gateway, role = "ROUTINE_OPERATOR")
        val state = capacityReleasedState()

        coordinator.accept(state)
        assertTrue(gateway.retryRuns.isEmpty())
        assertTrue(coordinator.runPrimaryAction(state))

        assertEquals(listOf(96L to 17L), gateway.retryRuns)
        assertTrue(coordinator.state.value.notice!!.contains("tarea original"))
    }

    @Test
    fun mapsDeterministicAndTransportFailuresWithoutWorkerUnavailableCopy() {
        assertEquals(
            "El estado cambió o la confirmación caducó. Actualiza y genera una nueva confirmación.",
            remoteCloseActionError(AteneaApiException(409, "raw"))
        )
        val transport = remoteCloseActionError(IllegalStateException("socket and infrastructure detail"))
        assertFalse(transport.contains("worker", ignoreCase = true))
        assertFalse(transport.contains("socket", ignoreCase = true))
    }

    private fun coordinator(gateway: FakeGateway, role: String): RemoteCloseOperatorCoordinator {
        var key = 0
        return RemoteCloseOperatorCoordinator(
            apiClient = AteneaApiClient("https://example.invalid", { "unused" }),
            currentWorkSessionId = 17,
            gateway = gateway,
            operatorRoleProvider = { role },
            idFactory = { "key-${++key}" }
        )
    }

    private class FakeGateway(
        private val failFirstPlan: Boolean = false,
        private val failFirstConfirmation: Boolean = false
    ) : RemoteCloseGateway {
        val planKeys = mutableListOf<String>()
        val confirmationKeys = mutableListOf<String>()
        val retryRuns = mutableListOf<Pair<Long, Long>>()

        override suspend fun resumeClose(sessionId: Long) = Unit

        override suspend fun createPlan(sessionId: Long, idempotencyKey: String): LegacyRemoteClosePlan {
            planKeys += idempotencyKey
            if (failFirstPlan && planKeys.size == 1) throw AteneaApiException(503, "transport")
            return plan()
        }

        override suspend fun confirmPlan(
            sessionId: Long,
            plan: LegacyRemoteClosePlan,
            idempotencyKey: String
        ): LegacyRemoteCloseOperation {
            confirmationKeys += idempotencyKey
            if (failFirstConfirmation && confirmationKeys.size == 1) {
                throw AteneaApiException(503, "response lost")
            }
            return operation()
        }

        override suspend fun retryRun(runId: Long, workSessionId: Long): Boolean {
            retryRuns += runId to workSessionId
            return true
        }
    }

    companion object {
        private fun blockedCapacityState() = MobileSessionOperatorState(
            surfaceEnabled = true,
            state = "CLOSED_OWNER_BLOCKS_CAPACITY",
            title = "Bloqueada por una sesión cerrada",
            blocker = "Otra sesión cerrada conserva la capacidad necesaria.",
            primaryAction = "RECONCILE_REMOTE_CLOSE",
            primaryActionLabel = "Reconciliar cierre",
            primaryActionAvailable = true,
            requiredRole = "PLATFORM_ADMINISTRATOR",
            targetWorkSessionId = 16,
            targetAgentRunId = 96
        )

        private fun capacityReleasedState() = blockedCapacityState().copy(
            state = "CAPACITY_RELEASED",
            title = "Capacidad liberada",
            blocker = null,
            primaryAction = "RETRY_AGENT_RUN",
            primaryActionLabel = "Reintentar tarea",
            requiredRole = "ROUTINE_OPERATOR"
        )

        private fun plan() = LegacyRemoteClosePlan(
            planId = "00000000-0000-0000-0000-000000000016",
            workSessionId = 16,
            operation = "RECONCILE_REMOTE_CLOSE",
            state = "READY_FOR_CONFIRMATION",
            requiredRole = "PLATFORM_ADMINISTRATOR",
            ownershipFingerprintSha256 = "a".repeat(64),
            expiresAt = "2026-08-04T18:00:00Z",
            consumed = false,
            expectedImpact = "Retirar ownership remoto activo de esta sesión.",
            valuesExposed = false,
            createdAt = "2026-08-04T17:55:00Z"
        )

        private fun operation() = LegacyRemoteCloseOperation(
            operationId = "00000000-0000-0000-0000-000000000017",
            planId = plan().planId,
            workSessionId = 16,
            operation = "RECONCILE_REMOTE_CLOSE",
            state = "RELEASED",
            revision = 2,
            ownershipFingerprintSha256 = "a".repeat(64),
            errorCode = null,
            errorCategory = null,
            nextAction = "REFRESH",
            retryable = false,
            receiptSha256 = "b".repeat(64),
            requestedAt = "2026-08-04T17:56:00Z",
            updatedAt = "2026-08-04T17:56:01Z",
            releasedAt = "2026-08-04T17:56:01Z",
            valuesExposed = false
        )
    }
}
