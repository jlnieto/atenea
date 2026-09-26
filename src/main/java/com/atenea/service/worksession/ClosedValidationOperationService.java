package com.atenea.service.worksession;

import com.atenea.api.worksession.ValidationOperationResponse;
import com.atenea.api.worksession.DevelopmentChangeValidationResponse;
import com.atenea.persistence.developmentchange.DevelopmentChangeEntity;
import com.atenea.persistence.developmentchange.DevelopmentChangeProjectionState;
import com.atenea.persistence.developmentchange.DevelopmentChangeSourceState;
import com.atenea.persistence.developmentchange.DevelopmentChangeStatus;
import com.atenea.persistence.developmentchange.DevelopmentChangeWorkspaceState;
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

    @Transactional
    public DevelopmentChangeValidationResponse advanceDevelopmentChange(Long sessionId) {
        WorkSessionEntity session = workSessionRepository
                .findLockedWithProjectAndDevelopmentChangeById(sessionId)
                .orElseThrow(() -> new WorkSessionNotFoundException(sessionId));
        DevelopmentChangeEntity change = requireExactDevelopmentChange(session);
        RemoteWorkerClient.SourceTreeFingerprint source = remoteWorkerClient.fingerprintSourceTree(session);
        validateSourceObservation(session, source);
        if (!source.fingerprintSha256().equals(change.getSourceFingerprintSha256())) {
            acceptanceService.observeSourceTree(sessionId, source.fingerprintSha256());
            change.setValidationState(DevelopmentChangeProjectionState.STALE);
            return developmentChangeResponse(
                    change,
                    List.of(),
                    "STALE",
                    null,
                    "El código cambió después de la última observación durable.");
        }
        acceptanceService.observeSourceTree(sessionId, source.fingerprintSha256());

        List<ValidationOperationEntity> results = validationOperationRepository
                .findByWorkSessionIdAndSourceTreeFingerprintSha256OrderByStartedAtAscIdAsc(
                        sessionId, source.fingerprintSha256());
        Map<ValidationOperationKind, ValidationOperationEntity> latest = latestByOperation(results);
        ValidationOperationEntity running = latest.values().stream()
                .filter(result -> result.getStatus() == ValidationOperationStatus.RUNNING)
                .findFirst()
                .orElse(null);
        if (running != null) {
            try {
                applyDurableResult(
                        running,
                        session,
                        remoteWorkerClient.inspectValidation(session, running.getId().toString()));
            } catch (RemoteWorkerException exception) {
                return developmentChangeResponse(
                        change, results, "RUNNING", running.getOperation().name(),
                        "La validación sigue ejecutándose; su estado durable se reconciliará.");
            }
            results = validationOperationRepository
                    .findByWorkSessionIdAndSourceTreeFingerprintSha256OrderByStartedAtAscIdAsc(
                            sessionId, source.fingerprintSha256());
            latest = latestByOperation(results);
            if (running.getStatus() == ValidationOperationStatus.RUNNING) {
                return developmentChangeResponse(
                        change, results, "RUNNING", running.getOperation().name(),
                        running.getSummary());
            }
        }

        ValidationOperationEntity failed = latest.values().stream()
                .filter(result -> result.getStatus() == ValidationOperationStatus.FAILED
                        || result.getStatus() == ValidationOperationStatus.BLOCKED)
                .findFirst()
                .orElse(null);
        if (failed != null && (running != null
                || failed.getStatus() == ValidationOperationStatus.BLOCKED)) {
            change.setValidationState(DevelopmentChangeProjectionState.BLOCKED);
            projectAcceptance(sessionId, source.fingerprintSha256());
            return developmentChangeResponse(
                    change, results, "FAILED", failed.getOperation().name(), failed.getSummary());
        }

        ValidationOperationKind next = failed == null ? null : failed.getOperation();
        if (failed == null) {
            for (ValidationOperationKind kind : ValidationOperationKind.values()) {
                if (!latest.containsKey(kind)) {
                    next = kind;
                    break;
                }
            }
        }
        if (next == null) {
            projectAcceptance(sessionId, source.fingerprintSha256());
            change.setValidationState(DevelopmentChangeProjectionState.CURRENT);
            return developmentChangeResponse(
                    change, results, "SUCCEEDED", null,
                    "Todas las validaciones obligatorias están vigentes.");
        }

        ValidationOperationEntity entity = newValidationEntity(
                session, next, source.fingerprintSha256(), failed == null ? null : failed.getId());
        validationOperationRepository.saveAndFlush(entity);
        acceptanceService.markValidating(
                sessionId,
                source.fingerprintSha256(),
                projectionSha256(sessionId, source.fingerprintSha256()),
                profileRevision());
        try {
            applyDurableResult(
                    entity,
                    session,
                    remoteWorkerClient.startValidation(
                            session, next, source.fingerprintSha256(), entity.getId().toString()));
        } catch (RemoteWorkerException exception) {
            entity.setStatus(ValidationOperationStatus.BLOCKED);
            entity.setExitCode(null);
            entity.setDurationMillis(0L);
            entity.setArtifactManifestSha256(null);
            entity.setSummary("Worker validation authority was unavailable");
            entity.setFinishedAt(Instant.now());
            entity.setUpdatedAt(entity.getFinishedAt());
            validationOperationRepository.save(entity);
            change.setValidationState(DevelopmentChangeProjectionState.BLOCKED);
        }
        results = validationOperationRepository
                .findByWorkSessionIdAndSourceTreeFingerprintSha256OrderByStartedAtAscIdAsc(
                        sessionId, source.fingerprintSha256());
        if (entity.getStatus() == ValidationOperationStatus.FAILED
                || entity.getStatus() == ValidationOperationStatus.BLOCKED) {
            projectAcceptance(sessionId, source.fingerprintSha256());
        }
        String state = entity.getStatus() == ValidationOperationStatus.RUNNING
                ? "RUNNING"
                : entity.getStatus() == ValidationOperationStatus.SUCCEEDED
                ? "ADVANCING"
                : "FAILED";
        return developmentChangeResponse(
                change, results, state, entity.getOperation().name(), entity.getSummary());
    }

    private ValidationOperationEntity newValidationEntity(
            WorkSessionEntity session,
            ValidationOperationKind operation,
            String fingerprint,
            UUID retryOf
    ) {
        String identityMaterial = "development-change\0" + session.getRemoteSessionId() + "\0"
                + session.getWorkspaceIdentity() + "\0" + operation.name() + "\0"
                + operation.definitionRevision() + "\0" + fingerprint;
        String identity = sha256(retryOf == null
                ? identityMaterial : identityMaterial + "\0retry-of\0" + retryOf);
        Instant now = Instant.now();
        ValidationOperationEntity entity = new ValidationOperationEntity();
        entity.setId(UUID.randomUUID());
        entity.setWorkSession(session);
        entity.setOperation(operation);
        entity.setStatus(ValidationOperationStatus.RUNNING);
        entity.setSourceTreeFingerprintSha256(fingerprint);
        entity.setDefinitionRevision(operation.definitionRevision());
        entity.setIdentitySha256(identity);
        entity.setSummary("Bounded validation is queued");
        entity.setStartedAt(now);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        return entity;
    }

    private void applyDurableResult(
            ValidationOperationEntity entity,
            WorkSessionEntity session,
            RemoteWorkerClient.DurableValidationResult result
    ) {
        if (result.schemaVersion() != 1
                || !"closed-validation-broker/v1".equals(result.protocolVersion())
                || !entity.getId().toString().equals(result.operationId())
                || !session.getRemoteSessionId().toString().equals(result.sessionId())
                || !session.getWorkspaceIdentity().equals(result.workspaceIdentity())
                || !ProjectCodexIdentity.PROJECT_IDENTITY.equals(result.projectId())
                || !entity.getOperation().name().equals(result.validationDefinition())
                || !entity.getDefinitionRevision().equals(result.definitionRevision())
                || !entity.getSourceTreeFingerprintSha256().equals(
                        result.sourceTreeFingerprintSha256())
                || result.valuesExposed()
                || result.durationMillis() < 0) {
            throw new RemoteWorkerException(
                    "Durable validation ownership response is incomplete or conflicting", 409);
        }
        ValidationOperationStatus status = switch (result.state()) {
            case "QUEUED", "RUNNING", "CANCELLING", "RECONCILING" ->
                    ValidationOperationStatus.RUNNING;
            case "SUCCEEDED" -> ValidationOperationStatus.SUCCEEDED;
            case "CANDIDATE_FAILED" -> ValidationOperationStatus.FAILED;
            case "INFRASTRUCTURE_FAILED", "POLICY_FAILED", "VALIDATION_FAILED",
                    "OWNERSHIP_FAILED", "CANCELLED" -> ValidationOperationStatus.BLOCKED;
            default -> throw new RemoteWorkerException(
                    "Validation returned an unsupported durable state", 409);
        };
        Instant updatedAt = Instant.now();
        entity.setStatus(status);
        if (status == ValidationOperationStatus.RUNNING) {
            entity.setExitCode(null);
            entity.setDurationMillis(null);
            entity.setArtifactManifestSha256(null);
            entity.setFinishedAt(null);
        } else {
            entity.setExitCode(result.exitCode());
            entity.setDurationMillis(result.durationMillis());
            entity.setArtifactManifestSha256(result.artifactManifestSha256());
            entity.setFinishedAt(updatedAt);
        }
        entity.setSummary(safeSummary(result.summary()));
        entity.setUpdatedAt(updatedAt);
        validationOperationRepository.save(entity);
    }

    private DevelopmentChangeEntity requireExactDevelopmentChange(WorkSessionEntity session) {
        DevelopmentChangeEntity change = session.getDevelopmentChange();
        if (change == null
                || change.getStatus() != DevelopmentChangeStatus.OPEN
                || change.getWorkspaceState() != DevelopmentChangeWorkspaceState.READY
                || change.getSourceState() != DevelopmentChangeSourceState.DIRTY
                || !change.getWorkspaceIdentity().equals(session.getWorkspaceIdentity())
                || !change.getWorkspaceBranch().equals(session.getWorkspaceBranch())
                || !change.getSelectedWorkerId().equals(session.getSelectedWorkerId())) {
            throw new WorkSessionOperationBlockedException(
                    "Validation requires an exact OPEN/READY/DIRTY DevelopmentChange");
        }
        requireExactIdleSession(session);
        return change;
    }

    private DevelopmentChangeValidationResponse developmentChangeResponse(
            DevelopmentChangeEntity change,
            List<ValidationOperationEntity> results,
            String state,
            String currentOperation,
            String summary
    ) {
        int passed = (int) latestByOperation(results).values().stream()
                .filter(result -> result.getStatus() == ValidationOperationStatus.SUCCEEDED)
                .count();
        return new DevelopmentChangeValidationResponse(
                change.getChangeKey(),
                change.getSourceRevision(),
                change.getSourceFingerprintSha256(),
                change.getValidationState(),
                state,
                currentOperation,
                passed,
                ValidationOperationKind.values().length,
                safeSummary(summary));
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
                        .findByWorkSessionIdAndSourceTreeFingerprintSha256OrderByStartedAtAscIdAsc(
                                sessionId,
                                fingerprint);
        Map<ValidationOperationKind, ValidationOperationEntity> byKind = latestByOperation(results);
        String projection = projectionSha256(results);

        ValidationOperationEntity failed = byKind.values().stream()
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
                        .findByWorkSessionIdAndSourceTreeFingerprintSha256OrderByStartedAtAscIdAsc(
                                sessionId,
                                fingerprint));
    }

    private String projectionSha256(List<ValidationOperationEntity> results) {
        Map<ValidationOperationKind, ValidationOperationStatus> states =
                new EnumMap<>(ValidationOperationKind.class);
        latestByOperation(results).forEach((kind, result) -> states.put(kind, result.getStatus()));
        StringBuilder canonical = new StringBuilder(profileRevision());
        for (ValidationOperationKind kind : ValidationOperationKind.values()) {
            canonical.append('\0').append(kind.name()).append('=')
                    .append(states.getOrDefault(kind, null));
        }
        return sha256(canonical.toString());
    }

    private Map<ValidationOperationKind, ValidationOperationEntity> latestByOperation(
            List<ValidationOperationEntity> results
    ) {
        Map<ValidationOperationKind, ValidationOperationEntity> latest =
                new EnumMap<>(ValidationOperationKind.class);
        results.forEach(result -> latest.put(result.getOperation(), result));
        return latest;
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
