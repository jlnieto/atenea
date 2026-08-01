package com.atenea.service.worksession;

import com.atenea.persistence.worksession.AttachmentKind;
import com.atenea.persistence.worksession.AttachmentRetentionClass;
import com.atenea.persistence.worksession.AttachmentSource;
import com.atenea.persistence.worksession.AttachmentStorageScope;
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
        AttachmentStorageScope storageScope,
        UUID remoteSessionId,
        String workspaceIdentity,
        Instant createdAt
) {
}
