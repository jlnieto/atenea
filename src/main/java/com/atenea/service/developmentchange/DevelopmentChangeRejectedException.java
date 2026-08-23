package com.atenea.service.developmentchange;

import com.atenea.api.developmentchange.DevelopmentChangeActionResponse;
import com.atenea.api.developmentchange.DevelopmentChangeFailureResponse;
import com.atenea.v2.control.V2FailureCategory;

public class DevelopmentChangeRejectedException extends RuntimeException {

    private final DevelopmentChangeFailureResponse response;

    public DevelopmentChangeRejectedException(
            V2FailureCategory category,
            String code,
            String message,
            DevelopmentChangeActionResponse action) {
        super(message);
        int status = switch (category) {
            case POLICY -> 403;
            case OWNERSHIP -> 409;
            case VALIDATION -> 422;
            default -> throw new IllegalArgumentException(
                    "Only deterministic local failures can reject a development change request");
        };
        response = new DevelopmentChangeFailureResponse(
                status, category, code, message, false, action);
    }

    public DevelopmentChangeFailureResponse response() {
        return response;
    }
}
