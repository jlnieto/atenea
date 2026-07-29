package com.atenea.service.worksession;

import java.time.Instant;
import java.util.UUID;

public record PreviewIndexRequest(
        UUID previewId,
        Long agentRunId,
        String workerId,
        String allocationIdentity,
        String allocationFingerprint,
        boolean localhostCompatible,
        Instant createdAt
) {
}
