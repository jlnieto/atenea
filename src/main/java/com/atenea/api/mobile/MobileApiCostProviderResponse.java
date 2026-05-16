package com.atenea.api.mobile;

import java.util.List;

public record MobileApiCostProviderResponse(
        String provider,
        boolean configured,
        String status,
        String currency,
        double total,
        List<MobileApiCostLineResponse> lines
) {
}
