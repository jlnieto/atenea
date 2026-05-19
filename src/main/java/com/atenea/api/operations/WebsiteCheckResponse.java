package com.atenea.api.operations;

public record WebsiteCheckResponse(
        Long websiteId,
        String name,
        String url,
        int expectedStatus,
        Integer statusCode,
        long durationMillis,
        int degradedThresholdMillis,
        int timeoutMillis,
        String state,
        boolean healthy,
        String error
) {
}
