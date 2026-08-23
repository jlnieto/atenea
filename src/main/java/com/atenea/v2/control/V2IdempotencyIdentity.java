package com.atenea.v2.control;

import java.util.Objects;
import java.util.regex.Pattern;

public record V2IdempotencyIdentity(
        String idempotencyKey,
        String requestFingerprintSha256,
        String targetFingerprintSha256) {

    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");

    public V2IdempotencyIdentity {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Idempotency key is required");
        }
        requireSha256(requestFingerprintSha256, "Request fingerprint");
        requireSha256(targetFingerprintSha256, "Target fingerprint");
    }

    public boolean sameRequestAs(V2IdempotencyIdentity candidate) {
        return Objects.equals(this, candidate);
    }

    private static void requireSha256(String value, String label) {
        if (value == null || !SHA256.matcher(value).matches()) {
            throw new IllegalArgumentException(label + " must be a lowercase SHA-256 digest");
        }
    }
}
