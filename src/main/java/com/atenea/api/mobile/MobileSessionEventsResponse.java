package com.atenea.api.mobile;

import java.time.Instant;
import java.util.List;

public record MobileSessionEventsResponse(
        Long sessionId,
        List<MobileSessionEventResponse> events,
        Instant generatedAt
) {
}
