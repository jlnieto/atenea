package com.atenea.api.core;

import com.atenea.persistence.core.CoreCommandEventPhase;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

public record CoreCommandEventResponse(
        Long id,
        CoreCommandEventPhase phase,
        String message,
        JsonNode payload,
        Instant at
) {
}
