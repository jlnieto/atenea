package com.atenea.api.worksession;

import com.atenea.persistence.worksession.SessionDeliverableType;
import java.util.List;

public record SessionDeliverableHistoryResponse(
        Long sessionId,
        SessionDeliverableType type,
        Long latestGeneratedDeliverableId,
        Long latestApprovedDeliverableId,
        List<SessionDeliverableSummaryResponse> versions
) {
}
