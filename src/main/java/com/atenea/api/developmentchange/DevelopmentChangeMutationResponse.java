package com.atenea.api.developmentchange;

import java.util.UUID;

public record DevelopmentChangeMutationResponse(
        UUID operationId,
        String receiptSha256,
        boolean replayed,
        DevelopmentChangeResponse developmentChange) {
}
