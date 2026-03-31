package com.atenea.service.core;

public record CoreExecutionContext(
        Long commandId,
        Long projectId,
        Long workSessionId,
        String operatorKey,
        boolean confirmed,
        String confirmationToken
) {
}
