package com.atenea.previews;

import com.atenea.persistence.worksession.ExecutionTarget;
import com.atenea.persistence.worksession.PreviewState;
import com.atenea.persistence.worksession.WorkSessionEntity;
import com.atenea.persistence.worksession.WorkSessionPreviewEntity;
import com.atenea.persistence.worksession.WorkSessionRepository;
import com.atenea.service.worksession.PreviewIndexRequest;
import com.atenea.service.worksession.PreviewNotFoundException;
import com.atenea.service.worksession.PreviewOwnershipException;
import com.atenea.service.worksession.WorkSessionNotFoundException;
import com.atenea.service.worksession.WorkSessionPreviewMetadataService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
public class WorkSessionPreviewService {

    private static final Pattern WORKER_PROJECT_ID =
            Pattern.compile("[a-z0-9][a-z0-9-]{0,79}");

    private final PreviewProperties properties;
    private final PreviewWorkerClient workerClient;
    private final WorkSessionRepository workSessionRepository;
    private final WorkSessionPreviewMetadataService metadataService;

    public WorkSessionPreviewService(
            PreviewProperties properties,
            PreviewWorkerClient workerClient,
            WorkSessionRepository workSessionRepository,
            WorkSessionPreviewMetadataService metadataService
    ) {
        this.properties = properties;
        this.workerClient = workerClient;
        this.workSessionRepository = workSessionRepository;
        this.metadataService = metadataService;
    }

    public WorkSessionPreviewEntity activate(Long workSessionId, PreviewActivationCommand command) {
        WorkSessionEntity session = requireActivationAllowed(workSessionId);
        requireActivationCommand(command);
        UUID previewId = command.previewId() == null ? UUID.randomUUID() : command.previewId();
        String allocationIdentity = allocationIdentity(command.runtimeSessionId());
        Instant createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        WorkSessionPreviewEntity preview = metadataService.create(
                workSessionId,
                new PreviewIndexRequest(
                        previewId,
                        command.agentRunId(),
                        session.getSelectedWorkerId(),
                        allocationIdentity,
                        command.allocationFingerprint(),
                        false,
                        createdAt));
        if (preview.getState() == PreviewState.READY) {
            return preview;
        }
        if (preview.getState() != PreviewState.STARTING) {
            throw new PreviewFeatureDisabledException(
                    "Este preview ya terminó. Inicia una nueva activación.");
        }

        try {
            requireCompatibleHealth(session, workerClient.health());
            PreviewWorkerClient.Projection projection = workerClient.activate(
                    ownership(preview), command.runtimeSessionId());
            validateProjection(preview, projection, "READY", preview.getLifecycleRevision() + 1);
            return metadataService.markReady(
                    preview.getId(),
                    preview.getLifecycleRevision(),
                    projection.privateUrl(),
                    projection.localhostCompatible(),
                    projection.leaseExpiresAt(),
                    projection.hardExpiresAt(),
                    Instant.now());
        } catch (RuntimeException failure) {
            blockStarting(preview, failure);
            throw failure;
        }
    }

    public WorkSessionPreviewEntity status(Long workSessionId) {
        return metadataService.latest(workSessionId);
    }

    public List<WorkSessionPreviewEntity> retained(Long workSessionId) {
        return metadataService.retainedAudit(workSessionId, Instant.now());
    }

    public WorkSessionPreviewEntity renew(Long workSessionId, UUID previewId) {
        requireEnabled();
        WorkSessionPreviewEntity preview = metadataService.get(workSessionId, previewId);
        requireConfiguredOwnership(preview);
        PreviewWorkerClient.Projection projection = workerClient.renew(ownership(preview));
        validateProjection(preview, projection, "READY", preview.getLifecycleRevision() + 1);
        return metadataService.renewFromWorker(
                previewId,
                preview.getLifecycleRevision(),
                projection.leaseExpiresAt(),
                projection.hardExpiresAt(),
                Instant.now());
    }

    public WorkSessionPreviewEntity stop(Long workSessionId, UUID previewId) {
        WorkSessionPreviewEntity preview = metadataService.get(workSessionId, previewId);
        if (preview.getState() == PreviewState.STOPPED || preview.getState() == PreviewState.EXPIRED) {
            return preview;
        }
        requireEnabled();
        requireConfiguredOwnership(preview);
        PreviewWorkerClient.Projection projection = workerClient.stop(ownership(preview));
        validateProjection(preview, projection, "STOPPED", preview.getLifecycleRevision() + 1);
        return metadataService.stop(previewId, preview.getLifecycleRevision(), Instant.now());
    }

    public PreviewTunnel localhost(Long workSessionId, UUID previewId, int localPort) {
        if (localPort < 1024 || localPort > 65535) {
            throw new IllegalArgumentException("El puerto localhost debe estar entre 1024 y 65535.");
        }
        WorkSessionPreviewEntity preview = metadataService.get(workSessionId, previewId);
        if (preview.getState() != PreviewState.READY || !preview.isLocalhostCompatible()) {
            throw new PreviewFeatureDisabledException(
                    "Este preview no declara compatibilidad localhost lista.");
        }
        requireEnabled();
        PreviewWorkerClient.Projection projection = workerClient.inspect(ownership(preview));
        validateProjection(preview, projection, "READY", preview.getLifecycleRevision());
        PreviewWorkerClient.Tunnel tunnel = projection.tunnel();
        if (tunnel == null
                || tunnel.credentialIncluded()
                || tunnel.runtimePortExposed()
                || !"codex-worker".equals(tunnel.sshDestination())
                || !Objects.equals(tunnel.remoteHost(), properties.getPrivateHost())
                || tunnel.remotePort() < 19000
                || tunnel.remotePort() > 19031
                || tunnel.path() == null
                || !tunnel.path().startsWith("/")) {
            throw new PreviewWorkerException(
                    "AX42 devolvió datos localhost fuera del contrato privado.",
                    409,
                    "preview_tunnel_identity_conflict");
        }
        String command = "ssh -N -L 127.0.0.1:" + localPort + ":"
                + tunnel.remoteHost() + ":" + tunnel.remotePort() + " "
                + tunnel.sshDestination();
        return new PreviewTunnel(command, "http://127.0.0.1:" + localPort + tunnel.path(), localPort);
    }

