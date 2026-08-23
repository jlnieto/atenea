package com.atenea.api.developmentchange;

import com.atenea.v2.control.V2FailureCategory;

public record DevelopmentChangeFailureResponse(
        int status,
        V2FailureCategory failureCategory,
        String failureCode,
        String message,
        boolean retryable,
        DevelopmentChangeActionResponse nextAction) {
}
