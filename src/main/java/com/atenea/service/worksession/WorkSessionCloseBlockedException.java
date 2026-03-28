package com.atenea.service.worksession;

import java.util.List;

public class WorkSessionCloseBlockedException extends RuntimeException {

    private final String state;
    private final String reason;
    private final String action;
    private final boolean retryable;
    private final List<String> details;

    public WorkSessionCloseBlockedException(
            String message,
            String state,
            String reason,
            String action,
            boolean retryable,
            List<String> details
    ) {
        super(message);
        this.state = state;
        this.reason = reason;
        this.action = action;
        this.retryable = retryable;
        this.details = details;
    }

    public String getState() {
        return state;
    }

    public String getReason() {
        return reason;
    }

    public String getAction() {
        return action;
    }

    public boolean isRetryable() {
        return retryable;
    }

    public List<String> getDetails() {
        return details;
    }
}
