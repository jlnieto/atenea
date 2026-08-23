package com.atenea.remoteworker;

import com.atenea.attachments.AttachmentProperties;
import com.atenea.attachments.RealAttachmentProjectRegistry;
import com.atenea.persistence.worksession.AgentRunEntity;
import com.atenea.persistence.worksession.AttachmentKind;
import com.atenea.persistence.worksession.AttachmentRetentionClass;
import com.atenea.persistence.worksession.AttachmentSource;
import com.atenea.persistence.worksession.AttachmentStorageScope;
import com.atenea.persistence.worksession.ExecutionTarget;
import com.atenea.persistence.worksession.SessionTurnAttachmentEntity;
import com.atenea.persistence.worksession.SessionTurnAttachmentRepository;
import com.atenea.persistence.worksession.WorkSessionAttachmentEntity;
import com.atenea.persistence.worksession.WorkSessionAttachmentRepository;
import com.atenea.service.worksession.TurnAttachmentFingerprintService;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProjectCodexAttachmentManifestService {

    private final SessionTurnAttachmentRepository bindingRepository;
    private final WorkSessionAttachmentRepository attachmentRepository;
    private final TurnAttachmentFingerprintService fingerprintService;

    public ProjectCodexAttachmentManifestService(
            SessionTurnAttachmentRepository bindingRepository,
            WorkSessionAttachmentRepository attachmentRepository,
            TurnAttachmentFingerprintService fingerprintService
    ) {
        this.bindingRepository = bindingRepository;
        this.attachmentRepository = attachmentRepository;
        this.fingerprintService = fingerprintService;
    }

    @Transactional(readOnly = true)
    public List<AttachmentReference> exactReferences(AgentRunEntity run) {
        requireCompleteRun(run);
        List<SessionTurnAttachmentEntity> bindings = bindingRepository
                .findByWorkSessionIdAndSessionTurnIdOrderByPositionAsc(
                        run.getSession().getId(),
                        run.getOriginTurn().getId());
        if (bindings.size() != run.getAttachmentCount()) {
            throw conflict("Persisted image binding count differs from the AgentRun snapshot");
        }
        Collection<UUID> ids = bindings.stream()
                .map(SessionTurnAttachmentEntity::getAttachmentId)
                .collect(Collectors.toCollection(HashSet::new));
        if (ids.size() != bindings.size()) {
            throw conflict("Persisted image bindings contain duplicate identities");
        }
        Map<UUID, WorkSessionAttachmentEntity> indexed = attachmentRepository.findAllById(ids)
                .stream()
                .collect(Collectors.toMap(WorkSessionAttachmentEntity::getId, Function.identity()));
        if (indexed.size() != ids.size()) {
            throw conflict("Persisted image metadata is incomplete");
        }

        List<AttachmentReference> references = new ArrayList<>(bindings.size());
        long totalBytes = 0L;
        for (int index = 0; index < bindings.size(); index++) {
            SessionTurnAttachmentEntity binding = bindings.get(index);
            if (binding.getPosition() != index) {
                throw conflict("Persisted image binding order is incomplete");
            }
            WorkSessionAttachmentEntity attachment = indexed.get(binding.getAttachmentId());
            requireExactOwnership(run, attachment);
            if (totalBytes > AttachmentProperties.DEFAULT_MAX_ATTACHMENT_BYTES_PER_TURN
                    - attachment.getSizeBytes()) {
                throw conflict("Persisted image manifest exceeds the combined byte limit");
            }
            totalBytes += attachment.getSizeBytes();
            references.add(new AttachmentReference(
                    attachment.getId(),
                    attachment.getContentType(),
                    attachment.getSizeBytes(),
                    attachment.getSha256()));
        }
        String manifestSha256 = fingerprintService.attachmentManifestSha256(
                references.stream()
                        .map(reference ->
                                new TurnAttachmentFingerprintService.AttachmentFingerprintInput(
                                        reference.attachmentId(),
                                        reference.contentType(),
                                        reference.sizeBytes(),
                                        reference.sha256()))
                        .toList());
        if (totalBytes != run.getAttachmentBytes()
                || !Objects.equals(manifestSha256, run.getAttachmentManifestSha256())) {
            throw conflict("Persisted image references differ from the AgentRun manifest");
        }
        return List.copyOf(references);
    }

    private void requireCompleteRun(AgentRunEntity run) {
        if (run == null
                || (!ProjectCodexIdentity.IMAGE_WORKLOAD_KIND.equals(run.getWorkloadKind())
                    && !ProjectCodexIdentity.CHANGE_WORKLOAD_KIND.equals(run.getWorkloadKind()))
                || !ProjectCodexIdentity.matches(run)
                || run.getSession() == null
                || run.getSession().getId() == null
                || run.getSession().getProject() == null
                || run.getSession().getExecutionTarget() != ExecutionTarget.REMOTE
                || !ProjectCodexIdentity.WORKLOAD_KIND.equals(
                    run.getSession().getRemoteWorkloadKind())
                || !Objects.equals(run.getSelectedWorkerId(), run.getSession().getSelectedWorkerId())
                || !Objects.equals(run.getRemoteSessionId(), run.getSession().getRemoteSessionId())
                || !Objects.equals(run.getWorkspaceIdentity(), run.getSession().getWorkspaceIdentity())
                || !RealAttachmentProjectRegistry.ATENEA_WORKER_ID.equals(run.getSelectedWorkerId())
                || run.getRemoteSessionId() == null
                || run.getWorkspaceIdentity() == null
                || !RealAttachmentProjectRegistry.ATENEA_POLICY_REVISION.equals(
                    run.getSession().getAttachmentPolicyRevision())
                || (!ProjectCodexIdentity.CHANGE_WORKLOAD_KIND.equals(run.getWorkloadKind())
                    && !("remote:" + run.getSelectedWorkerId() + ":work-session:"
                        + run.getRemoteSessionId()).equals(run.getWorkspaceIdentity()))
                || (ProjectCodexIdentity.CHANGE_WORKLOAD_KIND.equals(run.getWorkloadKind())
                    && (run.getDevelopmentChangeKey() == null
                        || !("remote:" + run.getSelectedWorkerId() + ":change:"
                            + run.getDevelopmentChangeKey()).equals(run.getWorkspaceIdentity())))
                || run.getOriginTurn() == null
                || run.getOriginTurn().getId() == null
                || run.getOriginTurn().getSession() == null
                || !Objects.equals(
                    run.getSession().getId(), run.getOriginTurn().getSession().getId())
                || run.getAttachmentCount() < 1
                || run.getAttachmentCount() > AttachmentProperties.DEFAULT_MAX_ATTACHMENTS_PER_TURN
                || run.getAttachmentBytes() < 1
                || run.getAttachmentBytes()
                    > AttachmentProperties.DEFAULT_MAX_ATTACHMENT_BYTES_PER_TURN
                || run.getAttachmentManifestSha256() == null
                || !run.getAttachmentManifestSha256().matches("^[0-9a-f]{64}$")) {
            throw conflict("Persisted image AgentRun ownership or manifest is incomplete");
        }
    }

    private void requireExactOwnership(
            AgentRunEntity run,
            WorkSessionAttachmentEntity attachment
    ) {
        boolean exact = attachment != null
                && attachment.getWorkSession() != null
                && Objects.equals(run.getSession().getId(), attachment.getWorkSession().getId())
                && attachment.getProject() != null
                && Objects.equals(run.getSession().getProject().getId(), attachment.getProject().getId())
                && attachment.getSource() == AttachmentSource.OPERATOR_UPLOAD
                && attachment.getKind() == AttachmentKind.IMAGE
                && AttachmentProperties.TURN_IMAGE_CONTENT_TYPES.contains(attachment.getContentType())
                && attachment.getSizeBytes() > 0
                && attachment.getSizeBytes() <= AttachmentProperties.DEFAULT_MAX_FILE_BYTES
                && attachment.getRetentionClass() == AttachmentRetentionClass.SESSION
                && attachment.getRetainUntil() != null
                && attachment.getSha256() != null
                && attachment.getSha256().matches("^[0-9a-f]{64}$")
                && Objects.equals(run.getSelectedWorkerId(), attachment.getWorkerId())
                && attachment.getStorageIdentity() != null
                && !attachment.getStorageIdentity().isBlank()
                && attachment.getStorageScope() == AttachmentStorageScope.REAL_SESSION
                && Objects.equals(run.getRemoteSessionId(), attachment.getRemoteSessionId())
                && Objects.equals(run.getWorkspaceIdentity(), attachment.getWorkspaceIdentity())
                && attachment.getCreatedAt() != null
                && attachment.getIndexedAt() != null;
        if (!exact) {
            throw conflict("Persisted image reference has incomplete or conflicting ownership");
        }
    }

    private RemoteWorkerException conflict(String message) {
        return new RemoteWorkerException(message, 409);
    }

    public record AttachmentReference(
            UUID attachmentId,
            String contentType,
            long sizeBytes,
            String sha256
    ) {
    }
}
