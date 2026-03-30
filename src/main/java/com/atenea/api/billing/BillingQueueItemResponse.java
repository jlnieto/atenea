package com.atenea.api.billing;

import com.atenea.persistence.worksession.SessionDeliverableBillingStatus;
import com.atenea.persistence.worksession.WorkSessionPullRequestStatus;
import java.time.Instant;

public record BillingQueueItemResponse(
        Long projectId,
        String projectName,
        Long sessionId,
        String sessionTitle,
        Long deliverableId,
        int version,
        SessionDeliverableBillingStatus billingStatus,
        String billingReference,
        Instant billedAt,
        String currency,
        double recommendedPrice,
        double minimumPrice,
        double maximumPrice,
        Instant approvedAt,
        Instant publishedAt,
        String pullRequestUrl,
        WorkSessionPullRequestStatus pullRequestStatus
) {
}
