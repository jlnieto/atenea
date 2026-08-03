package com.atenea.api.worksession;

import com.atenea.persistence.worksession.AttachmentKind;
import com.atenea.persistence.worksession.AttachmentRetentionClass;
import com.atenea.persistence.worksession.AttachmentSource;
import com.atenea.persistence.worksession.WorkSessionAttachmentEntity;
import java.time.Instant;
import java.util.UUID;

public record WorkSessionAttachmentResponse(
        UUID id,
        Long workSessionId,
        Long projectId,
        Long agentRunId,
        AttachmentSource source,
        AttachmentKind kind,
        String originalFilename,
        String contentType,
        long sizeBytes,
        AttachmentRetentionClass retentionClass,
        Instant retainUntil,
        String sha256,
        Instant createdAt,
        Instant indexedAt
) {
    public static WorkSessionAttachmentResponse from(WorkSessionAttachmentEntity attachment) {
        return new WorkSessionAttachmentResponse(
                attachment.getId(),
                attachment.getWorkSession().getId(),
                attachment.getProject().getId(),
                attachment.getAgentRun() == null ? null : attachment.getAgentRun().getId(),
                attachment.getSource(),
                attachment.getKind(),
                attachment.getOriginalFilename(),
                attachment.getContentType(),
                attachment.getSizeBytes(),
                attachment.getRetentionClass(),
                attachment.getRetainUntil(),
                attachment.getSha256(),
                attachment.getCreatedAt(),
                attachment.getIndexedAt());
    }
}
