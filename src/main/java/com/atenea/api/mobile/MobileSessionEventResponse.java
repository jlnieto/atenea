package com.atenea.api.mobile;

import java.time.Instant;

public record MobileSessionEventResponse(
        String type,
        Instant at,
        String title,
        String details,
        Long runId,
        Long turnId,
        Long deliverableId
) {
}
