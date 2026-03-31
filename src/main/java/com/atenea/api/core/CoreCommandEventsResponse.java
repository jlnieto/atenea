package com.atenea.api.core;

import java.time.Instant;
import java.util.List;

public record CoreCommandEventsResponse(
        Long commandId,
        List<CoreCommandEventResponse> events,
        Instant generatedAt
) {
}
