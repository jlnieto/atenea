package com.atenea.api.worksession;

import java.util.UUID;

/**
 * Public metadata for one immutable image binding. The download path remains
 * inside the authenticated WorkSession API and deliberately exposes no worker
 * or storage identity.
 */
public record SessionTurnAttachmentResponse(
        UUID id,
        short position,
        String originalFilename,
        String contentType,
        long sizeBytes,
        String sha256,
        String downloadPath
) {
}
