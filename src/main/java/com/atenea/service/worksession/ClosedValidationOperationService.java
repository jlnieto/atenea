package com.atenea.service.worksession;

import com.atenea.api.worksession.ValidationOperationResponse;
import com.atenea.persistence.worksession.AgentRunRepository;
import com.atenea.persistence.worksession.AgentRunStatus;
import com.atenea.persistence.worksession.ExecutionTarget;
import com.atenea.persistence.worksession.ValidationOperationEntity;
import com.atenea.persistence.worksession.ValidationOperationKind;
import com.atenea.persistence.worksession.ValidationOperationRepository;
import com.atenea.persistence.worksession.ValidationOperationStatus;
import com.atenea.persistence.worksession.WorkSessionEntity;
import com.atenea.persistence.worksession.WorkSessionRepository;
import com.atenea.persistence.worksession.WorkSessionStatus;
import com.atenea.remoteworker.ProjectCodexIdentity;
import com.atenea.remoteworker.RemoteWorkerClient;
import com.atenea.remoteworker.RemoteWorkerException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ClosedValidationOperationService {

    private final WorkSessionRepository workSessionRepository;
    private final AgentRunRepository agentRunRepository;
    private final ValidationOperationRepository validationOperationRepository;
    private final RemoteWorkerClient remoteWorkerClient;
    private final WorkSessionAcceptanceService acceptanceService;

    public ClosedValidationOperationService(
            WorkSessionRepository workSessionRepository,
            AgentRunRepository agentRunRepository,
            ValidationOperationRepository validationOperationRepository,
            RemoteWorkerClient remoteWorkerClient,
            WorkSessionAcceptanceService acceptanceService
    ) {
        this.workSessionRepository = workSessionRepository;
        this.agentRunRepository = agentRunRepository;
        this.validationOperationRepository = validationOperationRepository;
        this.remoteWorkerClient = remoteWorkerClient;
        this.acceptanceService = acceptanceService;
    }

    @Transactional
    public ValidationOperationResponse run(Long sessionId, ValidationOperationKind operation) {
        WorkSessionEntity session = workSessionRepository.findLockedWithProjectById(sessionId)
                .orElseThrow(() -> new WorkSessionNotFoundException(sessionId));
        requireExactIdleSession(session);

        RemoteWorkerClient.SourceTreeFingerprint source = remoteWorkerClient.fingerprintSourceTree(session);
        validateSourceObservation(session, source);
        acceptanceService.observeSourceTree(sessionId, source.fingerprintSha256());

        String identity = sha256(
                session.getRemoteSessionId() + "\0"
                        + operation.name() + "\0"
                        + operation.definitionRevision() + "\0"
                        + source.fingerprintSha256());
        ValidationOperationEntity existing =
                validationOperationRepository.findByIdentitySha256(identity).orElse(null);
        if (existing != null) {
            return response(existing);
        }

        UUID validationId = UUID.randomUUID();
        Instant startedAt = Instant.now();
        ValidationOperationEntity entity = new ValidationOperationEntity();
        entity.setId(validationId);
        entity.setWorkSession(session);
        entity.setOperation(operation);
        entity.setStatus(ValidationOperationStatus.RUNNING);
        entity.setSourceTreeFingerprintSha256(source.fingerprintSha256());
        entity.setDefinitionRevision(operation.definitionRevision());
        entity.setIdentitySha256(identity);
        entity.setExitCode(null);
        entity.setDurationMillis(null);
        entity.setArtifactManifestSha256(null);
        entity.setSummary("Bounded validation is running");
        entity.setStartedAt(startedAt);
        entity.setFinishedAt(null);
        entity.setCreatedAt(startedAt);
        entity.setUpdatedAt(startedAt);
        validationOperationRepository.saveAndFlush(entity);

        acceptanceService.markValidating(
                sessionId,
                source.fingerprintSha256(),
                projectionSha256(sessionId, source.fingerprintSha256()),
                profileRevision());

        try {
            RemoteWorkerClient.ValidationResult result = remoteWorkerClient.runValidation(
                    session,
                    operation,
                    source.fingerprintSha256(),
                    validationId.toString());
            applyResult(entity, session, result);
        } catch (RemoteWorkerException exception) {
            Instant finishedAt = Instant.now();
            entity.setStatus(ValidationOperationStatus.BLOCKED);
            entity.setExitCode(null);
            entity.setDurationMillis(Duration.between(startedAt, finishedAt).toMillis());
            entity.setArtifactManifestSha256(null);
            entity.setSummary("Worker validation authority was unavailable");
            entity.setFinishedAt(finishedAt);
            entity.setUpdatedAt(finishedAt);
            validationOperationRepository.save(entity);
        }

        projectAcceptance(sessionId, source.fingerprintSha256());
        return response(entity);
    }

    private void applyResult(
            ValidationOperationEntity entity,
            WorkSessionEntity session,
            RemoteWorkerClient.ValidationResult result
    ) {
        if (!entity.getId().toString().equals(result.validationId())
                || !session.getRemoteSessionId().toString().equals(result.sessionId())
                || !session.getWorkspaceIdentity().equals(result.workspaceIdentity())
                || !entity.getOperation().name().equals(result.operation())
                || !entity.getDefinitionRevision().equals(result.definitionRevision())
                || !entity.getSourceTreeFingerprintSha256().equals(result.sourceTreeFingerprintSha256())
                || result.valuesExposed()
                || result.durationMillis() < 0) {
            throw new RemoteWorkerException("Validation ownership response is incomplete or conflicting", 409);
        }
        ValidationOperationStatus status;
        try {
            status = ValidationOperationStatus.valueOf(result.status());
        } catch (IllegalArgumentException exception) {
            throw new RemoteWorkerException("Validation returned an unsupported state", 409);
        }
        if (status == ValidationOperationStatus.RUNNING) {
            return;
        }
        if (result.artifactManifestSha256() == null
                || !result.artifactManifestSha256().matches("^[0-9a-f]{64}$")
                || (status == ValidationOperationStatus.SUCCEEDED
                    && (result.exitCode() == null || result.exitCode() != 0))
                || (status == ValidationOperationStatus.FAILED
                    && (result.exitCode() == null || result.exitCode() == 0))) {
            throw new RemoteWorkerException("Validation terminal result is inconsistent", 409);
        }
        Instant finishedAt = Instant.now();
        entity.setStatus(status);
        entity.setExitCode(result.exitCode());
        entity.setDurationMillis(result.durationMillis());
        entity.setArtifactManifestSha256(result.artifactManifestSha256());
        entity.setSummary(safeSummary(result.summary()));
        entity.setFinishedAt(finishedAt);
        entity.setUpdatedAt(finishedAt);
        validationOperationRepository.save(entity);
    }

    private void projectAcceptance(Long sessionId, String fingerprint) {
        List<ValidationOperationEntity> results =
                validationOperationRepository
                        .findByWorkSessionIdAndSourceTreeFingerprintSha256OrderByOperationAsc(
                                sessionId,
                                fingerprint);
        Map<ValidationOperationKind, ValidationOperationEntity> byKind =
                new EnumMap<>(ValidationOperationKind.class);
        results.forEach(result -> byKind.put(result.getOperation(), result));
        String projection = projectionSha256(results);

        ValidationOperationEntity failed = results.stream()
                .filter(result -> result.getStatus() == ValidationOperationStatus.FAILED
                        || result.getStatus() == ValidationOperationStatus.BLOCKED)
                .findFirst()
                .orElse(null);
        if (failed != null) {
            acceptanceService.markBlocked(
                    sessionId,
                    fingerprint,
                    projection,
                    profileRevision(),
                    failed.getOperation().name(),
                    "Retry the exact failed or unavailable validation operation");
            return;
        }
        ValidationOperationKind missing = java.util.Arrays.stream(ValidationOperationKind.values())
                .filter(kind -> !byKind.containsKey(kind)
                        || byKind.get(kind).getStatus() != ValidationOperationStatus.SUCCEEDED)
                .findFirst()
                .orElse(null);
        if (missing != null) {
            acceptanceService.markBlocked(
                    sessionId,
                    fingerprint,
                    projection,
                    profileRevision(),
                    missing.name(),
                    "Run the missing required validation operation");
            return;
        }
        acceptanceService.markValidated(
                sessionId,
                fingerprint,
                projection,
                profileRevision());
    }

    private String projectionSha256(Long sessionId, String fingerprint) {
        return projectionSha256(
                validationOperationRepository
                        .findByWorkSessionIdAndSourceTreeFingerprintSha256OrderByOperationAsc(
                                sessionId,
                                fingerprint));
    }

    private String projectionSha256(List<ValidationOperationEntity> results) {
        Map<ValidationOperationKind, ValidationOperationStatus> states =
                new EnumMap<>(ValidationOperationKind.class);
        results.forEach(result -> states.put(result.getOperation(), result.getStatus()));
        StringBuilder canonical = new StringBuilder(profileRevision());
        for (ValidationOperationKind kind : ValidationOperationKind.values()) {
            canonical.append('\0').append(kind.name()).append('=')
                    .append(states.getOrDefault(kind, null));
        }
        return sha256(canonical.toString());
    }

    private String profileRevision() {
        return "atenea-required-validation-v1";
    }

    private void requireExactIdleSession(WorkSessionEntity session) {
        if (session.getStatus() != WorkSessionStatus.OPEN
                || session.getExecutionTarget() != ExecutionTarget.REMOTE
                || !ProjectCodexIdentity.hasCanonicalSourceObservation(session)
                || agentRunRepository.existsBySessionIdAndStatusIn(
                        session.getId(),
                        AgentRunStatus.nonTerminalStatuses())) {
            throw new WorkSessionOperationBlockedException(
                    "Validation requires an idle, exactly owned current Atenea WorkSession");
        }
    }

    private void validateSourceObservation(
            WorkSessionEntity session,
            RemoteWorkerClient.SourceTreeFingerprint source
    ) {
        if (!"observed".equals(source.state())
                || !session.getRemoteSessionId().toString().equals(source.sessionId())
                || !session.getWorkspaceIdentity().equals(source.workspaceIdentity())
                || !ProjectCodexIdentity.PROJECT_IDENTITY.equals(source.projectId())
                || !session.getCanonicalSourceCommit().equals(source.headCommit())
                || source.fingerprintSha256() == null
                || !source.fingerprintSha256().matches("^[0-9a-f]{64}$")
                || source.valuesExposed()) {
            throw new WorkSessionOperationBlockedException(
                    "Worker source tree observation failed closed");
        }
    }

    private String safeSummary(String value) {
        if (value == null || value.isBlank()) {
            return "Validation finished without a summary";
        }
        String normalized = value.replaceAll("[\\r\\n\\t]+", " ").trim();
        return normalized.substring(0, Math.min(normalized.length(), 500));
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private ValidationOperationResponse response(ValidationOperationEntity entity) {
        return new ValidationOperationResponse(
                entity.getId(),
                entity.getWorkSession().getId(),
                entity.getOperation(),
                entity.getStatus(),
                entity.getSourceTreeFingerprintSha256(),
                entity.getDefinitionRevision(),
                entity.getExitCode(),
                entity.getDurationMillis(),
                entity.getArtifactManifestSha256(),
                entity.getSummary(),
                entity.getStartedAt(),
                entity.getFinishedAt());
    }
}