    PreviewWorkerClient.Ownership ownership(WorkSessionPreviewEntity preview) {
        return new PreviewWorkerClient.Ownership(
                preview.getId(),
                preview.getWorkSession().getId().toString(),
                projectIdentity(preview.getProject().getName()),
                preview.getWorkerId(),
                preview.getAllocationIdentity(),
                preview.getAllocationFingerprint(),
                preview.getLifecycleRevision());
    }

    void validateProjection(
            WorkSessionPreviewEntity preview,
            PreviewWorkerClient.Projection projection,
            String expectedState,
            long expectedRevision
    ) {
        if (projection == null
                || !PreviewProperties.PROTOCOL.equals(projection.protocolVersion())
                || !Objects.equals(preview.getId(), projection.previewId())
                || !Objects.equals(preview.getWorkSession().getId().toString(), projection.workSessionId())
                || !Objects.equals(projectIdentity(preview.getProject().getName()), projection.projectId())
                || !Objects.equals(preview.getWorkerId(), projection.workerId())
                || !Objects.equals(preview.getAllocationIdentity(), projection.allocationIdentity())
                || !Objects.equals(preview.getAllocationFingerprint(), projection.allocationFingerprint())
                || projection.lifecycleRevision() != expectedRevision
                || !Objects.equals(expectedState, projection.state())
                || !projection.syntheticFixture()) {
            throw new PreviewWorkerException(
                    "AX42 devolvió una identidad de preview distinta de la persistida.",
                    409,
                    "preview_response_identity_conflict");
        }
    }

    private WorkSessionEntity requireActivationAllowed(Long workSessionId) {
        requireEnabled();
        WorkSessionEntity session = workSessionRepository.findWithProjectById(workSessionId)
                .orElseThrow(() -> new WorkSessionNotFoundException(workSessionId));
        if (!properties.getSyntheticProjectAllowlist().contains(session.getProject().getName())) {
            throw new PreviewFeatureDisabledException(
                    "Este proyecto no está autorizado para previews sintéticos.");
        }
        if (session.getExecutionTarget() != ExecutionTarget.REMOTE
                || !Objects.equals(session.getSelectedWorkerId(), properties.getWorkerId())) {
            throw new PreviewOwnershipException(
                    "La WorkSession no tiene afinidad persistida con el worker de previews.");
        }
        return session;
    }

    private void requireActivationCommand(PreviewActivationCommand command) {
        if (command == null || command.runtimeSessionId() == null) {
            throw new IllegalArgumentException("Falta la identidad persistida del runtime.");
        }
        if (command.allocationFingerprint() == null
                || !command.allocationFingerprint().matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("El fingerprint de allocation no es válido.");
        }
    }

    private void requireEnabled() {
        if (!properties.isEnabled()) {
            throw new PreviewFeatureDisabledException(
                    "Los previews nuevos están desactivados; el estado retenido sigue disponible.");
        }
    }

    private void requireConfiguredOwnership(WorkSessionPreviewEntity preview) {
        if (!Objects.equals(preview.getWorkerId(), properties.getWorkerId())
                || !properties.getSyntheticProjectAllowlist().contains(preview.getProject().getName())) {
            throw new PreviewOwnershipException(
                    "El preview retenido no pertenece al worker y proyecto habilitados.");
        }
    }

    private void requireCompatibleHealth(
            WorkSessionEntity session,
            PreviewWorkerClient.Health health
    ) {
        if (health == null
                || !health.healthy()
                || !PreviewProperties.PROTOCOL.equals(health.protocolVersion())
                || !Objects.equals(session.getSelectedWorkerId(), health.workerId())
                || health.publicSharing()
                || health.arbitraryUpstream()
                || !Objects.equals(health.ingressRange(), List.of(19000, 19031))) {
            throw new PreviewWorkerException(
                    "AX42 no anuncia un contrato de preview privado compatible.",
                    503,
                    "incompatible_preview_worker");
        }
    }

    private void blockStarting(WorkSessionPreviewEntity preview, RuntimeException failure) {
        try {
            metadataService.block(
                    preview.getId(),
                    preview.getLifecycleRevision(),
                    failure instanceof PreviewWorkerException worker ? worker.getCode() : "preview_activation_failed",
                    "No se pudo confirmar la ruta privada.",
                    "Revisa el runtime y vuelve a iniciar el preview.",
                    Instant.now());
        } catch (RuntimeException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
    }

    private String allocationIdentity(UUID runtimeSessionId) {
        return "ws-" + runtimeSessionId.toString().replace("-", "");
    }

    private String projectIdentity(String projectName) {
        String identity = projectName == null
                ? ""
                : projectName.trim().toLowerCase(Locale.ROOT);
        if (!WORKER_PROJECT_ID.matcher(identity).matches()) {
            throw new PreviewOwnershipException(
                    "El proyecto no tiene una identidad canónica compatible con el worker.");
        }
        return identity;
    }
}
