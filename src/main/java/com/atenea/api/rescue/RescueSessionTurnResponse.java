package com.atenea.api.rescue;

import com.atenea.persistence.rescue.RescueSessionTurnActor;
import java.time.Instant;

public record RescueSessionTurnResponse(
        Long id,
        RescueSessionTurnActor actor,
        String messageText,
        String externalTurnId,
        Instant createdAt
) {
}
