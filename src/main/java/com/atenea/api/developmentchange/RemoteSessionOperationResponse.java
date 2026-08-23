package com.atenea.api.developmentchange;

import com.atenea.persistence.developmentchange.RemoteSessionOperationState;
import com.atenea.persistence.worksession.WorkSessionStatus;
import java.util.UUID;

public record RemoteSessionOperationResponse(
        UUID operationId,
        RemoteSessionOperationState state,
        long revision,
        String receiptSha256,
        boolean replayed,
        RemoteSessionResolution resolution,
        UUID changeKey,
        Long sessionId,
        UUID remoteSessionId,
        String sourceFingerprintSha256,
        String ownershipFingerprintSha256,
        long changeRevision,
        WorkSessionStatus sessionState,
        RemoteSessionNextAction nextAction) {
}
