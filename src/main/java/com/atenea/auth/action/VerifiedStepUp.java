package com.atenea.auth.action;

import java.time.Instant;
import java.util.UUID;

public record VerifiedStepUp(
        Long operatorId,
        UUID sessionFamilyId,
        PrivilegedActionBinding binding,
        PrivilegedActionFactor factor,
        Instant authenticatedAt
) {
}
