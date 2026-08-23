package com.atenea.auth;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AuthenticatedSession(
        AuthenticatedOperator operator,
        UUID sessionFamilyId,
        Instant authenticatedAt,
        List<String> authenticationMethods
) {
}
