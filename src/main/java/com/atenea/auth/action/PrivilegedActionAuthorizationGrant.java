package com.atenea.auth.action;

import java.time.Instant;
import java.util.UUID;

public record PrivilegedActionAuthorizationGrant(UUID authorization, Instant expiresAt) {
    @Override public String toString() {
        return "PrivilegedActionAuthorizationGrant[REDACTED]";
    }
}
