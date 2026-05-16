package com.atenea.api.mobile;

public record MobileApiCostLineResponse(
        String label,
        String projectId,
        String currency,
        double amount
) {
}
