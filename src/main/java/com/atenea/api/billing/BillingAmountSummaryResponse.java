package com.atenea.api.billing;

public record BillingAmountSummaryResponse(
        String currency,
        double total
) {
}
