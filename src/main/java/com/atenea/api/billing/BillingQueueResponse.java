package com.atenea.api.billing;

import java.util.List;

public record BillingQueueResponse(
        List<BillingQueueItemResponse> items
) {
}
