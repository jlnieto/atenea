package com.atenea.delivery;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public record DeliveryOperation(UUID id, Long sessionId, Long operatorId, String kind,
        DeliveryTarget target, String sourceCommit, UUID executionId, String state,
        String planSha256, JsonNode evidence, String errorCode, Instant createdAt, Instant updatedAt) {
    static final Set<String> TERMINAL = Set.of("SUCCEEDED", "ROLLED_BACK", "FAILED", "BLOCKED", "ROLLBACK_FAILED");
    public boolean terminal() { return TERMINAL.contains(state); }
    public DeliveryView view() {
        return new DeliveryView(id, executionId, sessionId, kind, target, sourceCommit, state,
                planSha256, errorCode, evidence.path("versionCode").isIntegralNumber()
                    ? evidence.path("versionCode").asLong() : null,
                evidence.path("versionName").isTextual() ? evidence.path("versionName").asText() : null,
                evidence.path("expiresAt").isIntegralNumber() ? evidence.path("expiresAt").asLong() : null,
                evidence.path("pullRequestUrl").isTextual() ? evidence.path("pullRequestUrl").asText() : null,
                evidence.path("mergeCommit").isTextual() ? evidence.path("mergeCommit").asText() : null,
                evidence.path("effectiveSourceCommit").isTextual() ? evidence.path("effectiveSourceCommit").asText() : null,
                evidence.path("resultSha256").isTextual() ? evidence.path("resultSha256").asText() : null,
                createdAt, updatedAt);
    }
    public record DeliveryView(UUID id, UUID operationId, Long sessionId, String kind,
            DeliveryTarget target, String sourceCommit, String state, String planSha256, String errorCode,
            Long versionCode, String versionName, Long expiresAt, String pullRequestUrl,
            String mergeCommit, String effectiveSourceCommit, String resultSha256, Instant createdAt, Instant updatedAt) { }
}
