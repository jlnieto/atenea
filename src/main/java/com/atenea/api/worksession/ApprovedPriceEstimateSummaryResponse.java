package com.atenea.api.worksession;

import com.atenea.persistence.worksession.SessionDeliverableBillingStatus;
import java.time.Instant;
import java.util.List;

public record ApprovedPriceEstimateSummaryResponse(
        Long sessionId,
        Long deliverableId,
        int version,
        String title,
        String currency,
        double baseHourlyRate,
        double equivalentHours,
        double minimumPrice,
        double recommendedPrice,
        double maximumPrice,
        String commercialPositioning,
        String riskLevel,
        String confidence,
        List<String> assumptions,
        List<String> exclusions,
        SessionDeliverableBillingStatus billingStatus,
        String billingReference,
        Instant billedAt,
        Instant approvedAt,
        Instant updatedAt
) {
}
