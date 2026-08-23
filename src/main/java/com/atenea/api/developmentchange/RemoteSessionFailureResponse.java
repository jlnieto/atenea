package com.atenea.api.developmentchange;

import com.atenea.persistence.developmentchange.RemoteSessionOperationState;
import java.util.UUID;

public record RemoteSessionFailureResponse(
        int status,
        RemoteSessionRejectionClass rejectionClass,
        String failureCode,
        String message,
        boolean retryable,
        RemoteSessionNextAction nextAction,
        UUID operationId,
        RemoteSessionOperationState state,
        Long revision,
        String receiptSha256,
        boolean replayed,
        UUID changeKey,
        Long sessionId,
        UUID remoteSessionId,
        String sourceFingerprintSha256,
        String ownershipFingerprintSha256) {
}
