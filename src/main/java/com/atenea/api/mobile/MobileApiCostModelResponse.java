package com.atenea.api.mobile;

public record MobileApiCostModelResponse(
        String provider,
        String model,
        String currency,
        double amount
) {
}
