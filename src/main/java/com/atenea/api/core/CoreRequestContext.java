package com.atenea.api.core;

public record CoreRequestContext(
        Long projectId,
        Long workSessionId,
        String operatorKey
) {
    public CoreRequestContext(Long projectId, Long workSessionId) {
        this(projectId, workSessionId, null);
    }
}
