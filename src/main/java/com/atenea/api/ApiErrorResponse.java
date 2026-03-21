package com.atenea.api;

import java.util.List;

public record ApiErrorResponse(
        String message,
        List<String> details
) {
}
