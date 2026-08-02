package com.atenea.service.worksession;

import com.atenea.attachments.AttachmentAdmissionPolicy;
import com.atenea.attachments.AttachmentProperties;
import com.atenea.attachments.AttachmentWorkerClient;
import com.atenea.attachments.AttachmentWorkerException;
import com.atenea.attachments.RealAttachmentProjectRegistry;
import com.atenea.persistence.worksession.AttachmentKind;
import com.atenea.persistence.worksession.AttachmentRetentionClass;
import com.atenea.persistence.worksession.AttachmentSource;
import com.atenea.persistence.worksession.AttachmentStorageScope;
import com.atenea.persistence.worksession.ExecutionTarget;
import com.atenea.persistence.worksession.WorkSessionAttachmentEntity;
import com.atenea.persistence.worksession.WorkSessionAttachmentRepository;
import com.atenea.persistence.worksession.WorkSessionEntity;
import com.atenea.remoteworker.ProjectCodexIdentity;
import java.time.Instant;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TurnAttachmentSelectionValidator {

    private static final Pattern LOWERCASE_SHA256 = Pattern.compile("^[0-9a-f]{64}$");

    private final AttachmentProperties properties;
    private final AttachmentAdmissionPolicy admissionPolicy;
    private final AttachmentWorkerClient workerClient;
    private final WorkSessionAttachmentRepository attachmentRepository;
    private final TurnAttachmentFingerprintService fingerprintService;

    public TurnAttachmentSelectionValidator(
            AttachmentProperties properties,
            AttachmentAdmissionPolicy admissionPolicy,
            AttachmentWorkerClient workerClient,
            WorkSessionAttachmentRepository attachmentRepository,
            TurnAttachmentFingerprintService fingerprintService
    ) {
        this.properties = properties;
        this.admissionPolicy = admissionPolicy;
        this.workerClient = workerClient;
        this.attachmentRepository = attachmentRepository;
        this.fingerprintService = fingerprintService;
    }

    @Transactional(readOnly = true)
    public ValidatedSelection validate(
            WorkSessionEntity session,
            List<UUID> attachmentIds
    ) {
        requireExactEligibleSession(session);
        List<UUID> orderedIds = requireBoundedDistinctIds(attachmentIds);
        Instant now = Instant.now();
        List<WorkSessionAttachmentEntity> attachments = new ArrayList<>(orderedIds.size());
        long totalBytes = 0L;

        for (UUID attachmentId : orderedIds) {
            WorkSessionAttachmentEntity attachment = attachmentRepository
                    .findByIdAndWorkSessionId(attachmentId, session.getId())
                    .orElseThrow(() -> new AttachmentOwnershipException(
                            "Una imagen no pertenece a esta WorkSession."));
            requireExactIndexedOwnership(session, attachment, now);
            if (totalBytes > AttachmentProperties.DEFAULT_MAX_ATTACHMENT_BYTES_PER_TURN
                    - attachment.getSizeBytes()) {
                throw new AttachmentLimitException(
                        "Las imágenes del turno superan el límite combinado de 32 MiB.");
            }
            totalBytes += attachment.getSizeBytes();
            attachments.add(attachment);
        }

        requireCompatibleWorker(session, workerClient.health());
        requireCompatibleRealCapability(session, workerClient.realProjectCapability());

        List<ValidatedAttachment> validated = new ArrayList<>(attachments.size());
        for (WorkSessionAttachmentEntity attachment : attachments) {
            AttachmentWorkerClient.StoredAttachment retained = workerClient.metadata(
                    session.getRemoteSessionId(),
                    attachment.getId());
            requireExactRetainedIdentity(session, attachment, retained);
            requireExactRetainedContent(
                    attachment,
                    workerClient.content(session.getRemoteSessionId(), attachment.getId()));
            validated.add(new ValidatedAttachment(
                    attachment.getId(),
                    attachment.getContentType(),
                    attachment.getSizeBytes(),
                    attachment.getSha256()));
        }

        List<TurnAttachmentFingerprintService.AttachmentFingerprintInput> fingerprintInputs =
                validated.stream()
                        .map(attachment -> new TurnAttachmentFingerprintService.AttachmentFingerprintInput(
                                attachment.id(),
                                attachment.contentType(),
                                attachment.sizeBytes(),
                                attachment.sha256()))
                        .toList();
        return new ValidatedSelection(
                List.copyOf(validated),
                totalBytes,
                fingerprintService.attachmentManifestSha256(fingerprintInputs));
    }

    private void requireExactEligibleSession(WorkSessionEntity session) {
        if (session == null || session.getId() == null || session.getProject() == null) {
            throw new AttachmentOwnershipException(
                    "La WorkSession no tiene ownership real completo para adjuntos.");
        }
        admissionPolicy.requireRealCreateBindAllowed(ProjectCodexIdentity.PROJECT_IDENTITY);
        String expectedWorkspace = "remote:" + RealAttachmentProjectRegistry.ATENEA_WORKER_ID
                + ":work-session:" + session.getRemoteSessionId();
        if (!RealAttachmentProjectRegistry.ATENEA_POLICY_REVISION.equals(
                    session.getAttachmentPolicyRevision())
                || !ProjectCodexIdentity.matches(session)
                || session.getExecutionTarget() != ExecutionTarget.REMOTE
                || !RealAttachmentProjectRegistry.ATENEA_WORKER_ID.equals(
                    session.getSelectedWorkerId())
                || session.getRemoteSessionId() == null
                || !ProjectCodexIdentity.WORKLOAD_KIND.equals(session.getRemoteWorkloadKind())
                || !Objects.equals(expectedWorkspace, session.getWorkspaceIdentity())) {
            throw new AttachmentOwnershipException(
                    "La WorkSession no tiene ownership real completo para adjuntos.");
        }
    }

    private List<UUID> requireBoundedDistinctIds(List<UUID> attachmentIds) {
        if (attachmentIds == null || attachmentIds.isEmpty()) {
            throw new IllegalArgumentException("Selecciona entre una y cuatro imágenes.");
        }
        if (attachmentIds.size() > AttachmentProperties.DEFAULT_MAX_ATTACHMENTS_PER_TURN) {
            throw new AttachmentLimitException("Solo puedes adjuntar hasta cuatro imágenes por turno.");
        }
        Set<UUID> distinct = new HashSet<>();
        for (UUID attachmentId : attachmentIds) {
            if (attachmentId == null) {
                throw new AttachmentOwnershipException("La identidad de una imagen es inválida.");
            }
            if (!distinct.add(attachmentId)) {
                throw new AttachmentConflictException("No puedes adjuntar la misma imagen dos veces.");
            }
        }
        return List.copyOf(attachmentIds);
    }

    private void requireExactIndexedOwnership(
            WorkSessionEntity session,
            WorkSessionAttachmentEntity attachment,
            Instant now
    ) {
        String expectedWorkspace = session.getWorkspaceIdentity();
        boolean exactOwnership = attachment.getWorkSession() != null
                && Objects.equals(session.getId(), attachment.getWorkSession().getId())
                && attachment.getProject() != null
                && Objects.equals(session.getProject().getId(), attachment.getProject().getId())
                && attachment.getSource() == AttachmentSource.OPERATOR_UPLOAD
                && attachment.getKind() == AttachmentKind.IMAGE
                && AttachmentProperties.TURN_IMAGE_CONTENT_TYPES.contains(
                    attachment.getContentType())
                && attachment.getSizeBytes() > 0
                && attachment.getSizeBytes() <= properties.getMaxFileBytes()
                && attachment.getRetentionClass() == AttachmentRetentionClass.SESSION
                && attachment.getRetainUntil() != null
                && attachment.getRetainUntil().isAfter(now)
                && attachment.getSha256() != null
                && LOWERCASE_SHA256.matcher(attachment.getSha256()).matches()
                && Objects.equals(session.getSelectedWorkerId(), attachment.getWorkerId())
                && attachment.getStorageIdentity() != null
                && !attachment.getStorageIdentity().isBlank()
                && attachment.getStorageScope() == AttachmentStorageScope.REAL_SESSION
                && Objects.equals(session.getRemoteSessionId(), attachment.getRemoteSessionId())
                && Objects.equals(expectedWorkspace, attachment.getWorkspaceIdentity())
                && attachment.getCreatedAt() != null
                && attachment.getIndexedAt() != null;
        if (!exactOwnership) {
            if (attachment.getRetainUntil() != null
                    && !attachment.getRetainUntil().isAfter(now)) {
                throw new AttachmentConflictException(
                        "La imagen ya no admite una vinculación nueva.");
            }
            if (attachment.getSizeBytes() > properties.getMaxFileBytes()) {
                throw new AttachmentLimitException(
                        "Una imagen supera el límite individual de 16 MiB.");
            }
            throw new AttachmentOwnershipException(
                    "Una imagen no tiene ownership real completo y verificable.");
        }
    }

    private void requireCompatibleWorker(
            WorkSessionEntity session,
            AttachmentWorkerClient.Health health
    ) {
        if (health == null
                || !health.healthy()
                || !AttachmentProperties.PROTOCOL.equals(health.protocolVersion())
                || !Objects.equals(session.getSelectedWorkerId(), health.workerId())
                || health.maxFileBytes() != properties.getMaxFileBytes()
                || health.maxSessionBytes() != properties.getMaxSessionBytes()
                || health.contentTypes() == null
                || !health.contentTypes().containsAll(AttachmentProperties.TURN_IMAGE_CONTENT_TYPES)) {
            throw new AttachmentWorkerException(
                    "AX42 no anuncia un contrato de imágenes compatible.",
                    503,
                    "incompatible_attachment_worker");
        }
    }

    private void requireCompatibleRealCapability(
            WorkSessionEntity session,
            AttachmentWorkerClient.RealProjectCapability capability
    ) {
        if (capability == null
                || !capability.healthy()
                || !AttachmentWorkerClient.REAL_PROJECT_PROTOCOL.equals(
                    capability.protocolVersion())
                || !Objects.equals(session.getSelectedWorkerId(), capability.workerId())
                || !List.of(ProjectCodexIdentity.PROJECT_IDENTITY)
                    .equals(capability.projectIdentities())
                || !List.of(AttachmentStorageScope.REAL_SESSION)
                    .equals(capability.storageScopes())) {
            throw new AttachmentWorkerException(
                    "AX42 no anuncia un contrato de adjuntos reales compatible.",
                    503,
                    "incompatible_real_attachment_worker");
        }
    }

    private void requireExactRetainedIdentity(
            WorkSessionEntity session,
            WorkSessionAttachmentEntity indexed,
            AttachmentWorkerClient.StoredAttachment retained
    ) {
        boolean exact = retained != null
                && AttachmentProperties.PROTOCOL.equals(retained.protocolVersion())
                && Objects.equals(session.getSelectedWorkerId(), retained.workerId())
                && Objects.equals(session.getRemoteSessionId().toString(), retained.sessionId())
                && Objects.equals(indexed.getId(), retained.attachmentId())
                && Objects.equals(indexed.getStorageIdentity(), retained.storageIdentity())
                && indexed.getSource() == retained.source()
                && indexed.getKind() == retained.kind()
                && Objects.equals(indexed.getContentType(), retained.contentType())
                && indexed.getSizeBytes() == retained.sizeBytes()
                && indexed.getRetentionClass() == retained.retentionClass()
                && Objects.equals(indexed.getSha256(), retained.sha256())
                && !retained.syntheticFixture()
                && Objects.equals(indexed.getCreatedAt(), retained.createdAt())
                && retained.storedAt() != null;
        if (!exact) {
            throw new AttachmentOwnershipException(
                    "La identidad retenida de una imagen no coincide con su índice inmutable.");
        }
    }

    private void requireExactRetainedContent(
            WorkSessionAttachmentEntity indexed,
            AttachmentWorkerClient.Content content
    ) {
        boolean exact = content != null
                && content.bytes() != null
                && content.bytes().length == indexed.getSizeBytes()
                && Objects.equals(indexed.getContentType(), content.contentType())
                && Objects.equals(indexed.getSha256(), sha256(content.bytes()));
        if (!exact) {
            throw new AttachmentOwnershipException(
                    "El contenido retenido de una imagen no coincide con su integridad indexada.");
        }
    }

    private String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public record ValidatedSelection(
            List<ValidatedAttachment> attachments,
            long totalBytes,
            String manifestSha256
    ) {
    }

    public record ValidatedAttachment(
            UUID id,
            String contentType,
            long sizeBytes,
            String sha256
    ) {
    }
}
