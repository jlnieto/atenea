package com.atenea.api.mobile;

public record MobileApiCostLineResponse(
        String label,
        String projectId,
        String model,
        String currency,
        double amount
) {
}
