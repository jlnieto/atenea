package com.atenea.service.worksession;

import com.atenea.persistence.worksession.AttachmentKind;
import com.atenea.persistence.worksession.AttachmentRetentionClass;
import com.atenea.persistence.worksession.AttachmentSource;
import java.time.Instant;
import java.util.UUID;

public record AttachmentIndexRequest(
        UUID attachmentId,
        Long agentRunId,
        AttachmentSource source,
        AttachmentKind kind,
        String originalFilename,
        String contentType,
        long sizeBytes,
        AttachmentRetentionClass retentionClass,
        String sha256,
        String workerId,
        String storageIdentity,
        Instant createdAt
) {
}
