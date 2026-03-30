package com.atenea.api.mobile;

import java.time.Instant;

public record MobileInboxItemResponse(
        String type,
        String severity,
        String title,
        String message,
        String action,
        Long projectId,
        String projectName,
        Long sessionId,
        String sessionTitle,
        Instant updatedAt
) {
}
