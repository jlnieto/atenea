package com.atenea.api.worksession;

import com.atenea.persistence.worksession.SessionDeliverableStatus;
import com.atenea.persistence.worksession.SessionDeliverableType;
import java.time.Instant;

public record SessionDeliverableResponse(
        Long id,
        Long sessionId,
        SessionDeliverableType type,
        SessionDeliverableStatus status,
        int version,
        String title,
        String contentMarkdown,
        String contentJson,
        String inputSnapshotJson,
        String generationNotes,
        String errorMessage,
        String model,
        String promptVersion,
        boolean approved,
        Instant approvedAt,
        Instant createdAt,
        Instant updatedAt
) {
}
