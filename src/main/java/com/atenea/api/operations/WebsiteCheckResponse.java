package com.atenea.api.operations;

public record WebsiteCheckResponse(
        Long websiteId,
        String name,
        String url,
        int expectedStatus,
        Integer statusCode,
        long durationMillis,
        boolean healthy,
        String error
) {
}
