package com.atenea.api.core;

public record CoreRequestContext(
        Long projectId,
        Long workSessionId,
        String operatorKey,
        String scope
) {
    public CoreRequestContext(Long projectId, Long workSessionId) {
        this(projectId, workSessionId, null, null);
    }

    public CoreRequestContext(Long projectId, Long workSessionId, String operatorKey) {
        this(projectId, workSessionId, operatorKey, null);
    }

    public boolean isGlobalScope() {
        return "GLOBAL".equalsIgnoreCase(scope);
    }
}
