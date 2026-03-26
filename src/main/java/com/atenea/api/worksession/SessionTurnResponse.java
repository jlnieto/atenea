package com.atenea.api.worksession;

import com.atenea.persistence.worksession.SessionTurnActor;
import java.time.Instant;

public record SessionTurnResponse(
        Long id,
        SessionTurnActor actor,
        String messageText,
        Instant createdAt
) {
}
