package com.atenea.api.mobile;

import com.atenea.api.worksession.ApprovedPriceEstimateSummaryResponse;
import com.atenea.api.worksession.SessionDeliverablesViewResponse;
import com.atenea.api.worksession.WorkSessionConversationViewResponse;

public record MobileSessionSummaryResponse(
        WorkSessionConversationViewResponse conversation,
        SessionDeliverablesViewResponse approvedDeliverables,
        ApprovedPriceEstimateSummaryResponse approvedPriceEstimate,
        MobileSessionActionsResponse actions,
        MobileSessionInsightsResponse insights
) {
}
