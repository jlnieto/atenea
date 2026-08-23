package com.atenea.service.v2control;

import com.atenea.v2.control.V2FailureCategory;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;

public record V2AuditFact(
        UUID operationId,
        Long projectId,
        Long actorId,
        String capability,
        String eventType,
        String state,
        long revision,
        String requestFingerprintSha256,
        String targetFingerprintSha256,
        V2FailureCategory failureCategory,
        String failureCode,
        int itemCount,
        long durationMillis,
        Instant occurredAt) {

    private static final Pattern CAPABILITY = Pattern.compile("^[a-z][a-z0-9-]{2,79}$");
    private static final Pattern SYMBOL = Pattern.compile("^[A-Z][A-Z0-9_]{2,79}$");
    private static final Pattern STATE = Pattern.compile("^[A-Z][A-Z0-9_]{1,39}$");
    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");

    public V2AuditFact {
        if (operationId == null || projectId == null || actorId == null || occurredAt == null) {
            throw new IllegalArgumentException("Audit identities and occurrence time are required");
        }
        if (!CAPABILITY.matcher(capability == null ? "" : capability).matches()) {
            throw new IllegalArgumentException("Capability must be a server-owned symbolic identifier");
        }
        if (!SYMBOL.matcher(eventType == null ? "" : eventType).matches()) {
            throw new IllegalArgumentException("Event type must be a closed symbolic identifier");
        }
        if (!STATE.matcher(state == null ? "" : state).matches()) {
            throw new IllegalArgumentException("State must be a closed symbolic identifier");
        }
        if (revision < 0 || itemCount < 0 || durationMillis < 0) {
            throw new IllegalArgumentException("Audit revision, counts and duration must be non-negative");
        }
        if (!SHA256.matcher(requestFingerprintSha256 == null ? "" : requestFingerprintSha256).matches()
                || !SHA256.matcher(targetFingerprintSha256 == null ? "" : targetFingerprintSha256).matches()) {
            throw new IllegalArgumentException("Audit fingerprints must be lowercase SHA-256 digests");
        }
        if ((failureCategory == null) != (failureCode == null)) {
            throw new IllegalArgumentException("Failure category and code must be present together");
        }
        if (failureCode != null && !SYMBOL.matcher(failureCode).matches()) {
            throw new IllegalArgumentException("Failure code must be a closed symbolic identifier");
        }
    }
}
