package com.atenea.api.mobile;

import java.time.Instant;

public record MobileSessionReadStateResponse(
        Long sessionId,
        Instant lastSeenActivityAt,
        Instant updatedAt
) {
}
