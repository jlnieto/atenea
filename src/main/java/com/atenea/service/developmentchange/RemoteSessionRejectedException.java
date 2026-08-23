package com.atenea.service.developmentchange;

import com.atenea.api.developmentchange.RemoteSessionFailureResponse;
import com.atenea.api.developmentchange.RemoteSessionNextAction;
import com.atenea.api.developmentchange.RemoteSessionRejectionClass;

public class RemoteSessionRejectedException extends RuntimeException {

    private final RemoteSessionFailureResponse response;

    public RemoteSessionRejectedException(
            RemoteSessionRejectionClass rejectionClass,
            String code,
            String message,
            RemoteSessionNextAction nextAction) {
        super(message);
        int status = switch (rejectionClass) {
            case POLICY -> 403;
            case OWNERSHIP, CAPACITY -> 409;
            case VALIDATION, UNSUPPORTED -> 422;
        };
        response = new RemoteSessionFailureResponse(
                status, rejectionClass, code, message, false, nextAction,
                null, null, null, null, false, null, null, null, null, null);
    }

    public RemoteSessionRejectedException(RemoteSessionFailureResponse response) {
        super(response.message());
        this.response = response;
    }

    public RemoteSessionFailureResponse response() {
        return response;
    }
}
