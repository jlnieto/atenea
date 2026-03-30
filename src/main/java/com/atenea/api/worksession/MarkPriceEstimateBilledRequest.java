package com.atenea.api.worksession;

import jakarta.validation.constraints.NotBlank;

public record MarkPriceEstimateBilledRequest(
        @NotBlank String billingReference
) {
}
