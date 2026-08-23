package com.atenea.api.v2.control;

import com.atenea.v2.control.V2FailureCategory;

public record V2DeterministicFailureResponse(
        int status,
        V2FailureCategory failureCategory,
        String failureCode,
        String message,
        boolean retryable,
        V2ControlProjectionResponse projection) {

    public V2DeterministicFailureResponse {
        if (failureCategory != V2FailureCategory.POLICY
                && failureCategory != V2FailureCategory.OWNERSHIP) {
            throw new IllegalArgumentException("Only deterministic policy or ownership failures are supported");
        }
        int expectedStatus = failureCategory == V2FailureCategory.POLICY ? 403 : 409;
        if (status != expectedStatus || retryable) {
            throw new IllegalArgumentException("Deterministic failures have a fixed status and are never retryable");
        }
        if (projection == null || projection.blocking() == null
                || projection.blocking().category() != failureCategory
                || !projection.blocking().code().equals(failureCode)
                || !projection.blocking().message().equals(message)) {
            throw new IllegalArgumentException("Failure and projection must describe the same blocking fact");
        }
    }
}
