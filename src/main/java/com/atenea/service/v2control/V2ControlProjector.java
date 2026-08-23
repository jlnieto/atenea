package com.atenea.service.v2control;

import com.atenea.api.v2.control.V2BlockingResponse;
import com.atenea.api.v2.control.V2ControlProjectionResponse;
import com.atenea.api.v2.control.V2DeterministicFailureResponse;
import com.atenea.api.v2.control.V2Phase;
import com.atenea.api.v2.control.V2PrimaryActionResponse;
import com.atenea.v2.control.V2FailureCategory;
import com.atenea.v2.control.V2OperationState;
import java.time.Instant;
import org.springframework.stereotype.Service;

@Service
public class V2ControlProjector {

    public V2ControlProjectionResponse project(
            V2OperationState state,
            long revision,
            V2FailureCategory failureCategory,
            String failureCode,
            String safeMessage,
            Instant updatedAt) {
        if (state == null) {
            throw new IllegalArgumentException("Authoritative operation state is required");
        }
        if (state == V2OperationState.FAILED) {
            if (failureCategory == null) {
                throw new IllegalArgumentException("A failed operation requires a failure category");
            }
            return failed(revision, failureCategory, failureCode, safeMessage, updatedAt);
        }
        if (failureCategory != null || failureCode != null || safeMessage != null) {
            throw new IllegalArgumentException("A non-failed operation cannot expose blocking failure data");
        }
        return switch (state) {
            case READY -> projection(
                    V2Phase.READY, revision, null, V2PrimaryActionResponse.none(), updatedAt);
            case RUNNING, RECONCILING -> projection(
                    V2Phase.IN_PROGRESS,
                    revision,
                    null,
                    V2PrimaryActionResponse.waitForUpdate(),
                    updatedAt);
            case SUCCEEDED -> projection(
                    V2Phase.COMPLETED, revision, null, V2PrimaryActionResponse.none(), updatedAt);
            case FAILED -> throw new IllegalStateException("Failed state must be projected with failure data");
        };
    }

    public V2DeterministicFailureResponse policyDenied(
            long revision,
            String failureCode,
            String safeMessage,
            Instant updatedAt) {
        V2ControlProjectionResponse projection = failed(
                revision, V2FailureCategory.POLICY, failureCode, safeMessage, updatedAt);
        return new V2DeterministicFailureResponse(
                403,
                V2FailureCategory.POLICY,
                failureCode,
                safeMessage,
                false,
                projection);
    }

    public V2DeterministicFailureResponse ownershipBlocked(
            long revision,
            String failureCode,
            String safeMessage,
            Instant updatedAt) {
        V2ControlProjectionResponse projection = failed(
                revision, V2FailureCategory.OWNERSHIP, failureCode, safeMessage, updatedAt);
        return new V2DeterministicFailureResponse(
                409,
                V2FailureCategory.OWNERSHIP,
                failureCode,
                safeMessage,
                false,
                projection);
    }

    private V2ControlProjectionResponse failed(
            long revision,
            V2FailureCategory category,
            String failureCode,
            String safeMessage,
            Instant updatedAt) {
        V2BlockingResponse blocking = new V2BlockingResponse(category, failureCode, safeMessage);
        return switch (category) {
            case TRANSPORT -> projection(
                    V2Phase.RECONCILIATION_REQUIRED,
                    revision,
                    blocking,
                    V2PrimaryActionResponse.reconcile(),
                    updatedAt);
            case CAPACITY -> projection(
                    V2Phase.WAITING_FOR_CAPACITY,
                    revision,
                    blocking,
                    V2PrimaryActionResponse.waitForUpdate(),
                    updatedAt);
            case VALIDATION -> projection(
                    V2Phase.ACTION_REQUIRED,
                    revision,
                    blocking,
                    V2PrimaryActionResponse.correctRequest(),
                    updatedAt);
            case POLICY -> projection(
                    V2Phase.BLOCKED,
                    revision,
                    blocking,
                    V2PrimaryActionResponse.none(),
                    updatedAt);
            case OWNERSHIP -> projection(
                    V2Phase.BLOCKED,
                    revision,
                    blocking,
                    V2PrimaryActionResponse.contactPlatformAdministrator(),
                    updatedAt);
        };
    }

    private V2ControlProjectionResponse projection(
            V2Phase phase,
            long revision,
            V2BlockingResponse blocking,
            V2PrimaryActionResponse primaryAction,
            Instant updatedAt) {
        return new V2ControlProjectionResponse(
                phase,
                revision,
                false,
                blocking,
                primaryAction,
                updatedAt);
    }
}
