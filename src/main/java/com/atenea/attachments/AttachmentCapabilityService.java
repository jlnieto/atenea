package com.atenea.attachments;

import com.atenea.persistence.worksession.AttachmentStorageScope;
import com.atenea.persistence.worksession.ExecutionTarget;
import com.atenea.persistence.worksession.WorkSessionAttachmentRepository;
import com.atenea.persistence.worksession.WorkSessionEntity;
import com.atenea.persistence.worksession.WorkSessionRepository;
import com.atenea.remoteworker.ProjectCodexIdentity;
import com.atenea.service.worksession.WorkSessionNotFoundException;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;

@Service
public class AttachmentCapabilityService {

    private final AttachmentProperties properties;
    private final AttachmentAdmissionPolicy admissionPolicy;
    private final AttachmentWorkerClient workerClient;
    private final WorkSessionRepository workSessionRepository;
    private final WorkSessionAttachmentRepository attachmentRepository;

    public AttachmentCapabilityService(
            AttachmentProperties properties,
            AttachmentAdmissionPolicy admissionPolicy,
            AttachmentWorkerClient workerClient,
            WorkSessionRepository workSessionRepository,
            WorkSessionAttachmentRepository attachmentRepository
    ) {
        this.properties = properties;
        this.admissionPolicy = admissionPolicy;
        this.workerClient = workerClient;
        this.workSessionRepository = workSessionRepository;
        this.attachmentRepository = attachmentRepository;
    }

    public AttachmentCapability get(Long workSessionId) {
        WorkSessionEntity session = workSessionRepository.findWithProjectById(workSessionId)
                .orElseThrow(() -> new WorkSessionNotFoundException(workSessionId));
        long currentBytes = Math.max(
                0L,
                attachmentRepository.sumSizeBytesByWorkSessionId(workSessionId));

        if (!admissionPolicy.isGlobalCreateBindEnabled()) {
            return blocked(
                    session,
                    currentBytes,
                    AttachmentCapability.BlockedReason.GLOBAL_DISABLED,
                    "Los adjuntos nuevos están desactivados.",
                    "Continúa con texto o contacta con un administrador.",
                    AttachmentCapability.WorkerCompatibility.NOT_CHECKED);
        }

        boolean canonicalProject = ProjectCodexIdentity.matches(session);
        if (!canonicalProject && session.getAttachmentPolicyRevision() == null) {
            return blocked(
                    session,
                    currentBytes,
                    AttachmentCapability.BlockedReason.PROJECT_DISABLED,
                    "Los adjuntos no están habilitados para este proyecto.",
                    "Continúa con texto; solo las WorkSessions nuevas de Atenea pueden adjuntar.",
                    AttachmentCapability.WorkerCompatibility.NOT_CHECKED);
        }
        if (canonicalProject
                && !admissionPolicy.isRealCreateBindEnabled(ProjectCodexIdentity.PROJECT_IDENTITY)) {
            return blocked(
                    session,
                    currentBytes,
                    AttachmentCapability.BlockedReason.PROJECT_DISABLED,
                    "Los adjuntos no están habilitados para Atenea.",
                    "Continúa con texto o contacta con un administrador.",
                    AttachmentCapability.WorkerCompatibility.NOT_CHECKED);
        }
        if (session.getAttachmentPolicyRevision() == null) {
            return blocked(
                    session,
                    currentBytes,
                    AttachmentCapability.BlockedReason.SESSION_NOT_ELIGIBLE,
                    "Esta sesión se creó antes de habilitar los adjuntos.",
                    "Crea una WorkSession nueva de Atenea.",
                    AttachmentCapability.WorkerCompatibility.NOT_CHECKED);
        }
        if (!hasExactRealOwnership(session)) {
            return blocked(
                    session,
                    currentBytes,
                    AttachmentCapability.BlockedReason.OWNERSHIP_INVALID,
                    "La sesión no tiene ownership canónico completo para adjuntos.",
                    "Cierra esta sesión y crea una WorkSession nueva y limpia.",
                    AttachmentCapability.WorkerCompatibility.NOT_CHECKED);
        }
        if (currentBytes >= properties.getMaxSessionBytes()) {
            return blocked(
                    session,
                    currentBytes,
                    AttachmentCapability.BlockedReason.SESSION_QUOTA_EXHAUSTED,
                    "La sesión ha agotado la cuota de adjuntos.",
                    "Continúa con texto; los adjuntos retenidos no se eliminan automáticamente.",
                    AttachmentCapability.WorkerCompatibility.NOT_CHECKED);
        }

        AttachmentWorkerClient.Health health;
        try {
            health = workerClient.health();
        } catch (AttachmentWorkerException exception) {
            return blocked(
                    session,
                    currentBytes,
                    AttachmentCapability.BlockedReason.WORKER_UNAVAILABLE,
                    "El almacenamiento de adjuntos no está disponible.",
                    "Reintenta cuando AX42 vuelva a estar accesible.",
                    AttachmentCapability.WorkerCompatibility.UNAVAILABLE);
        }
        if (!compatibleHealth(session, health)) {
            return unsupported(session, currentBytes);
        }

        AttachmentWorkerClient.RealProjectCapability realCapability;
        try {
            realCapability = workerClient.realProjectCapability();
        } catch (AttachmentWorkerException exception) {
            if (exception.getStatusCode() == 404) {
                return unsupported(session, currentBytes);
            }
            return blocked(
                    session,
                    currentBytes,
                    AttachmentCapability.BlockedReason.WORKER_UNAVAILABLE,
                    "El almacenamiento de adjuntos no está disponible.",
                    "Reintenta cuando AX42 vuelva a estar accesible.",
                    AttachmentCapability.WorkerCompatibility.UNAVAILABLE);
        }
        if (!compatibleRealCapability(session, realCapability)) {
            return unsupported(session, currentBytes);
        }

        return new AttachmentCapability(
                AttachmentCapability.State.READY,
                AttachmentCapability.BlockedReason.NONE,
                "Puedes adjuntar hasta 4 imágenes al próximo mensaje.",
                "Selecciona imágenes PNG, JPEG o WebP.",
                session.getAttachmentPolicyRevision(),
                AttachmentCapability.WorkerCompatibility.COMPATIBLE,
                AttachmentProperties.TURN_IMAGE_CONTENT_TYPES,
                currentBytes,
                properties.getMaxSessionBytes(),
                remainingBytes(currentBytes),
                properties.getMaxFileBytes(),
                AttachmentProperties.DEFAULT_MAX_ATTACHMENTS_PER_TURN,
                AttachmentProperties.DEFAULT_MAX_ATTACHMENT_BYTES_PER_TURN);
    }

