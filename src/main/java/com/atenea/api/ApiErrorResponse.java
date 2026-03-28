package com.atenea.api;

import java.util.List;

public record ApiErrorResponse(
        String message,
        List<String> details,
        String state,
        String reason,
        String action,
        Boolean retryable
) {
    public ApiErrorResponse(String message, List<String> details) {
        this(message, details, null, null, null, null);
    }
}
