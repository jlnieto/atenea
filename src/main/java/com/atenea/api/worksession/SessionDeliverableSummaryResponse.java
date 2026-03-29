package com.atenea.api.worksession;

import com.atenea.persistence.worksession.SessionDeliverableStatus;
import com.atenea.persistence.worksession.SessionDeliverableType;
import java.time.Instant;

public record SessionDeliverableSummaryResponse(
        Long id,
        SessionDeliverableType type,
        SessionDeliverableStatus status,
        int version,
        String title,
        boolean approved,
        Instant approvedAt,
        Instant updatedAt,
        String preview,
        Long latestApprovedDeliverableId
) {
}
