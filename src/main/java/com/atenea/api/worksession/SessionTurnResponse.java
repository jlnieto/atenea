package com.atenea.api.worksession;

import com.atenea.persistence.worksession.SessionTurnActor;
import java.time.Instant;
import java.util.List;

public record SessionTurnResponse(
        Long id,
        SessionTurnActor actor,
        String messageText,
        Instant createdAt,
        TurnExecutionProfileResponse executionProfile,
        List<SessionTurnAttachmentResponse> attachments
) {
    public SessionTurnResponse {
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
    }

    public SessionTurnResponse(
            Long id,
            SessionTurnActor actor,
            String messageText,
            Instant createdAt,
            TurnExecutionProfileResponse executionProfile
    ) {
        this(id, actor, messageText, createdAt, executionProfile, List.of());
    }

    public SessionTurnResponse(Long id, SessionTurnActor actor, String messageText, Instant createdAt) {
        this(id, actor, messageText, createdAt, null, List.of());
    }
}