    private boolean hasExactRealOwnership(WorkSessionEntity session) {
        String expectedWorkspace = "remote:" + RealAttachmentProjectRegistry.ATENEA_WORKER_ID
                + ":work-session:" + session.getRemoteSessionId();
        return RealAttachmentProjectRegistry.ATENEA_POLICY_REVISION.equals(
                    session.getAttachmentPolicyRevision())
                && ProjectCodexIdentity.matches(session)
                && session.getExecutionTarget() == ExecutionTarget.REMOTE
                && RealAttachmentProjectRegistry.ATENEA_WORKER_ID.equals(
                    session.getSelectedWorkerId())
                && session.getRemoteSessionId() != null
                && ProjectCodexIdentity.WORKLOAD_KIND.equals(session.getRemoteWorkloadKind())
                && Objects.equals(expectedWorkspace, session.getWorkspaceIdentity());
    }

    private boolean compatibleHealth(
            WorkSessionEntity session,
            AttachmentWorkerClient.Health health
    ) {
        return health != null
                && health.healthy()
                && AttachmentProperties.PROTOCOL.equals(health.protocolVersion())
                && Objects.equals(session.getSelectedWorkerId(), health.workerId())
                && health.maxFileBytes() == properties.getMaxFileBytes()
                && health.maxSessionBytes() == properties.getMaxSessionBytes()
                && health.contentTypes() != null
                && health.contentTypes().containsAll(AttachmentProperties.TURN_IMAGE_CONTENT_TYPES);
    }

    private boolean compatibleRealCapability(
            WorkSessionEntity session,
            AttachmentWorkerClient.RealProjectCapability capability
    ) {
        return capability != null
                && capability.healthy()
                && AttachmentWorkerClient.REAL_PROJECT_PROTOCOL.equals(
                    capability.protocolVersion())
                && Objects.equals(session.getSelectedWorkerId(), capability.workerId())
                && List.of(ProjectCodexIdentity.PROJECT_IDENTITY)
                    .equals(capability.projectIdentities())
                && List.of(AttachmentStorageScope.REAL_SESSION)
                    .equals(capability.storageScopes());
    }

    private AttachmentCapability unsupported(WorkSessionEntity session, long currentBytes) {
        return blocked(
                session,
                currentBytes,
                AttachmentCapability.BlockedReason.WORKER_UNSUPPORTED,
                "AX42 no anuncia un contrato de imágenes compatible.",
                "Actualiza o recupera el servicio de adjuntos antes de reintentar.",
                AttachmentCapability.WorkerCompatibility.INCOMPATIBLE);
    }

    private AttachmentCapability blocked(
            WorkSessionEntity session,
            long currentBytes,
            AttachmentCapability.BlockedReason reason,
            String message,
            String nextAction,
            AttachmentCapability.WorkerCompatibility workerCompatibility
    ) {
        return new AttachmentCapability(
                AttachmentCapability.State.BLOCKED,
                reason,
                message,
                nextAction,
                session.getAttachmentPolicyRevision(),
                workerCompatibility,
                AttachmentProperties.TURN_IMAGE_CONTENT_TYPES,
                currentBytes,
                properties.getMaxSessionBytes(),
                remainingBytes(currentBytes),
                properties.getMaxFileBytes(),
                AttachmentProperties.DEFAULT_MAX_ATTACHMENTS_PER_TURN,
                AttachmentProperties.DEFAULT_MAX_ATTACHMENT_BYTES_PER_TURN);
    }

    private long remainingBytes(long currentBytes) {
        return Math.max(0L, properties.getMaxSessionBytes() - currentBytes);
    }
}
