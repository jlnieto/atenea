package com.atenea.api.v2.control;

import com.atenea.v2.control.V2FailureCategory;
import java.util.UUID;

public record V2OperationEnvelopeResponse(
        UUID operationId,
        String idempotencyKey,
        String requestFingerprintSha256,
        String targetFingerprintSha256,
        long revision,
        boolean terminal,
        V2FailureCategory failureCategory,
        String failureCode,
        String receiptSha256) {
}
