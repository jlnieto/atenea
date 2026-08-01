package com.atenea.attachments;

import com.atenea.persistence.worksession.AttachmentKind;
import com.atenea.persistence.worksession.AttachmentRetentionClass;
import com.atenea.persistence.worksession.AttachmentSource;
import com.atenea.persistence.worksession.ExecutionTarget;
import com.atenea.persistence.worksession.WorkSessionAttachmentEntity;
import com.atenea.persistence.worksession.WorkSessionEntity;
import com.atenea.persistence.worksession.WorkSessionRepository;
import com.atenea.service.worksession.AttachmentIndexRequest;
import com.atenea.service.worksession.AttachmentLimitException;
import com.atenea.service.worksession.AttachmentOwnershipException;
import com.atenea.service.worksession.WorkSessionAttachmentMetadataService;
import com.atenea.service.worksession.WorkSessionNotFoundException;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class WorkSessionAttachmentService {

    private static final Set<String> IMAGE_CONTENT_TYPES = Set.of(
            "image/png",
            "image/jpeg",
            "image/webp");

    private final AttachmentProperties properties;
    private final AttachmentAdmissionPolicy admissionPolicy;
    private final AttachmentWorkerClient workerClient;
    private final WorkSessionRepository workSessionRepository;
    private final WorkSessionAttachmentMetadataService metadataService;

    public WorkSessionAttachmentService(
            AttachmentProperties properties,
            AttachmentAdmissionPolicy admissionPolicy,
            AttachmentWorkerClient workerClient,
            WorkSessionRepository workSessionRepository,
            WorkSessionAttachmentMetadataService metadataService
    ) {
        this.properties = properties;
        this.admissionPolicy = admissionPolicy;
        this.workerClient = workerClient;
        this.workSessionRepository = workSessionRepository;
        this.metadataService = metadataService;
    }

    public WorkSessionAttachmentEntity upload(
            Long workSessionId,
            UUID idempotencyKey,
            Long agentRunId,
            AttachmentSource claimedSource,
            AttachmentKind claimedKind,
            AttachmentRetentionClass claimedRetentionClass,
            MultipartFile file
    ) {
        WorkSessionEntity session = requireCreateAllowed(workSessionId);
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Selecciona un fichero no vacío.");
        }
        if (file.getSize() > properties.getMaxFileBytes()) {
            throw new AttachmentLimitException("El adjunto supera el límite de 16 MiB.");
        }
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException exception) {
            throw new AttachmentWorkerException("No se pudo leer el adjunto seleccionado.", exception);
        }
        String contentType = normalizedContentType(file.getContentType());
        AttachmentSource source = AttachmentSource.OPERATOR_UPLOAD;
        AttachmentKind kind = IMAGE_CONTENT_TYPES.contains(contentType)
                ? AttachmentKind.IMAGE
                : AttachmentKind.FILE;
        AttachmentRetentionClass retentionClass = AttachmentRetentionClass.SESSION;
        requireDerivedClassification(
                claimedSource,
                claimedKind,
                claimedRetentionClass,
                source,
                kind,
                retentionClass);
        String sha256 = sha256(content);
        // PostgreSQL stores TIMESTAMPTZ at microsecond precision. Normalize
        // before worker retention so an idempotent read-back remains identical.
        Instant createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        UUID attachmentId = idempotencyKey == null ? UUID.randomUUID() : idempotencyKey;

        AttachmentWorkerClient.Health health = workerClient.health();
        requireCompatibleWorker(session, health);
        AttachmentWorkerClient.PutResult stored = workerClient.put(
                workSessionId,
                attachmentId,
                source,
                kind,
                retentionClass,
                contentType,
                sha256,
                createdAt,
                content);
        validateStored(session, attachmentId, sha256, content.length, stored.attachment());
        validateStoredClassification(
                source,
                kind,
                retentionClass,
                contentType,
                stored.attachment());

        try {
            return metadataService.index(
                    workSessionId,
                    new AttachmentIndexRequest(
                            attachmentId,
                            agentRunId,
                            source,
                            kind,
                            file.getOriginalFilename(),
                            contentType,
                            content.length,
                            retentionClass,
                            sha256,
                            stored.attachment().workerId(),
                            stored.attachment().storageIdentity(),
                            stored.attachment().createdAt()));
        } catch (RuntimeException indexingFailure) {
            if (stored.created()) {
                try {
                    workerClient.deleteSynthetic(workSessionId, attachmentId);
                } catch (RuntimeException cleanupFailure) {
                    indexingFailure.addSuppressed(cleanupFailure);
                }
            }
            throw indexingFailure;
        }
    }

    public List<WorkSessionAttachmentEntity> list(Long workSessionId, int limit) {
        return metadataService.list(workSessionId, limit);
    }

    public List<WorkSessionAttachmentEntity> screenshots(
            Long workSessionId,
            AttachmentSource source,
            int offset,
            int limit
    ) {
        return metadataService.screenshots(workSessionId, source, offset, limit);
    }

    public WorkSessionAttachmentEntity get(Long workSessionId, UUID attachmentId) {
        return metadataService.get(workSessionId, attachmentId);
    }

    public Download download(Long workSessionId, UUID attachmentId) {
        WorkSessionAttachmentEntity metadata = metadataService.get(workSessionId, attachmentId);
        AttachmentWorkerClient.Content content = workerClient.content(workSessionId, attachmentId);
        String actualSha256 = sha256(content.bytes());
        if (content.bytes().length != metadata.getSizeBytes()
                || !Objects.equals(actualSha256, metadata.getSha256())
                || !Objects.equals(normalizedContentType(content.contentType()), metadata.getContentType())) {
            throw new AttachmentWorkerException(
                    "El contenido retenido no coincide con su identidad indexada.",
                    409,
                    "retained_integrity_conflict");
        }
        return new Download(metadata, content.bytes());
    }

    private WorkSessionEntity requireCreateAllowed(Long workSessionId) {
        WorkSessionEntity session = workSessionRepository.findWithProjectById(workSessionId)
                .orElseThrow(() -> new WorkSessionNotFoundException(workSessionId));
        admissionPolicy.requireSyntheticCreationAllowed(session.getProject().getName());
        if (session.getExecutionTarget() != ExecutionTarget.REMOTE
                || !Objects.equals(session.getSelectedWorkerId(), properties.getWorkerId())) {
            throw new AttachmentOwnershipException(
                    "La WorkSession no tiene afinidad persistida con el worker de adjuntos.");
        }
        return session;
    }

    private void requireCompatibleWorker(
            WorkSessionEntity session,
            AttachmentWorkerClient.Health health
    ) {
        if (!health.healthy()
                || !AttachmentProperties.PROTOCOL.equals(health.protocolVersion())
                || !Objects.equals(session.getSelectedWorkerId(), health.workerId())
                || health.maxFileBytes() != properties.getMaxFileBytes()
                || health.maxSessionBytes() != properties.getMaxSessionBytes()) {
            throw new AttachmentWorkerException(
                    "AX42 no anuncia un contrato de adjuntos compatible.",
                    503,
                    "incompatible_attachment_worker");
        }
    }

    private void validateStored(
            WorkSessionEntity session,
            UUID attachmentId,
            String sha256,
            int size,
            AttachmentWorkerClient.StoredAttachment stored
    ) {
        if (!AttachmentProperties.PROTOCOL.equals(stored.protocolVersion())
                || !Objects.equals(session.getSelectedWorkerId(), stored.workerId())
                || !Objects.equals(session.getId().toString(), stored.sessionId())
                || !Objects.equals(attachmentId, stored.attachmentId())
                || !Objects.equals(sha256, stored.sha256())
                || stored.sizeBytes() != size
                || !stored.syntheticFixture()) {
            throw new AttachmentWorkerException(
                    "AX42 devolvió una identidad de adjunto distinta de la solicitada.",
                    409,
                    "attachment_response_identity_conflict");
        }
    }

    private void validateStoredClassification(
            AttachmentSource source,
            AttachmentKind kind,
            AttachmentRetentionClass retentionClass,
            String contentType,
            AttachmentWorkerClient.StoredAttachment stored
    ) {
        if (stored.source() != source
                || stored.kind() != kind
                || stored.retentionClass() != retentionClass
                || !Objects.equals(stored.contentType(), contentType)
                || stored.createdAt() == null
                || stored.storedAt() == null
                || stored.storageIdentity() == null
                || stored.storageIdentity().isBlank()) {
            throw new AttachmentWorkerException(
                    "AX42 devolvió una clasificación de adjunto distinta de la solicitada.",
                    409,
                    "attachment_response_classification_conflict");
        }
    }

    private void requireDerivedClassification(
            AttachmentSource claimedSource,
            AttachmentKind claimedKind,
            AttachmentRetentionClass claimedRetentionClass,
            AttachmentSource derivedSource,
            AttachmentKind derivedKind,
            AttachmentRetentionClass derivedRetentionClass
    ) {
        boolean sourceConflict = claimedSource != null && claimedSource != derivedSource;
        boolean kindConflict = claimedKind != null && claimedKind != derivedKind;
        boolean retentionConflict = claimedRetentionClass != null
                && claimedRetentionClass != derivedRetentionClass;
        if (sourceConflict || kindConflict || retentionConflict) {
            throw new IllegalArgumentException(
                    "La clasificación del adjunto la determina Atenea y no puede solicitar autoridad privilegiada.");
        }
    }

    private String normalizedContentType(String value) {
        if (value == null || value.isBlank()) {
            return "application/octet-stream";
        }
        int separator = value.indexOf(';');
        return (separator >= 0 ? value.substring(0, separator) : value).trim().toLowerCase();
    }

    private String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public record Download(WorkSessionAttachmentEntity metadata, byte[] content) {
    }
}
