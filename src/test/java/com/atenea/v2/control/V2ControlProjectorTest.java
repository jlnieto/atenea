package com.atenea.v2.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.atenea.api.v2.control.V2DeterministicFailureResponse;
import com.atenea.api.v2.control.V2Phase;
import com.atenea.api.v2.control.V2PrimaryActionKind;
import com.atenea.service.v2control.V2ControlProjector;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class V2ControlProjectorTest {

    private static final Instant UPDATED_AT = Instant.parse("2026-08-12T12:00:00Z");

    private final V2ControlProjector projector = new V2ControlProjector();

    @Test
    void derivesOrdinaryPhasesAndActionsFromAuthoritativeState() {
        var ready = projector.project(V2OperationState.READY, 4, null, null, null, UPDATED_AT);
        var running = projector.project(V2OperationState.RUNNING, 5, null, null, null, UPDATED_AT);
        var reconciling = projector.project(
                V2OperationState.RECONCILING, 6, null, null, null, UPDATED_AT);
        var succeeded = projector.project(
                V2OperationState.SUCCEEDED, 7, null, null, null, UPDATED_AT);

        assertEquals(V2Phase.READY, ready.phase());
        assertEquals(V2PrimaryActionKind.NONE, ready.primaryAction().kind());
        assertEquals(V2Phase.IN_PROGRESS, running.phase());
        assertEquals(V2PrimaryActionKind.WAIT, running.primaryAction().kind());
        assertEquals(running.primaryAction(), reconciling.primaryAction());
        assertEquals(V2Phase.COMPLETED, succeeded.phase());
        assertNull(succeeded.blocking());
    }

    @Test
    void classifiesEveryFailureWithoutTurningDeterministicFailuresIntoRetry() {
        var transport = failed(V2FailureCategory.TRANSPORT, "WORKER_TIMEOUT");
        var capacity = failed(V2FailureCategory.CAPACITY, "NO_SLOT_AVAILABLE");
        var validation = failed(V2FailureCategory.VALIDATION, "REQUEST_INVALID");
        var policy = failed(V2FailureCategory.POLICY, "PROJECT_DISABLED");
        var ownership = failed(V2FailureCategory.OWNERSHIP, "TARGET_NOT_OWNED");

        assertEquals(V2Phase.RECONCILIATION_REQUIRED, transport.phase());
        assertEquals(V2PrimaryActionKind.RECONCILE, transport.primaryAction().kind());
        assertEquals(V2Phase.WAITING_FOR_CAPACITY, capacity.phase());
        assertEquals(V2PrimaryActionKind.WAIT, capacity.primaryAction().kind());
        assertEquals(V2Phase.ACTION_REQUIRED, validation.phase());
        assertEquals(V2PrimaryActionKind.CORRECT_REQUEST, validation.primaryAction().kind());
        assertEquals(V2Phase.BLOCKED, policy.phase());
        assertEquals(V2PrimaryActionKind.NONE, policy.primaryAction().kind());
        assertEquals(V2Phase.BLOCKED, ownership.phase());
        assertEquals(
                V2PrimaryActionKind.CONTACT_PLATFORM_ADMINISTRATOR,
                ownership.primaryAction().kind());
        assertFalse(ownership.primaryAction().enabled());
    }

    @Test
    void buildsFixedPolicyAndOwnershipHttpResponses() {
        V2DeterministicFailureResponse policy = projector.policyDenied(
                3,
                "V2_PROJECT_POLICY_DISABLED",
                "La capacidad V2 no está habilitada para este proyecto.",
                UPDATED_AT);
        V2DeterministicFailureResponse ownership = projector.ownershipBlocked(
                9,
                "V2_TARGET_OWNERSHIP_UNVERIFIED",
                "No se puede demostrar la propiedad exacta del recurso.",
                UPDATED_AT);

        assertEquals(403, policy.status());
        assertEquals(V2FailureCategory.POLICY, policy.failureCategory());
        assertFalse(policy.retryable());
        assertEquals(409, ownership.status());
        assertEquals(V2FailureCategory.OWNERSHIP, ownership.failureCategory());
        assertFalse(ownership.retryable());
        assertEquals(ownership.failureCode(), ownership.projection().blocking().code());
    }

    @Test
    void rejectsContradictoryStateAndFailureInputs() {
        assertThrows(IllegalArgumentException.class, () -> projector.project(
                V2OperationState.RUNNING,
                1,
                V2FailureCategory.POLICY,
                "PROJECT_DISABLED",
                "Proyecto deshabilitado",
                UPDATED_AT));
        assertThrows(IllegalArgumentException.class, () -> projector.project(
                V2OperationState.FAILED,
                1,
                null,
                null,
                null,
                UPDATED_AT));
        assertThrows(IllegalArgumentException.class, () -> failed(
                V2FailureCategory.OWNERSHIP,
                "not-symbolic"));
    }

    private com.atenea.api.v2.control.V2ControlProjectionResponse failed(
            V2FailureCategory category,
            String code) {
        return projector.project(
                V2OperationState.FAILED,
                8,
                category,
                code,
                "Estado operativo sanitizado",
                UPDATED_AT);
    }
}
