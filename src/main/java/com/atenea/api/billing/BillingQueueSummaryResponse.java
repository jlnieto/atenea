package com.atenea.api.billing;

import java.util.List;

public record BillingQueueSummaryResponse(
        long readyCount,
        long billedCount,
        List<BillingAmountSummaryResponse> readyAmounts,
        List<BillingAmountSummaryResponse> billedAmounts
) {
}
