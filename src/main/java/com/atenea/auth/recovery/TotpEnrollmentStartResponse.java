package com.atenea.auth.recovery;

import java.time.Instant;
import java.util.UUID;

public record TotpEnrollmentStartResponse(
        UUID enrollmentId,
        String secret,
        String algorithm,
        int digits,
        int periodSeconds,
        Instant expiresAt
) {
    @Override
    public String toString() {
        return "TotpEnrollmentStartResponse[REDACTED]";
    }
}
