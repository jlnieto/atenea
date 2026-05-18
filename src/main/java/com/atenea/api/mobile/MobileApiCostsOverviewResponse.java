package com.atenea.api.mobile;

import java.time.Instant;
import java.util.List;

public record MobileApiCostsOverviewResponse(
        Instant generatedAt,
        Instant startAt,
        Instant endAt,
        String currency,
        double total,
        List<MobileApiCostProviderResponse> providers,
        List<MobileApiUsageSummaryResponse> usageSummaries,
        List<MobileCodexAuthStatusResponse> codexAuthStatuses
) {
}
