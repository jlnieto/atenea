package com.atenea.api.mobile;

import java.time.Instant;
import java.util.List;

public record MobileApiCostsOverviewResponse(
        Instant generatedAt,
        Instant startAt,
        Instant endAt,
        List<MobileApiCostProviderResponse> providers
) {
}
