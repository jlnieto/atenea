package com.atenea.api.worksession;

import java.time.Instant;
import java.util.List;

public record SessionDeliverablesViewResponse(
        Long sessionId,
        List<SessionDeliverableSummaryResponse> deliverables,
        boolean allCoreDeliverablesPresent,
        boolean allCoreDeliverablesApproved,
        Instant lastGeneratedAt
) {
}
