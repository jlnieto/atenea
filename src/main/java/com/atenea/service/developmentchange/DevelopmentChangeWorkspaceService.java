package com.atenea.service.developmentchange;

import com.atenea.api.developmentchange.DevelopmentChangeActionResponse;
import com.atenea.api.developmentchange.DevelopmentChangeWorkspaceOperationResponse;
import com.atenea.auth.AuthenticatedOperator;
import com.atenea.developmentchange.DevelopmentChangeIdentity;
import com.atenea.developmentchange.DevelopmentChangeProperties;
import com.atenea.developmentchange.DevelopmentChangeSourceProjection;
import com.atenea.persistence.auth.OperatorEntity;
import com.atenea.persistence.auth.OperatorRepository;
import com.atenea.persistence.developmentchange.DevelopmentChangeEntity;
import com.atenea.persistence.developmentchange.DevelopmentChangeProjectionState;
import com.atenea.persistence.developmentchange.DevelopmentChangeRepository;
import com.atenea.persistence.developmentchange.DevelopmentChangeSourceState;
import com.atenea.persistence.developmentchange.DevelopmentChangeStatus;
import com.atenea.persistence.developmentchange.DevelopmentChangeWorkspaceOperationEntity;
import com.atenea.persistence.developmentchange.DevelopmentChangeWorkspaceOperationKind;
import com.atenea.persistence.developmentchange.DevelopmentChangeWorkspaceOperationRepository;
import com.atenea.persistence.developmentchange.DevelopmentChangeWorkspaceOperationState;
import com.atenea.persistence.developmentchange.DevelopmentChangeWorkspaceState;
import com.atenea.persistence.project.ProjectEntity;
import com.atenea.persistence.project.ProjectRepository;
import com.atenea.remoteworker.DevelopmentChangeWorkspaceCommand;
import com.atenea.remoteworker.DevelopmentChangeWorkspaceGateway;
import com.atenea.remoteworker.DevelopmentChangeWorkspaceObservation;
import com.atenea.remoteworker.ProjectCodexIdentity;
import com.atenea.remoteworker.RemoteWorkerException;
import com.atenea.remoteworker.RemoteWorkerFailureCategory;
import com.atenea.remoteworker.RemoteWorkerProperties;
import com.atenea.service.v2control.V2AuditFact;
import com.atenea.service.v2control.V2AuditOutboxService;
import com.atenea.service.worksession.WorkSessionProjectNotFoundException;
import com.atenea.v2.control.V2FailureCategory;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class DevelopmentChangeWorkspaceService {

    private static final Set<DevelopmentChangeWorkspaceOperationState> ACTIVE_STATES =
            Set.of(
                    DevelopmentChangeWorkspaceOperationState.REQUESTED,
                    DevelopmentChangeWorkspaceOperationState.DISPATCHED);
    private static final String TRANSPORT_UNCERTAIN =
            "DEVELOPMENT_CHANGE_WORKER_RESPONSE_UNCERTAIN";
    private static final String FOREIGN_RESOURCE =
            "DEVELOPMENT_CHANGE_FOREIGN_RESOURCE_REFUSED";

    private final ProjectRepository projectRepository;
    private final OperatorRepository operatorRepository;
    private final DevelopmentChangeRepository changeRepository;
    private final DevelopmentChangeWorkspaceOperationRepository operationRepository;
    private final DevelopmentChangePolicy policy;
    private final DevelopmentChangeProperties properties;
    private final RemoteWorkerProperties remoteWorkerProperties;
    private final DevelopmentChangeWorkspaceGateway gateway;
    private final V2AuditOutboxService auditService;
    private final TransactionTemplate transaction;

    public DevelopmentChangeWorkspaceService(
            ProjectRepository projectRepository,
            OperatorRepository operatorRepository,
            DevelopmentChangeRepository changeRepository,
            DevelopmentChangeWorkspaceOperationRepository operationRepository,
            DevelopmentChangePolicy policy,
            DevelopmentChangeProperties properties,
            RemoteWorkerProperties remoteWorkerProperties,
            DevelopmentChangeWorkspaceGateway gateway,
            V2AuditOutboxService auditService,
            PlatformTransactionManager transactionManager) {
        this.projectRepository = projectRepository;
        this.operatorRepository = operatorRepository;
        this.changeRepository = changeRepository;
        this.operationRepository = operationRepository;
        this.policy = policy;
        this.properties = properties;
        this.remoteWorkerProperties = remoteWorkerProperties;
        this.gateway = gateway;
        this.auditService = auditService;
        transaction = new TransactionTemplate(transactionManager);
    }

    public DevelopmentChangeWorkspaceOperationResponse provision(
            AuthenticatedOperator actor,
            Long projectId,
            UUID changeKey,
            UUID idempotencyKey) {
        return execute(actor == null ? null : actor.operatorId(), projectId, changeKey,
                DevelopmentChangeWorkspaceOperationKind.PROVISION,
                idempotencyKey, null);
    }

    public DevelopmentChangeWorkspaceOperationResponse inspect(
            AuthenticatedOperator actor,
            Long projectId,
            UUID changeKey,
            UUID idempotencyKey) {
        return execute(actor == null ? null : actor.operatorId(), projectId, changeKey,
                DevelopmentChangeWorkspaceOperationKind.INSPECT,
                idempotencyKey, null);
    }

    public DevelopmentChangeWorkspaceOperationResponse reconcile(
            AuthenticatedOperator actor,
            Long projectId,
            UUID changeKey) {
        return execute(actor == null ? null : actor.operatorId(), projectId, changeKey,
                DevelopmentChangeWorkspaceOperationKind.RECONCILE,
                null, null);
    }

    public int reconcilePersistedAfterStartup() {
        if (!properties.isWorkspaceOperationsEnabled()
                || !properties.isWorkspaceReconciliationEnabled()
                || !remoteWorkerProperties.isEnabled()) {
            return 0;
        }
        List<UUID> requested = operationRepository
                .findAllByStateInOrderByRequestedAtAsc(
                        Set.of(DevelopmentChangeWorkspaceOperationState.REQUESTED))
                .stream()
                .map(DevelopmentChangeWorkspaceOperationEntity::getOperationId)
                .toList();
        requested.forEach(this::failInterruptedBeforeDispatch);

        List<UUID> dispatched = operationRepository
                .findAllByStateInOrderByRequestedAtAsc(
                        Set.of(DevelopmentChangeWorkspaceOperationState.DISPATCHED))
                .stream()
                .map(DevelopmentChangeWorkspaceOperationEntity::getOperationId)
                .toList();
        dispatched.forEach(this::markRestartUncertain);

        List<RestartTarget> uncertain = operationRepository
                .findAllUnreconciledUncertain()
                .stream()
                .map(operation -> new RestartTarget(
                        operation.getOperator().getId(),
                        operation.getProject().getId(),
                        operation.getDevelopmentChange().getChangeKey(),
                        operation.getOperationId()))
                .toList();
        int completed = 0;
        for (RestartTarget target : uncertain) {
            try {
                execute(
                        target.operatorId(),
                        target.projectId(),
                        target.changeKey(),
                        DevelopmentChangeWorkspaceOperationKind.RECONCILE,
                        null,
                        target.predecessorOperationId());
                completed++;
            } catch (RuntimeException ignored) {
                // A later explicit inspection can diagnose the durable state safely.
            }
        }
        return completed;
    }

    private DevelopmentChangeWorkspaceOperationResponse execute(
            Long operatorId,
            Long projectId,
            UUID changeKey,
            DevelopmentChangeWorkspaceOperationKind kind,
            UUID requestedIdempotencyKey,
            UUID exactPredecessor) {
        StartOutcome start = transaction.execute(ignored -> start(
                operatorId, projectId, changeKey, kind,
                requestedIdempotencyKey, exactPredecessor));
        if (start == null) {
            throw new IllegalStateException("Workspace operation produced no durable intent");
        }
        if (start.rejection() != null) {
            throw start.rejection();
        }
        if (start.replay() != null) {
            return start.replay();
        }
        markDispatched(start.command().operationId());
        try {
            DevelopmentChangeWorkspaceObservation observation = gateway.execute(start.command());
            return completeObservation(start.command().operationId(), observation);
        } catch (RemoteWorkerException failure) {
            return completeRemoteFailure(start.command().operationId(), failure);
        } catch (RuntimeException failure) {
            return completeRemoteFailure(
                    start.command().operationId(),
                    new RemoteWorkerException(
                            "Development change worker result is uncertain", failure));
        }
    }

    private StartOutcome start(
            Long operatorId,
            Long projectId,
            UUID changeKey,
            DevelopmentChangeWorkspaceOperationKind kind,
            UUID requestedIdempotencyKey,
            UUID exactPredecessor) {
        ProjectEntity project = requireProject(projectId);
        OperatorEntity actor = requireActor(operatorId);
        String preliminaryRequest = fingerprint(
                kind, operatorId, projectId, changeKey, requestedIdempotencyKey,
                exactPredecessor);
        String preliminaryTarget = fingerprint("WORKSPACE", projectId, changeKey);
        if (changeKey == null
                || (kind != DevelopmentChangeWorkspaceOperationKind.RECONCILE
                    && requestedIdempotencyKey == null)) {
            return rejected(actor, project, preliminaryRequest, preliminaryTarget,
                    V2FailureCategory.VALIDATION,
                    "DEVELOPMENT_CHANGE_WORKSPACE_REQUEST_INVALID",
                    "Se requieren changeKey e idempotency key válidos.");
        }
        DevelopmentChangeEntity change = changeRepository
                .findByChangeKeyForUpdate(changeKey)
                .orElse(null);
        if (change == null || change.getProject() == null
                || !projectId.equals(change.getProject().getId())) {
            return rejected(actor, project, preliminaryRequest, preliminaryTarget,
                    V2FailureCategory.OWNERSHIP,
                    "DEVELOPMENT_CHANGE_WORKSPACE_OWNERSHIP_MISMATCH",
                    "El cambio no pertenece exactamente al proyecto solicitado.");
        }

        DevelopmentChangeWorkspaceOperationEntity predecessor = null;
        UUID idempotencyKey = requestedIdempotencyKey;
        if (kind == DevelopmentChangeWorkspaceOperationKind.RECONCILE) {
            predecessor = exactPredecessor == null
                    ? operationRepository
                            .findFirstByDevelopmentChangeIdAndStateOrderByRequestedAtDesc(
                                    change.getId(),
                                    DevelopmentChangeWorkspaceOperationState.UNCERTAIN)
                            .orElse(null)
                    : operationRepository.findByOperationIdForUpdate(exactPredecessor)
                            .filter(operation -> Objects.equals(
                                    operation.getDevelopmentChange().getId(), change.getId()))
                            .filter(operation -> operation.getState()
                                    == DevelopmentChangeWorkspaceOperationState.UNCERTAIN)
                            .orElse(null);
            if (predecessor == null) {
                return rejected(actor, project, preliminaryRequest,
                        changeTargetFingerprint(change),
                        V2FailureCategory.VALIDATION,
                        "DEVELOPMENT_CHANGE_NO_UNCERTAIN_OPERATION",
                        "No existe una operación incierta exacta que reconciliar.");
            }
            idempotencyKey = reconciliationKey(predecessor.getOperationId());
        }

        String requestFingerprint = fingerprint(
                kind, actor.getId(), projectId, changeKey, idempotencyKey,
                predecessor == null ? null : predecessor.getOperationId());
        String targetFingerprint = changeTargetFingerprint(change);
        DevelopmentChangeWorkspaceOperationEntity existing = operationRepository
                .findByOperationKindAndIdempotencyKey(kind, idempotencyKey)
                .orElse(null);
        if (existing != null) {
            if (!Objects.equals(existing.getOperator().getId(), actor.getId())
                    || !Objects.equals(existing.getProject().getId(), projectId)
                    || !Objects.equals(existing.getDevelopmentChange().getId(), change.getId())
                    || !Objects.equals(existing.getRequestFingerprintSha256(), requestFingerprint)) {
                return rejected(actor, project, requestFingerprint, targetFingerprint,
                        V2FailureCategory.OWNERSHIP,
                        "DEVELOPMENT_CHANGE_WORKSPACE_IDEMPOTENCY_CONFLICT",
                        "La idempotency key pertenece a otra operación exacta.");
            }
            return StartOutcome.replay(response(existing, change, true));
        }

        DevelopmentChangePolicy.Decision decision = policy.decideWorkspace(
                projectId, kind == DevelopmentChangeWorkspaceOperationKind.RECONCILE);
        if (!decision.allowed()) {
            return rejected(actor, project, requestFingerprint, targetFingerprint,
                    V2FailureCategory.POLICY,
                    decision.failureCode(),
                    "Las operaciones de workspace no están habilitadas para este proyecto.");
        }
        if (!remoteWorkerProperties.isEnabled()) {
            return rejected(actor, project, requestFingerprint, targetFingerprint,
                    V2FailureCategory.POLICY,
                    "DEVELOPMENT_CHANGE_REMOTE_WORKER_DISABLED",
                    "El worker remoto permanece deshabilitado.");
        }
        if (!exactServerOwnedIdentity(change)
                || decision.projectPolicyRevision() != change.getProjectPolicyRevision()) {
            return rejected(actor, project, requestFingerprint, targetFingerprint,
                    V2FailureCategory.OWNERSHIP,
                    "DEVELOPMENT_CHANGE_WORKSPACE_POLICY_DRIFT",
                    "La identidad o policy server-owned ya no coincide exactamente.");
        }
        if (change.getStatus() != DevelopmentChangeStatus.OPEN
                && change.getStatus() != DevelopmentChangeStatus.PAUSED) {
            return rejected(actor, project, requestFingerprint, targetFingerprint,
                    V2FailureCategory.VALIDATION,
                    "DEVELOPMENT_CHANGE_WORKSPACE_STATE_INVALID",
                    "El estado del cambio no permite operar su workspace.");
        }
        if (kind == DevelopmentChangeWorkspaceOperationKind.PROVISION
                && change.getWorkspaceState()
                    != DevelopmentChangeWorkspaceState.NOT_PROVISIONED) {
            return rejected(actor, project, requestFingerprint, targetFingerprint,
                    V2FailureCategory.VALIDATION,
                    "DEVELOPMENT_CHANGE_WORKSPACE_ALREADY_OBSERVED",
                    "Provisionar sólo es válido antes de observar un workspace.");
        }
        if (operationRepository.existsByDevelopmentChangeIdAndStateIn(
                change.getId(), ACTIVE_STATES)) {
            return rejected(actor, project, requestFingerprint, targetFingerprint,
                    V2FailureCategory.OWNERSHIP,
                    "DEVELOPMENT_CHANGE_WORKSPACE_OPERATION_ACTIVE",
                    "Ya existe una operación durable de workspace en curso.");
        }

        Instant now = Instant.now();
        UUID operationId = UUID.randomUUID();
        String canonicalCommit = currentCanonicalCommit(change);
        DevelopmentChangeWorkspaceOperationEntity operation =
                new DevelopmentChangeWorkspaceOperationEntity();
        operation.setOperationId(operationId);
        operation.setOperator(actor);
        operation.setProject(project);
        operation.setDevelopmentChange(change);
        operation.setIdempotencyKey(idempotencyKey);
        operation.setOperationKind(kind);
        operation.setPredecessorOperationId(
                predecessor == null ? null : predecessor.getOperationId());
        operation.setRequestFingerprintSha256(requestFingerprint);
        operation.setTargetFingerprintSha256(targetFingerprint);
        operation.setExpectedSourceRevision(change.getSourceRevision());
        operation.setExpectedSourceFingerprintSha256(
                change.getSourceFingerprintSha256());
        operation.setExpectedCanonicalCommit(canonicalCommit);
        operation.setState(DevelopmentChangeWorkspaceOperationState.REQUESTED);
        operation.setRevision(0);
        operation.setRequestedAt(now);
        operation.setUpdatedAt(now);
        operationRepository.saveAndFlush(operation);

        DevelopmentChangeWorkspaceCommand command =
                new DevelopmentChangeWorkspaceCommand(
                        operationId,
                        idempotencyKey,
                        kind,
                        predecessor == null ? null : predecessor.getOperationId(),
                        change.getChangeKey(),
                        project.getId(),
                        ProjectCodexIdentity.PROJECT_IDENTITY,
                        ProjectCodexIdentity.REPOSITORY,
                        ProjectCodexIdentity.BRANCH,
                        change.getBaseCommit(),
                        canonicalCommit,
                        change.getWorkspaceBranch(),
                        change.getWorkspaceIdentity(),
                        change.getSelectedWorkerId(),
                        change.getSourceRevision(),
                        change.getSourceFingerprintSha256());
        return StartOutcome.dispatch(command);
    }

    private void markDispatched(UUID operationId) {
        transaction.executeWithoutResult(ignored -> {
            DevelopmentChangeWorkspaceOperationEntity operation = operationRepository
                    .findByOperationIdForUpdate(operationId)
                    .orElseThrow(() -> new IllegalStateException(
                            "Durable workspace operation disappeared"));
            if (operation.getState() != DevelopmentChangeWorkspaceOperationState.REQUESTED) {
                throw new IllegalStateException("Workspace operation was not requested");
            }
            Instant now = Instant.now();
            operation.setState(DevelopmentChangeWorkspaceOperationState.DISPATCHED);
            operation.setRevision(1);
            operation.setDispatchedAt(now);
            operation.setUpdatedAt(now);
            operationRepository.saveAndFlush(operation);
        });
    }

    private DevelopmentChangeWorkspaceOperationResponse completeObservation(
            UUID operationId,
            DevelopmentChangeWorkspaceObservation observation) {
        return Objects.requireNonNull(transaction.execute(ignored -> {
            DevelopmentChangeWorkspaceOperationEntity operation = lockedDispatched(operationId);
            DevelopmentChangeEntity change = changeRepository
                    .findByChangeKeyForUpdate(
                            operation.getDevelopmentChange().getChangeKey())
                    .orElseThrow();
            if (!exactOperationTarget(operation, change)) {
                return completeFailure(
                        operation,
                        change,
                        DevelopmentChangeWorkspaceOperationState.BLOCKED,
                        V2FailureCategory.OWNERSHIP,
                        "DEVELOPMENT_CHANGE_CHANGED_DURING_WORKSPACE_OPERATION");
            }
            if (observation.disposition()
                    == DevelopmentChangeWorkspaceObservation.Disposition.FOREIGN) {
                return completeFailure(
                        operation,
                        change,
                        DevelopmentChangeWorkspaceOperationState.BLOCKED,
                        V2FailureCategory.OWNERSHIP,
                        FOREIGN_RESOURCE);
            }
            if (observation.disposition()
                    == DevelopmentChangeWorkspaceObservation.Disposition.ABSENT) {
                boolean unexpected = change.getWorkspaceState()
                        == DevelopmentChangeWorkspaceState.READY;
                if (unexpected) {
                    return completeFailure(
                            operation,
                            change,
                            DevelopmentChangeWorkspaceOperationState.BLOCKED,
                            V2FailureCategory.OWNERSHIP,
                            "DEVELOPMENT_CHANGE_OWNED_RESOURCE_MISSING");
                }
                return completeSuccess(operation, change,
                        DevelopmentChangeWorkspaceState.NOT_PROVISIONED,
                        change.getSourceState(),
                        change.getSourceRevision(),
                        change.getSourceFingerprintSha256(),
                        currentCanonicalCommit(change),
                        null,
                        observation.ownershipFingerprintSha256());
            }
            return applyOwnedObservation(operation, change, observation);
        }));
    }

    private DevelopmentChangeWorkspaceOperationResponse applyOwnedObservation(
            DevelopmentChangeWorkspaceOperationEntity operation,
            DevelopmentChangeEntity change,
            DevelopmentChangeWorkspaceObservation observation) {
        DevelopmentChangeSourceProjection projection = new DevelopmentChangeSourceProjection(
                change.getSourceRevision(),
                change.getSourceFingerprintSha256(),
                currentCanonicalCommit(change));
        DevelopmentChangeSourceProjection.Transition transition = projection.observe(
                observation.sourceFingerprintSha256(),
                observation.canonicalCommit(),
                observation.workspaceDirty());
        DevelopmentChangeSourceState sourceState = switch (transition.sourceState()) {
            case CLEAN -> observation.workspaceDirty()
                    ? DevelopmentChangeSourceState.DIRTY
                    : DevelopmentChangeSourceState.CLEAN;
            case DIRTY -> DevelopmentChangeSourceState.DIRTY;
            case STALE -> DevelopmentChangeSourceState.STALE;
        };
        if (transition.invalidatesDownstream()) {
            change.setValidationState(staleIfCurrent(change.getValidationState()));
            change.setReviewState(staleIfCurrent(change.getReviewState()));
            change.setIntegrationState(staleIfCurrent(change.getIntegrationState()));
            change.setReleaseState(staleIfCurrent(change.getReleaseState()));
        }
        change.setSourceRevision(transition.sourceRevision());
        change.setSourceFingerprintSha256(observation.sourceFingerprintSha256());
        change.setSourceState(sourceState);
        change.setObservedCanonicalCommit(observation.canonicalCommit());
        String observationFingerprint = fingerprint(
                observation.requestFingerprintSha256(),
                observation.ownershipFingerprintSha256(),
                observation.canonicalCommit(),
                observation.sourceFingerprintSha256(),
                observation.workspaceDirty(),
                observation.retainedDraft());
        return completeSuccess(
                operation,
                change,
                DevelopmentChangeWorkspaceState.READY,
                sourceState,
                transition.sourceRevision(),
                observation.sourceFingerprintSha256(),
                observation.canonicalCommit(),
                observation.ownershipFingerprintSha256(),
                observationFingerprint);
    }

    private DevelopmentChangeWorkspaceOperationResponse completeRemoteFailure(
            UUID operationId,
            RemoteWorkerException failure) {
        return Objects.requireNonNull(transaction.execute(ignored -> {
            DevelopmentChangeWorkspaceOperationEntity operation = lockedDispatched(operationId);
            DevelopmentChangeEntity change = changeRepository
                    .findByChangeKeyForUpdate(
                            operation.getDevelopmentChange().getChangeKey())
                    .orElseThrow();
            boolean uncertain = failure.isCompatibleTransportFailure();
            V2FailureCategory category = mapCategory(failure.getCategory());
            String code = safeFailureCode(failure, uncertain);
            return completeFailure(
                    operation,
                    change,
                    uncertain
                            ? DevelopmentChangeWorkspaceOperationState.UNCERTAIN
                            : DevelopmentChangeWorkspaceOperationState.BLOCKED,
                    category,
                    code);
        }));
    }

    private DevelopmentChangeWorkspaceOperationResponse completeSuccess(
            DevelopmentChangeWorkspaceOperationEntity operation,
            DevelopmentChangeEntity change,
            DevelopmentChangeWorkspaceState workspaceState,
            DevelopmentChangeSourceState sourceState,
            long sourceRevision,
            String sourceFingerprint,
            String observedCanonicalCommit,
            String workspaceOwnershipFingerprint,
            String observationFingerprint) {
        Instant now = Instant.now();
        change.setWorkspaceState(workspaceState);
        change.setWorkspaceOperationRevision(
                Math.addExact(change.getWorkspaceOperationRevision(), 1));
        change.setWorkspaceObservationSha256(observationFingerprint);
        change.setWorkspaceOwnershipFingerprintSha256(workspaceOwnershipFingerprint);
        change.setWorkspaceUpdatedAt(now);
        change.setUpdatedAt(now);
        changeRepository.saveAndFlush(change);

        operation.setState(DevelopmentChangeWorkspaceOperationState.SUCCEEDED);
        terminalFields(
                operation, workspaceState, sourceState, sourceRevision,
                sourceFingerprint, observedCanonicalCommit, null, null, now);
        operationRepository.saveAndFlush(operation);
        audit(operation, "DEVELOPMENT_CHANGE_WORKSPACE_OPERATION_SUCCEEDED", null, null);
        return response(operation, change, false);
    }

    private DevelopmentChangeWorkspaceOperationResponse completeFailure(
            DevelopmentChangeWorkspaceOperationEntity operation,
            DevelopmentChangeEntity change,
            DevelopmentChangeWorkspaceOperationState state,
            V2FailureCategory category,
            String code) {
        Instant now = Instant.now();
        DevelopmentChangeWorkspaceState workspaceState = state
                == DevelopmentChangeWorkspaceOperationState.UNCERTAIN
                        ? DevelopmentChangeWorkspaceState.UNCERTAIN
                        : DevelopmentChangeWorkspaceState.BLOCKED;
        change.setWorkspaceState(workspaceState);
        change.setWorkspaceOperationRevision(
                Math.addExact(change.getWorkspaceOperationRevision(), 1));
        String observationFingerprint = fingerprint(
                operation.getOperationId(), state, code,
                change.getSourceRevision(), change.getSourceFingerprintSha256());
        change.setWorkspaceObservationSha256(observationFingerprint);
        change.setWorkspaceOwnershipFingerprintSha256(null);
        change.setWorkspaceUpdatedAt(now);
        change.setUpdatedAt(now);
        changeRepository.saveAndFlush(change);

        operation.setState(state);
        terminalFields(
                operation,
                workspaceState,
                change.getSourceState(),
                change.getSourceRevision(),
                change.getSourceFingerprintSha256(),
                currentCanonicalCommit(change),
                category,
                code,
                now);
        operationRepository.saveAndFlush(operation);
        audit(operation, "DEVELOPMENT_CHANGE_WORKSPACE_OPERATION_FAILED", category, code);
        return response(operation, change, false);
    }

    private void terminalFields(
            DevelopmentChangeWorkspaceOperationEntity operation,
            DevelopmentChangeWorkspaceState workspaceState,
            DevelopmentChangeSourceState sourceState,
            long sourceRevision,
            String sourceFingerprint,
            String observedCanonicalCommit,
            V2FailureCategory category,
            String code,
            Instant now) {
        operation.setRevision(2);
        operation.setResultWorkspaceState(workspaceState);
        operation.setResultSourceState(sourceState);
        operation.setResultSourceRevision(sourceRevision);
        operation.setResultSourceFingerprintSha256(sourceFingerprint);
        operation.setObservedCanonicalCommit(observedCanonicalCommit);
        operation.setFailureCategory(category);
        operation.setFailureCode(code);
        operation.setReceiptSha256(fingerprint(
                operation.getOperationId(),
                operation.getOperationKind(),
                operation.getPredecessorOperationId(),
                operation.getRequestFingerprintSha256(),
                operation.getTargetFingerprintSha256(),
                operation.getState(),
                2,
                workspaceState,
                sourceState,
                sourceRevision,
                sourceFingerprint,
                observedCanonicalCommit,
                category,
                code));
        operation.setCompletedAt(now);
        operation.setUpdatedAt(now);
    }

    private DevelopmentChangeWorkspaceOperationEntity lockedDispatched(UUID operationId) {
        DevelopmentChangeWorkspaceOperationEntity operation = operationRepository
                .findByOperationIdForUpdate(operationId)
                .orElseThrow(() -> new IllegalStateException(
                        "Durable workspace operation disappeared"));
        if (operation.getState() != DevelopmentChangeWorkspaceOperationState.DISPATCHED) {
            throw new IllegalStateException("Workspace operation is not dispatching");
        }
        return operation;
    }

    private void failInterruptedBeforeDispatch(UUID operationId) {
        try {
            markDispatched(operationId);
            transaction.executeWithoutResult(ignored -> {
                DevelopmentChangeWorkspaceOperationEntity operation =
                        lockedDispatched(operationId);
                DevelopmentChangeEntity change = changeRepository
                        .findByChangeKeyForUpdate(
                                operation.getDevelopmentChange().getChangeKey())
                        .orElseThrow();
                completeFailure(
                        operation,
                        change,
                        DevelopmentChangeWorkspaceOperationState.BLOCKED,
                        V2FailureCategory.TRANSPORT,
                        "DEVELOPMENT_CHANGE_INTERRUPTED_BEFORE_DISPATCH");
            });
        } catch (RuntimeException ignored) {
            // Durable state remains available for a later explicit diagnosis.
        }
    }

    private void markRestartUncertain(UUID operationId) {
        try {
            transaction.executeWithoutResult(ignored -> {
                DevelopmentChangeWorkspaceOperationEntity operation =
                        lockedDispatched(operationId);
                DevelopmentChangeEntity change = changeRepository
                        .findByChangeKeyForUpdate(
                                operation.getDevelopmentChange().getChangeKey())
                        .orElseThrow();
                completeFailure(
                        operation,
                        change,
                        DevelopmentChangeWorkspaceOperationState.UNCERTAIN,
                        V2FailureCategory.TRANSPORT,
                        "DEVELOPMENT_CHANGE_RESTART_RESPONSE_UNCERTAIN");
            });
        } catch (RuntimeException ignored) {
            // Durable state remains available for a later explicit diagnosis.
        }
    }

    private boolean exactServerOwnedIdentity(DevelopmentChangeEntity change) {
        try {
            new DevelopmentChangeIdentity(
                    change.getChangeKey(),
                    change.getProject().getId(),
                    change.getSelectedWorkerId(),
                    change.getWorkspaceBranch(),
                    change.getWorkspaceIdentity());
        } catch (IllegalArgumentException invalid) {
            return false;
        }
        return ProjectCodexIdentity.matches(change.getProject())
                && ProjectCodexIdentity.WORKER_ID.equals(change.getSelectedWorkerId())
                && ProjectCodexIdentity.WORKER_ID.equals(remoteWorkerProperties.getWorkerId())
                && ("refs/heads/" + ProjectCodexIdentity.BRANCH).equals(change.getBaseRef())
                && exactGitObject(change.getBaseCommit())
                && exactGitObject(currentCanonicalCommit(change))
                && sha256(change.getSourceFingerprintSha256());
    }

    private boolean exactOperationTarget(
            DevelopmentChangeWorkspaceOperationEntity operation,
            DevelopmentChangeEntity change) {
        return Objects.equals(operation.getProject().getId(), change.getProject().getId())
                && Objects.equals(
                        operation.getDevelopmentChange().getId(), change.getId())
                && operation.getExpectedSourceRevision() == change.getSourceRevision()
                && Objects.equals(
                        operation.getExpectedSourceFingerprintSha256(),
                        change.getSourceFingerprintSha256())
                && Objects.equals(
                        operation.getExpectedCanonicalCommit(),
                        currentCanonicalCommit(change))
                && Objects.equals(
                        operation.getTargetFingerprintSha256(),
                        changeTargetFingerprint(change))
                && exactServerOwnedIdentity(change);
    }

    private DevelopmentChangeProjectionState staleIfCurrent(
            DevelopmentChangeProjectionState state) {
        return state == DevelopmentChangeProjectionState.CURRENT
                ? DevelopmentChangeProjectionState.STALE
                : state;
    }

    private String currentCanonicalCommit(DevelopmentChangeEntity change) {
        return change.getObservedCanonicalCommit() == null
                ? change.getBaseCommit()
                : change.getObservedCanonicalCommit();
    }

    private String changeTargetFingerprint(DevelopmentChangeEntity change) {
        return fingerprint(
                change.getProject().getId(),
                change.getChangeKey(),
                change.getStatus(),
                change.getBaseRef(),
                change.getBaseCommit(),
                change.getWorkspaceBranch(),
                change.getWorkspaceIdentity(),
                change.getSelectedWorkerId(),
                change.getProjectPolicyRevision(),
                change.getSourceRevision(),
                change.getSourceFingerprintSha256(),
                currentCanonicalCommit(change),
                change.getWorkspaceState(),
                change.getWorkspaceOperationRevision(),
                change.getVersion());
    }

    private DevelopmentChangeWorkspaceOperationResponse response(
            DevelopmentChangeWorkspaceOperationEntity operation,
            DevelopmentChangeEntity change,
            boolean replayed) {
        boolean terminal = operation.getState().terminal();
        DevelopmentChangeWorkspaceState workspaceState = terminal
                ? operation.getResultWorkspaceState()
                : change.getWorkspaceState();
        DevelopmentChangeSourceState sourceState = terminal
                ? operation.getResultSourceState()
                : change.getSourceState();
        long sourceRevision = terminal
                ? operation.getResultSourceRevision()
                : change.getSourceRevision();
        String sourceFingerprint = terminal
                ? operation.getResultSourceFingerprintSha256()
                : change.getSourceFingerprintSha256();
        return new DevelopmentChangeWorkspaceOperationResponse(
                operation.getOperationId(),
                operation.getOperationKind(),
                operation.getState(),
                operation.getRevision(),
                operation.getReceiptSha256(),
                replayed,
                operation.getFailureCode(),
                workspaceState,
                sourceState,
                sourceRevision,
                sourceFingerprint,
                nextAction(operation.getState(), workspaceState, sourceState));
    }

    private DevelopmentChangeActionResponse nextAction(
            DevelopmentChangeWorkspaceOperationState operationState,
            DevelopmentChangeWorkspaceState workspaceState,
            DevelopmentChangeSourceState sourceState) {
        if (operationState == DevelopmentChangeWorkspaceOperationState.REQUESTED
                || operationState == DevelopmentChangeWorkspaceOperationState.DISPATCHED) {
            return DevelopmentChangeActionResponse.waitForWorkspace();
        }
        if (operationState == DevelopmentChangeWorkspaceOperationState.UNCERTAIN
                || workspaceState == DevelopmentChangeWorkspaceState.UNCERTAIN) {
            return DevelopmentChangeActionResponse.reconcileWorkspace();
        }
        if (operationState == DevelopmentChangeWorkspaceOperationState.BLOCKED
                || workspaceState == DevelopmentChangeWorkspaceState.BLOCKED) {
            return DevelopmentChangeActionResponse.resolveOwnership();
        }
        if (workspaceState == DevelopmentChangeWorkspaceState.NOT_PROVISIONED) {
            return DevelopmentChangeActionResponse.provisionWorkspace();
        }
        if (sourceState == DevelopmentChangeSourceState.STALE) {
            return DevelopmentChangeActionResponse.reviewStaleSource();
        }
        return DevelopmentChangeActionResponse.bindSession();
    }

    private StartOutcome rejected(
            OperatorEntity actor,
            ProjectEntity project,
            String requestFingerprint,
            String targetFingerprint,
            V2FailureCategory category,
            String code,
            String message) {
        UUID operationId = UUID.randomUUID();
        auditService.record(new V2AuditFact(
                operationId,
                project.getId(),
                actor.getId(),
                DevelopmentChangePolicy.CAPABILITY,
                "DEVELOPMENT_CHANGE_WORKSPACE_OPERATION_DENIED",
                "DENIED",
                0,
                requestFingerprint,
                targetFingerprint,
                category,
                code,
                0,
                0,
                Instant.now()));
        DevelopmentChangeActionResponse action = category == V2FailureCategory.POLICY
                ? DevelopmentChangeActionResponse.waitForEnablement()
                : DevelopmentChangeActionResponse.none();
        return StartOutcome.rejected(new DevelopmentChangeRejectedException(
                category, code, message, action));
    }

    private void audit(
            DevelopmentChangeWorkspaceOperationEntity operation,
            String eventType,
            V2FailureCategory category,
            String code) {
        auditService.record(new V2AuditFact(
                operation.getOperationId(),
                operation.getProject().getId(),
                operation.getOperator().getId(),
                DevelopmentChangePolicy.CAPABILITY,
                eventType,
                operation.getState().name(),
                operation.getRevision(),
                operation.getRequestFingerprintSha256(),
                operation.getTargetFingerprintSha256(),
                category,
                code,
                operation.getState() == DevelopmentChangeWorkspaceOperationState.SUCCEEDED
                        ? 1 : 0,
                0,
                operation.getCompletedAt()));
    }

    private ProjectEntity requireProject(Long projectId) {
        if (projectId == null || projectId <= 0) {
            throw new WorkSessionProjectNotFoundException(projectId);
        }
        return projectRepository.findById(projectId)
                .orElseThrow(() -> new WorkSessionProjectNotFoundException(projectId));
    }

    private OperatorEntity requireActor(Long operatorId) {
        if (operatorId == null) {
            throw new DevelopmentChangeRejectedException(
                    V2FailureCategory.POLICY,
                    "DEVELOPMENT_CHANGE_AUTHENTICATION_REQUIRED",
                    "Se requiere un operador autenticado.",
                    DevelopmentChangeActionResponse.none());
        }
        OperatorEntity actor = operatorRepository.findByIdForUpdate(operatorId).orElse(null);
        if (actor == null || !actor.isActive()) {
            throw new DevelopmentChangeRejectedException(
                    V2FailureCategory.POLICY,
                    "DEVELOPMENT_CHANGE_OPERATOR_INACTIVE",
                    "El operador autenticado no está activo.",
                    DevelopmentChangeActionResponse.none());
        }
        return actor;
    }

    private V2FailureCategory mapCategory(RemoteWorkerFailureCategory category) {
        if (category == null) {
            return V2FailureCategory.TRANSPORT;
        }
        return switch (category) {
            case TRANSPORT -> V2FailureCategory.TRANSPORT;
            case CAPACITY -> V2FailureCategory.CAPACITY;
            case POLICY -> V2FailureCategory.POLICY;
            case VALIDATION -> V2FailureCategory.VALIDATION;
            case OWNERSHIP, PROTOCOL -> V2FailureCategory.OWNERSHIP;
        };
    }

    private String safeFailureCode(RemoteWorkerException failure, boolean uncertain) {
        String code = failure.getFailureCode();
        if (code != null && code.matches("[A-Z][A-Z0-9_]{2,79}")) {
            return code;
        }
        return uncertain ? TRANSPORT_UNCERTAIN :
                "DEVELOPMENT_CHANGE_WORKER_FAILURE_BLOCKED";
    }

    private UUID reconciliationKey(UUID predecessorOperationId) {
        return UUID.nameUUIDFromBytes(("development-change-workspace-reconcile:"
                + predecessorOperationId).getBytes(StandardCharsets.UTF_8));
    }

    private boolean exactGitObject(String value) {
        return value != null && value.matches("(?:[0-9a-f]{40}|[0-9a-f]{64})");
    }

    private boolean sha256(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    private static String fingerprint(Object... values) {
        StringBuilder canonical = new StringBuilder();
        for (Object value : values) {
            canonical.append(value == null ? "<null>" : value).append('\n');
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    private record StartOutcome(
            DevelopmentChangeWorkspaceCommand command,
            DevelopmentChangeWorkspaceOperationResponse replay,
            DevelopmentChangeRejectedException rejection) {

        private static StartOutcome dispatch(DevelopmentChangeWorkspaceCommand command) {
            return new StartOutcome(command, null, null);
        }

        private static StartOutcome replay(
                DevelopmentChangeWorkspaceOperationResponse response) {
            return new StartOutcome(null, response, null);
        }

        private static StartOutcome rejected(DevelopmentChangeRejectedException rejection) {
            return new StartOutcome(null, null, rejection);
        }
    }

    private record RestartTarget(
            Long operatorId,
            Long projectId,
            UUID changeKey,
            UUID predecessorOperationId) {
    }
}
