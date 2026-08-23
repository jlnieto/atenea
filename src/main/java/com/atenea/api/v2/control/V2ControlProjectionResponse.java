package com.atenea.api.v2.control;

import java.time.Instant;

public record V2ControlProjectionResponse(
        V2Phase phase,
        long stateRevision,
        boolean stale,
        V2BlockingResponse blocking,
        V2PrimaryActionResponse primaryAction,
        Instant updatedAt) {

    public V2ControlProjectionResponse {
        if (phase == null || primaryAction == null || updatedAt == null) {
            throw new IllegalArgumentException("Phase, primary action and update time are required");
        }
        if (stateRevision < 0) {
            throw new IllegalArgumentException("State revision must be non-negative");
        }
        boolean failurePhase = phase == V2Phase.RECONCILIATION_REQUIRED
                || phase == V2Phase.WAITING_FOR_CAPACITY
                || phase == V2Phase.ACTION_REQUIRED
                || phase == V2Phase.BLOCKED;
        if (failurePhase != (blocking != null)) {
            throw new IllegalArgumentException("Blocking details must match a blocking phase");
        }
        if (stale && primaryAction.enabled()) {
            throw new IllegalArgumentException("A stale projection cannot expose an enabled action");
        }
    }
}
