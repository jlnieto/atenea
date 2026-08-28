package com.atenea.service.developmentchange;

import com.atenea.api.developmentchange.OpenOrResolveRemoteSessionRequest;
import com.atenea.api.developmentchange.RemoteSessionNextAction;
import com.atenea.api.developmentchange.RemoteSessionFailureResponse;
import com.atenea.api.developmentchange.RemoteSessionOperationResponse;
import com.atenea.api.developmentchange.RemoteSessionRejectionClass;
import com.atenea.api.developmentchange.RemoteSessionResolution;
import com.atenea.auth.AuthenticatedOperator;
import com.atenea.persistence.auth.OperatorEntity;
import com.atenea.persistence.auth.OperatorRepository;
import com.atenea.persistence.developmentchange.DevelopmentChangeEntity;
import com.atenea.persistence.developmentchange.DevelopmentChangeRepository;
import com.atenea.persistence.developmentchange.DevelopmentChangeSourceState;
import com.atenea.persistence.developmentchange.DevelopmentChangeStatus;
import com.atenea.persistence.developmentchange.DevelopmentChangeWorkspaceState;
import com.atenea.persistence.developmentchange.RemoteSessionOperationEntity;
import com.atenea.persistence.developmentchange.RemoteSessionOperationKind;
import com.atenea.persistence.developmentchange.RemoteSessionOperationRepository;
import com.atenea.persistence.developmentchange.RemoteSessionOperationState;
import com.atenea.persistence.project.ProjectEntity;
import com.atenea.persistence.project.ProjectRepository;
import com.atenea.persistence.worksession.ExecutionTarget;
import com.atenea.persistence.worksession.RemoteCloseState;
import com.atenea.persistence.worksession.WorkSessionEntity;
import com.atenea.persistence.worksession.WorkSessionPullRequestStatus;
import com.atenea.persistence.worksession.WorkSessionRepository;
import com.atenea.persistence.worksession.WorkSessionStatus;
import com.atenea.remoteworker.ProjectCodexIdentity;
import com.atenea.service.v2control.V2AuditFact;
import com.atenea.service.v2control.V2AuditOutboxService;
import com.atenea.service.worksession.WorkSessionProjectNotFoundException;
import com.atenea.v2.control.V2FailureCategory;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class RemoteSessionService {

    private final ProjectRepository projectRepository;
    private final OperatorRepository operatorRepository;
    private final DevelopmentChangeRepository changeRepository;
    private final WorkSessionRepository workSessionRepository;
    private final RemoteSessionOperationRepository operationRepository;
    private final DevelopmentChangePolicy developmentChangePolicy;
    private final RemoteWorkBetaPolicy betaPolicy;
    private final V2AuditOutboxService auditService;
    private final TransactionTemplate transaction;

    public RemoteSessionService(
            ProjectRepository projectRepository,
            OperatorRepository operatorRepository,
            DevelopmentChangeRepository changeRepository,
            WorkSessionRepository workSessionRepository,
            RemoteSessionOperationRepository operationRepository,
            DevelopmentChangePolicy developmentChangePolicy,
            RemoteWorkBetaPolicy betaPolicy,
            V2AuditOutboxService auditService,
            PlatformTransactionManager transactionManager) {
        this.projectRepository = projectRepository;
        this.operatorRepository = operatorRepository;
        this.changeRepository = changeRepository;
        this.workSessionRepository = workSessionRepository;
        this.operationRepository = operationRepository;
        this.developmentChangePolicy = developmentChangePolicy;
        this.betaPolicy = betaPolicy;
        this.auditService = auditService;
        transaction = new TransactionTemplate(transactionManager);
    }

    public RemoteSessionOperationResponse openOrResolve(
            AuthenticatedOperator authenticated,
            Long projectId,
            UUID changeKey,
            UUID idempotencyKey,
            OpenOrResolveRemoteSessionRequest request) {
        Outcome outcome = transaction.execute(ignored -> openOrResolveInTransaction(
                authenticated, projectId, changeKey, idempotencyKey, request));
        if (outcome == null) {
            throw new IllegalStateException("Remote session operation produced no outcome");
        }
        if (outcome.rejection() != null) {
            throw outcome.rejection();
        }
        return outcome.response();
    }

    public List<RemoteSessionOperationResponse> recoverIncompleteOperations() {
        List<RemoteSessionOperationResponse> result = transaction.execute(ignored -> {
            List<RemoteSessionOperationResponse> recovered = new ArrayList<>();
            for (RemoteSessionOperationEntity operation : operationRepository
                    .findAllByStateForUpdate(RemoteSessionOperationState.REQUESTED)) {
                List<WorkSessionEntity> linked = workSessionRepository
                        .findAllByDevelopmentChangeIdOrderByOpenedAtAscIdAsc(
                                operation.getDevelopmentChange().getId());
                WorkSessionEntity persisted = operation.getWorkSession();
                if (persisted != null
                        && linked.size() == 1
                        && Objects.equals(linked.getFirst().getId(), persisted.getId())
                        && exactOwnership(operation.getDevelopmentChange(), persisted)
                        && Objects.equals(operation.getOwnershipFingerprintSha256(),
                                ownershipFingerprint(operation.getDevelopmentChange(), persisted))) {
                    complete(operation, operation.getDevelopmentChange(), persisted,
                            RemoteSessionResolution.RESOLVED, Instant.now());
                    acceptedAudit(operation, "REMOTE_SESSION_RECOVERED");
                    recovered.add(response(operation, true));
                } else {
                    block(operation,
                            linked.size() > 1
                                    ? "REMOTE_SESSION_RECOVERY_AMBIGUOUS"
                                    : "REMOTE_SESSION_RECOVERY_INCOMPLETE",
                            Instant.now());
                    rejectedAudit(operation, V2FailureCategory.OWNERSHIP);
                }
            }
            return List.copyOf(recovered);
        });
        return result == null ? List.of() : result;
    }

    private Outcome openOrResolveInTransaction(
            AuthenticatedOperator authenticated,
            Long projectId,
            UUID changeKey,
            UUID idempotencyKey,
            OpenOrResolveRemoteSessionRequest request) {
        ProjectEntity project = requireProject(projectId);
        OperatorEntity actor = requireActor(authenticated);
        Long expectedRevision = request == null ? null : request.getExpectedChangeRevision();
        Set<String> unsupported = request == null ? Set.of() : request.unsupportedFields();
        String requestFingerprint = fingerprint(
                RemoteSessionOperationKind.OPEN_OR_RESOLVE_REMOTE_SESSION,
                actor.getId(), projectId, changeKey, idempotencyKey,
                expectedRevision, unsupported);

        if (changeKey == null) {
            return rejectWithoutOperation(actor, project, requestFingerprint,
                    fingerprint("REMOTE_SESSION_TARGET", projectId, changeKey),
                    RemoteSessionRejectionClass.VALIDATION,
                    "REMOTE_SESSION_REQUEST_INVALID",
                    "Se requieren changeKey, idempotency key y revisión esperada válidos.",
                    RemoteSessionNextAction.NONE);
        }

        DevelopmentChangeEntity change = changeRepository.findByChangeKeyForUpdate(changeKey)
                .orElse(null);
        if (change == null || change.getProject() == null
                || !Objects.equals(projectId, change.getProject().getId())) {
            return rejectWithoutOperation(actor, project, requestFingerprint,
                    fingerprint("REMOTE_SESSION_TARGET", projectId, changeKey),
                    RemoteSessionRejectionClass.OWNERSHIP,
                    "REMOTE_SESSION_CHANGE_OWNERSHIP_MISMATCH",
                    "El cambio no pertenece exactamente al proyecto solicitado.",
                    RemoteSessionNextAction.RESOLVE_OWNERSHIP);
        }

        RemoteSessionOperationEntity replay = idempotencyKey == null
                ? null
                : operationRepository
                        .findByOperatorIdAndOperationKindAndIdempotencyKey(
                                actor.getId(),
                                RemoteSessionOperationKind.OPEN_OR_RESOLVE_REMOTE_SESSION,
                                idempotencyKey)
                        .orElse(null);
        if (replay != null) {
            return replay(replay, projectId, changeKey, requestFingerprint);
        }

        String targetFingerprint = targetFingerprint(change, expectedRevision);
        String sourceFingerprint = safeFingerprint(change.getSourceFingerprintSha256());
        String changeOwnership = ownershipFingerprint(change, null);

        if (idempotencyKey == null || expectedRevision == null || expectedRevision < 0) {
            return rejectWithoutOperation(actor, project, requestFingerprint, targetFingerprint,
                    RemoteSessionRejectionClass.VALIDATION,
                    "REMOTE_SESSION_REQUEST_INVALID",
                    "Se requieren idempotency key y revisión esperada válidas.",
                    RemoteSessionNextAction.NONE);
        }
        if (!unsupported.isEmpty()) {
            return rejectTerminal(actor, project, change, idempotencyKey, expectedRevision,
                    requestFingerprint, targetFingerprint, sourceFingerprint, changeOwnership, 0,
                    RemoteSessionRejectionClass.VALIDATION,
                    "REMOTE_SESSION_CLIENT_SELECTOR_REJECTED",
                    "La solicitud contiene selectores internos no admitidos.",
                    RemoteSessionNextAction.NONE);
        }

        RemoteWorkBetaPolicy.Decision betaDecision = betaPolicy.decide(projectId);
        if (!betaDecision.allowed()) {
            return rejectTerminal(actor, project, change, idempotencyKey, expectedRevision,
                    requestFingerprint, targetFingerprint, sourceFingerprint, changeOwnership, 0,
                    RemoteSessionRejectionClass.POLICY,
                    betaDecision.failureCode(),
                    "La apertura de sesión remota beta no está habilitada.",
                    RemoteSessionNextAction.WAIT_FOR_ENABLEMENT);
        }
        DevelopmentChangePolicy.Decision developmentDecision =
                developmentChangePolicy.decide(projectId, true);
        if (!developmentDecision.allowed()) {
            return rejectTerminal(actor, project, change, idempotencyKey, expectedRevision,
                    requestFingerprint, targetFingerprint, sourceFingerprint, changeOwnership,
                    betaDecision.projectPolicyRevision(),
                    RemoteSessionRejectionClass.POLICY,
                    developmentDecision.failureCode(),
                    "El control de DevelopmentChange requerido permanece deshabilitado.",
                    RemoteSessionNextAction.WAIT_FOR_ENABLEMENT);
        }
        if (developmentDecision.projectPolicyRevision() != change.getProjectPolicyRevision()) {
            return rejectTerminal(actor, project, change, idempotencyKey, expectedRevision,
                    requestFingerprint, targetFingerprint, sourceFingerprint, changeOwnership,
                    betaDecision.projectPolicyRevision(),
                    RemoteSessionRejectionClass.POLICY,
                    "REMOTE_SESSION_CHANGE_POLICY_REVISION_MISMATCH",
                    "La revisión de policy del cambio ya no coincide.",
                    RemoteSessionNextAction.REFRESH_CHANGE);
        }
        if (expectedRevision != change.getVersion()) {
            return rejectTerminal(actor, project, change, idempotencyKey, expectedRevision,
                    requestFingerprint, targetFingerprint, sourceFingerprint, changeOwnership,
                    betaDecision.projectPolicyRevision(),
                    RemoteSessionRejectionClass.VALIDATION,
                    "REMOTE_SESSION_CHANGE_REVISION_STALE",
                    "La revisión pública esperada del cambio está desactualizada.",
                    RemoteSessionNextAction.REFRESH_CHANGE);
        }

        if (operationRepository.existsByDevelopmentChangeIdAndState(
                change.getId(), RemoteSessionOperationState.REQUESTED)) {
            return rejectWithoutOperation(actor, project, requestFingerprint, targetFingerprint,
                    RemoteSessionRejectionClass.CAPACITY,
                    "REMOTE_SESSION_OPERATION_ACTIVE",
                    "El cambio ya tiene una operación de sesión activa.",
                    RemoteSessionNextAction.WAIT_FOR_CLOSE);
        }

        List<WorkSessionEntity> linked = workSessionRepository
                .findAllByDevelopmentChangeIdOrderByOpenedAtAscIdAsc(change.getId());
        if (linked.size() > 1) {
            return rejectTerminal(actor, project, change, idempotencyKey, expectedRevision,
                    requestFingerprint, targetFingerprint, sourceFingerprint, changeOwnership,
                    betaDecision.projectPolicyRevision(),
                    RemoteSessionRejectionClass.OWNERSHIP,
                    "REMOTE_SESSION_RETAINED_STATE_AMBIGUOUS",
                    "Existe más de una sesión retenida para el cambio.",
                    RemoteSessionNextAction.RESOLVE_OWNERSHIP);
        }
        if (linked.size() == 1) {
            WorkSessionEntity existing = linked.getFirst();
            if (!exactOwnership(change, existing)) {
                return rejectTerminal(actor, project, change, idempotencyKey, expectedRevision,
                        requestFingerprint, targetFingerprint, sourceFingerprint,
                        ownershipFingerprint(change, existing),
                        betaDecision.projectPolicyRevision(),
                        RemoteSessionRejectionClass.OWNERSHIP,
                        "REMOTE_SESSION_OWNERSHIP_MISMATCH",
                        "La sesión vinculada no coincide con el ownership exacto del cambio.",
                        RemoteSessionNextAction.RESOLVE_OWNERSHIP);
            }
            if (change.getStatus() != DevelopmentChangeStatus.OPEN
                    && change.getStatus() != DevelopmentChangeStatus.PAUSED) {
                return rejectTerminal(actor, project, change, idempotencyKey, expectedRevision,
                        requestFingerprint, targetFingerprint, sourceFingerprint,
                        ownershipFingerprint(change, existing),
                        betaDecision.projectPolicyRevision(),
                        RemoteSessionRejectionClass.UNSUPPORTED,
                        "REMOTE_SESSION_CHANGE_STATE_UNSUPPORTED",
                        "El estado terminal del cambio no permite resolver la sesión.",
                        RemoteSessionNextAction.NONE);
            }
            return succeed(actor, project, change, existing, idempotencyKey,
                    expectedRevision, requestFingerprint, targetFingerprint,
                    betaDecision.projectPolicyRevision(), RemoteSessionResolution.RESOLVED);
        }

        if (change.getStatus() == DevelopmentChangeStatus.PAUSED) {
            return rejectTerminal(actor, project, change, idempotencyKey, expectedRevision,
                    requestFingerprint, targetFingerprint, sourceFingerprint, changeOwnership,
                    betaDecision.projectPolicyRevision(),
                    RemoteSessionRejectionClass.UNSUPPORTED,
                    "REMOTE_SESSION_PAUSED_RESOLVE_ONLY",
                    "Un cambio pausado sólo puede resolver una sesión exacta ya vinculada.",
                    RemoteSessionNextAction.NONE);
        }
        if (change.getStatus() != DevelopmentChangeStatus.OPEN) {
            return rejectTerminal(actor, project, change, idempotencyKey, expectedRevision,
                    requestFingerprint, targetFingerprint, sourceFingerprint, changeOwnership,
                    betaDecision.projectPolicyRevision(),
                    RemoteSessionRejectionClass.UNSUPPORTED,
                    "REMOTE_SESSION_CHANGE_STATE_UNSUPPORTED",
                    "Sólo un cambio abierto puede crear una sesión.",
                    RemoteSessionNextAction.NONE);
        }
        if (!readyWorkspace(change)) {
            return rejectTerminal(actor, project, change, idempotencyKey, expectedRevision,
                    requestFingerprint, targetFingerprint, sourceFingerprint, changeOwnership,
                    betaDecision.projectPolicyRevision(),
                    RemoteSessionRejectionClass.OWNERSHIP,
                    "REMOTE_SESSION_WORKSPACE_NOT_READY",
                    "El workspace server-owned no está READY de forma exacta.",
                    RemoteSessionNextAction.RESOLVE_OWNERSHIP);
        }

        List<WorkSessionEntity> retained = retainedCandidates(change);
        if (!retained.isEmpty()) {
            String code = retained.size() > 1
                    ? "REMOTE_SESSION_RETAINED_STATE_AMBIGUOUS"
                    : "REMOTE_SESSION_FOREIGN_RESOURCE";
            return rejectTerminal(actor, project, change, idempotencyKey, expectedRevision,
                    requestFingerprint, targetFingerprint, sourceFingerprint, changeOwnership,
                    betaDecision.projectPolicyRevision(),
                    RemoteSessionRejectionClass.OWNERSHIP,
                    code,
                    "Un recurso retenido no demuestra ownership exacto y permanece intacto.",
                    RemoteSessionNextAction.RESOLVE_OWNERSHIP);
        }

        WorkSessionEntity created = newSession(change, project, Instant.now());
        return succeed(actor, project, change, created, idempotencyKey,
                expectedRevision, requestFingerprint, targetFingerprint,
                betaDecision.projectPolicyRevision(), RemoteSessionResolution.CREATED);
    }

    private Outcome succeed(
            OperatorEntity actor,
            ProjectEntity project,
            DevelopmentChangeEntity change,
            WorkSessionEntity session,
            UUID idempotencyKey,
            long expectedRevision,
            String requestFingerprint,
            String targetFingerprint,
            long betaPolicyRevision,
            RemoteSessionResolution resolution) {
        String ownershipFingerprint = ownershipFingerprint(change, session);
        Instant now = Instant.now();
        RemoteSessionOperationEntity operation = requestedOperation(
                actor, project, change, idempotencyKey, expectedRevision,
                requestFingerprint, targetFingerprint,
                safeFingerprint(change.getSourceFingerprintSha256()),
                ownershipFingerprint, betaPolicyRevision, now);
        operationRepository.saveAndFlush(operation);

        if (resolution == RemoteSessionResolution.CREATED) {
            session.setDevelopmentChange(change);
            session = workSessionRepository.saveAndFlush(session);
        }
        change.setUpdatedAt(now);
        change = changeRepository.saveAndFlush(change);
        complete(operation, change, session, resolution, now);
        acceptedAudit(operation, resolution == RemoteSessionResolution.CREATED
                ? "REMOTE_SESSION_CREATED_AND_BOUND"
                : "REMOTE_SESSION_EXACT_RESOLVED");
        return Outcome.accepted(response(operation, false));
    }

    private Outcome rejectTerminal(
            OperatorEntity actor,
            ProjectEntity project,
            DevelopmentChangeEntity change,
            UUID idempotencyKey,
            long expectedRevision,
            String requestFingerprint,
            String targetFingerprint,
            String sourceFingerprint,
            String ownershipFingerprint,
            long betaPolicyRevision,
            RemoteSessionRejectionClass rejectionClass,
            String code,
            String message,
            RemoteSessionNextAction action) {
        Instant now = Instant.now();
        RemoteSessionOperationEntity operation = requestedOperation(
                actor, project, change, idempotencyKey, expectedRevision,
                requestFingerprint, targetFingerprint, sourceFingerprint,
                ownershipFingerprint, betaPolicyRevision, now);
        operationRepository.saveAndFlush(operation);
        reject(operation, rejectionClass, code, action, now);
        rejectedAudit(operation, auditCategory(rejectionClass));
        return Outcome.rejected(new RemoteSessionRejectedException(
                failure(operation, false, message)));
    }

    private Outcome rejectWithoutOperation(
            OperatorEntity actor,
            ProjectEntity project,
            String requestFingerprint,
            String targetFingerprint,
            RemoteSessionRejectionClass rejectionClass,
            String code,
            String message,
            RemoteSessionNextAction action) {
        auditService.record(new V2AuditFact(
                UUID.randomUUID(), project.getId(), actor.getId(),
                RemoteWorkBetaPolicy.CAPABILITY,
                "REMOTE_SESSION_MUTATION_DENIED", "DENIED", 0,
                requestFingerprint, targetFingerprint,
                auditCategory(rejectionClass), code, 0, 0, Instant.now()));
        return Outcome.rejected(new RemoteSessionRejectedException(
                rejectionClass, code, message, action));
    }

    private Outcome replay(
            RemoteSessionOperationEntity operation,
            Long projectId,
            UUID changeKey,
            String requestFingerprint) {
        if (!Objects.equals(operation.getProject().getId(), projectId)
                || !Objects.equals(operation.getDevelopmentChange().getChangeKey(), changeKey)
                || !Objects.equals(operation.getRequestFingerprintSha256(), requestFingerprint)) {
            return rejectWithoutOperation(
                    operation.getOperator(), operation.getProject(), requestFingerprint,
                    operation.getTargetFingerprintSha256(),
                    RemoteSessionRejectionClass.OWNERSHIP,
                    "REMOTE_SESSION_IDEMPOTENCY_CONFLICT",
                    "La idempotency key ya pertenece a otra solicitud.",
                    RemoteSessionNextAction.RESOLVE_OWNERSHIP);
        }
        return switch (operation.getState()) {
            case SUCCEEDED -> Outcome.accepted(response(operation, true));
            case REJECTED, BLOCKED -> Outcome.rejected(new RemoteSessionRejectedException(
                    failure(operation, true,
                            "La operación ya tiene un rechazo terminal inmutable.")));
            case REQUESTED -> Outcome.rejected(new RemoteSessionRejectedException(
                    RemoteSessionRejectionClass.CAPACITY,
                    "REMOTE_SESSION_OPERATION_ACTIVE",
                    "La operación durable todavía no tiene terminal verificable.",
                    RemoteSessionNextAction.WAIT_FOR_CLOSE));
        };
    }

    private RemoteSessionOperationEntity requestedOperation(
            OperatorEntity actor,
            ProjectEntity project,
            DevelopmentChangeEntity change,
            UUID idempotencyKey,
            long expectedRevision,
            String requestFingerprint,
            String targetFingerprint,
            String sourceFingerprint,
            String ownershipFingerprint,
            long betaPolicyRevision,
            Instant now) {
        RemoteSessionOperationEntity operation = new RemoteSessionOperationEntity();
        operation.setOperationId(UUID.randomUUID());
        operation.setOperator(actor);
        operation.setProject(project);
        operation.setDevelopmentChange(change);
        operation.setIdempotencyKey(idempotencyKey);
        operation.setOperationKind(RemoteSessionOperationKind.OPEN_OR_RESOLVE_REMOTE_SESSION);
        operation.setExpectedChangeRevision(expectedRevision);
        operation.setRequestFingerprintSha256(requestFingerprint);
        operation.setTargetFingerprintSha256(targetFingerprint);
        operation.setSourceFingerprintSha256(sourceFingerprint);
        operation.setOwnershipFingerprintSha256(ownershipFingerprint);
        operation.setBetaPolicyRevision(betaPolicyRevision);
        operation.setState(RemoteSessionOperationState.REQUESTED);
        operation.setRevision(0);
        operation.setRequestedAt(now);
        operation.setUpdatedAt(now);
        return operation;
    }

    private void complete(
            RemoteSessionOperationEntity operation,
            DevelopmentChangeEntity change,
            WorkSessionEntity session,
            RemoteSessionResolution resolution,
            Instant now) {
        RemoteSessionNextAction nextAction = nextAction(change, session);
        operation.setWorkSession(session);
        operation.setState(RemoteSessionOperationState.SUCCEEDED);
        operation.setRevision(1);
        operation.setResolution(resolution);
        operation.setResultChangeRevision(change.getVersion());
        operation.setResultSessionState(session.getStatus());
        operation.setResultRemoteSessionId(session.getRemoteSessionId());
        operation.setNextAction(nextAction);
        operation.setReceiptSha256(fingerprint(
                operation.getOperationId(), operation.getOperationKind(), "SUCCEEDED", 1,
                change.getChangeKey(), change.getVersion(), session.getId(),
                session.getRemoteSessionId(), resolution,
                operation.getSourceFingerprintSha256(),
                operation.getOwnershipFingerprintSha256(), nextAction));
        operation.setCompletedAt(now);
        operation.setUpdatedAt(now);
        operationRepository.saveAndFlush(operation);
    }

    private void reject(
            RemoteSessionOperationEntity operation,
            RemoteSessionRejectionClass rejectionClass,
            String code,
            RemoteSessionNextAction action,
            Instant now) {
        operation.setState(RemoteSessionOperationState.REJECTED);
        operation.setRevision(1);
        operation.setRejectionClass(rejectionClass);
        operation.setFailureCode(code);
        operation.setNextAction(action);
        operation.setReceiptSha256(fingerprint(
                operation.getOperationId(), operation.getOperationKind(), "REJECTED", 1,
                operation.getDevelopmentChange().getChangeKey(),
                operation.getRequestFingerprintSha256(),
                operation.getSourceFingerprintSha256(),
                operation.getOwnershipFingerprintSha256(), rejectionClass, code, action));
        operation.setCompletedAt(now);
        operation.setUpdatedAt(now);
        operationRepository.saveAndFlush(operation);
    }

    private void block(RemoteSessionOperationEntity operation, String code, Instant now) {
        operation.setState(RemoteSessionOperationState.BLOCKED);
        operation.setRevision(1);
        operation.setRejectionClass(RemoteSessionRejectionClass.OWNERSHIP);
        operation.setFailureCode(code);
        operation.setNextAction(RemoteSessionNextAction.RESOLVE_OWNERSHIP);
        operation.setReceiptSha256(fingerprint(
                operation.getOperationId(), operation.getOperationKind(), "BLOCKED", 1,
                operation.getDevelopmentChange().getChangeKey(),
                operation.getRequestFingerprintSha256(),
                operation.getSourceFingerprintSha256(),
                operation.getOwnershipFingerprintSha256(), code));
        operation.setCompletedAt(now);
        operation.setUpdatedAt(now);
        operationRepository.saveAndFlush(operation);
    }

    private RemoteSessionOperationResponse response(
            RemoteSessionOperationEntity operation,
            boolean replayed) {
        return new RemoteSessionOperationResponse(
                operation.getOperationId(),
                operation.getState(),
                operation.getRevision(),
                operation.getReceiptSha256(),
                replayed,
                operation.getResolution(),
                operation.getDevelopmentChange().getChangeKey(),
                operation.getWorkSession().getId(),
                operation.getResultRemoteSessionId(),
                operation.getSourceFingerprintSha256(),
                operation.getOwnershipFingerprintSha256(),
                operation.getResultChangeRevision(),
                operation.getResultSessionState(),
                operation.getNextAction());
    }

    private RemoteSessionFailureResponse failure(
            RemoteSessionOperationEntity operation,
            boolean replayed,
            String message) {
        return new RemoteSessionFailureResponse(
                status(operation.getRejectionClass()),
                operation.getRejectionClass(),
                operation.getFailureCode(),
                message,
                false,
                operation.getNextAction(),
                operation.getOperationId(),
                operation.getState(),
                operation.getRevision(),
                operation.getReceiptSha256(),
                replayed,
                operation.getDevelopmentChange().getChangeKey(),
                operation.getWorkSession() == null ? null : operation.getWorkSession().getId(),
                operation.getResultRemoteSessionId(),
                operation.getSourceFingerprintSha256(),
                operation.getOwnershipFingerprintSha256());
    }

    private int status(RemoteSessionRejectionClass rejectionClass) {
        return switch (rejectionClass) {
            case POLICY -> 403;
            case OWNERSHIP, CAPACITY -> 409;
            case VALIDATION, UNSUPPORTED -> 422;
        };
    }

    private WorkSessionEntity newSession(
            DevelopmentChangeEntity change,
            ProjectEntity project,
            Instant now) {
        WorkSessionEntity session = new WorkSessionEntity();
        session.setProject(project);
        session.setDevelopmentChange(change);
        session.setStatus(WorkSessionStatus.OPEN);
        session.setTitle(change.getTitle());
        session.setBaseBranch(change.getBaseRef().substring("refs/heads/".length()));
        session.setWorkspaceBranch(change.getWorkspaceBranch());
        session.setExternalThreadId(null);
        session.setExecutionTarget(ExecutionTarget.REMOTE);
        session.setSelectedWorkerId(change.getSelectedWorkerId());
        session.setWorkspaceIdentity(change.getWorkspaceIdentity());
        session.setRemoteSessionId(UUID.randomUUID());
        session.setRemoteWorkloadKind(ProjectCodexIdentity.WORKLOAD_KIND);
        session.setRemoteCloseState(RemoteCloseState.NOT_STARTED);
        session.setAttachmentPolicyRevision(null);
        session.setCanonicalSourceRef(change.getBaseRef());
        session.setCanonicalSourceCommit(change.getBaseCommit());
        session.setCanonicalSourceObservationSha256(change.getSourceFingerprintSha256());
        session.setCanonicalSourceObservedAt(now);
        session.setPullRequestStatus(WorkSessionPullRequestStatus.NOT_CREATED);
        session.setOpenedAt(now);
        session.setLastActivityAt(now);
        session.setCloseRetryable(false);
        session.setCreatedAt(now);
        session.setUpdatedAt(now);
        return session;
    }

    private List<WorkSessionEntity> retainedCandidates(DevelopmentChangeEntity change) {
        Map<Long, WorkSessionEntity> retained = new LinkedHashMap<>();
        for (WorkSessionEntity session : workSessionRepository
                .findAllByWorkspaceIdentityOrderByOpenedAtAscIdAsc(
                        change.getWorkspaceIdentity())) {
            retained.put(session.getId(), session);
        }
        for (WorkSessionEntity session : workSessionRepository
                .findAllByProjectIdAndWorkspaceBranchOrderByOpenedAtAscIdAsc(
                        change.getProject().getId(), change.getWorkspaceBranch())) {
            retained.put(session.getId(), session);
        }
        return List.copyOf(retained.values());
    }

    private boolean readyWorkspace(DevelopmentChangeEntity change) {
        return change.getWorkspaceState() == DevelopmentChangeWorkspaceState.READY
                && change.getWorkspaceOperationRevision() > 0
                && change.getWorkspaceUpdatedAt() != null
                && change.getBaseRef() != null
                && change.getBaseRef().matches("^refs/heads/[^\\s]+$")
                && change.getBaseCommit() != null
                && change.getBaseCommit().matches("^[0-9a-f]{40}$")
                && change.getWorkspaceBranch() != null
                && !change.getWorkspaceBranch().isBlank()
                && change.getWorkspaceIdentity() != null
                && !change.getWorkspaceIdentity().isBlank()
                && change.getSelectedWorkerId() != null
                && !change.getSelectedWorkerId().isBlank()
                && change.getObservedCanonicalCommit() != null
                && change.getObservedCanonicalCommit().matches("^[0-9a-f]{40}$")
                && (change.getSourceState() != DevelopmentChangeSourceState.DIRTY
                    || isSha256(change.getSourceFingerprintSha256()))
                && change.getSourceState() != DevelopmentChangeSourceState.STALE
                && change.getSourceState() != DevelopmentChangeSourceState.BLOCKED;
    }

    private boolean exactOwnership(
            DevelopmentChangeEntity change,
            WorkSessionEntity session) {
        return session != null
                && session.getDevelopmentChange() != null
                && Objects.equals(session.getDevelopmentChange().getId(), change.getId())
                && session.getProject() != null
                && Objects.equals(session.getProject().getId(), change.getProject().getId())
                && session.getExecutionTarget() == ExecutionTarget.REMOTE
                && session.getRemoteSessionId() != null
                && Objects.equals(session.getRemoteWorkloadKind(), ProjectCodexIdentity.WORKLOAD_KIND)
                && Objects.equals(session.getSelectedWorkerId(), change.getSelectedWorkerId())
                && Objects.equals(session.getWorkspaceBranch(), change.getWorkspaceBranch())
                && Objects.equals(session.getWorkspaceIdentity(), change.getWorkspaceIdentity())
                && Objects.equals("refs/heads/" + session.getBaseBranch(), change.getBaseRef());
    }

    private RemoteSessionNextAction nextAction(
            DevelopmentChangeEntity change,
            WorkSessionEntity session) {
        if (change.getStatus() == DevelopmentChangeStatus.PAUSED) {
            return RemoteSessionNextAction.READ_PAUSED_SESSION;
        }
        return switch (session.getStatus()) {
            case OPEN -> RemoteSessionNextAction.CONTINUE_SESSION;
            case CLOSING -> RemoteSessionNextAction.WAIT_FOR_CLOSE;
            case DRAFT_BLOCKED -> RemoteSessionNextAction.RESOLVE_OWNERSHIP;
            case CLOSED -> RemoteSessionNextAction.NONE;
        };
    }

    private String targetFingerprint(DevelopmentChangeEntity change, Long expectedRevision) {
        return fingerprint(
                change.getProject().getId(), change.getChangeKey(), expectedRevision,
                change.getBaseRef(), change.getBaseCommit(), change.getWorkspaceBranch(),
                change.getWorkspaceIdentity(), change.getSelectedWorkerId());
    }

    private String ownershipFingerprint(
            DevelopmentChangeEntity change,
            WorkSessionEntity session) {
        return fingerprint(
                change.getProject().getId(), change.getChangeKey(),
                change.getWorkspaceBranch(), change.getWorkspaceIdentity(),
                change.getSelectedWorkerId(), ProjectCodexIdentity.WORKLOAD_KIND,
                session == null ? null : session.getId(),
                session == null ? null : session.getRemoteSessionId(),
                session == null ? null : session.getStatus());
    }

    private void acceptedAudit(RemoteSessionOperationEntity operation, String eventType) {
        auditService.record(new V2AuditFact(
                operation.getOperationId(), operation.getProject().getId(),
                operation.getOperator().getId(), RemoteWorkBetaPolicy.CAPABILITY,
                eventType, operation.getState().name(), operation.getRevision(),
                operation.getRequestFingerprintSha256(),
                operation.getOwnershipFingerprintSha256(), null, null, 1, 0,
                operation.getCompletedAt()));
    }

    private void rejectedAudit(
            RemoteSessionOperationEntity operation,
            V2FailureCategory category) {
        auditService.record(new V2AuditFact(
                operation.getOperationId(), operation.getProject().getId(),
                operation.getOperator().getId(), RemoteWorkBetaPolicy.CAPABILITY,
                "REMOTE_SESSION_MUTATION_DENIED", operation.getState().name(),
                operation.getRevision(), operation.getRequestFingerprintSha256(),
                operation.getOwnershipFingerprintSha256(), category,
                operation.getFailureCode(), 0, 0, operation.getCompletedAt()));
    }

    private V2FailureCategory auditCategory(RemoteSessionRejectionClass rejectionClass) {
        return switch (rejectionClass) {
            case VALIDATION, UNSUPPORTED -> V2FailureCategory.VALIDATION;
            case POLICY -> V2FailureCategory.POLICY;
            case OWNERSHIP -> V2FailureCategory.OWNERSHIP;
            case CAPACITY -> V2FailureCategory.CAPACITY;
        };
    }

    private ProjectEntity requireProject(Long projectId) {
        if (projectId == null || projectId <= 0) {
            throw new WorkSessionProjectNotFoundException(projectId);
        }
        return projectRepository.findById(projectId)
                .orElseThrow(() -> new WorkSessionProjectNotFoundException(projectId));
    }

    private OperatorEntity requireActor(AuthenticatedOperator authenticated) {
        if (authenticated == null || authenticated.operatorId() == null) {
            throw new RemoteSessionRejectedException(
                    RemoteSessionRejectionClass.POLICY,
                    "REMOTE_SESSION_AUTHENTICATION_REQUIRED",
                    "Se requiere un operador autenticado.", RemoteSessionNextAction.NONE);
        }
        OperatorEntity actor = operatorRepository
                .findByIdForUpdate(authenticated.operatorId()).orElse(null);
        if (actor == null || !actor.isActive()) {
            throw new RemoteSessionRejectedException(
                    RemoteSessionRejectionClass.POLICY,
                    "REMOTE_SESSION_OPERATOR_INACTIVE",
                    "El operador autenticado no está activo.", RemoteSessionNextAction.NONE);
        }
        return actor;
    }

    private static String safeFingerprint(String value) {
        return isSha256(value) ? value : fingerprint("INVALID_SOURCE_FINGERPRINT", value);
    }

    private static boolean isSha256(String value) {
        return value != null && value.matches("^[0-9a-f]{64}$");
    }

    private static String fingerprint(Object... values) {
        StringBuilder canonical = new StringBuilder();
        for (Object value : values) {
            canonical.append(value == null ? "<null>" : canonicalValue(value)).append('\n');
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String canonicalValue(Object value) {
        if (value instanceof Set<?> set) {
            return set.stream().map(String::valueOf).sorted().toList().toString();
        }
        return String.valueOf(value);
    }

    private record Outcome(
            RemoteSessionOperationResponse response,
            RemoteSessionRejectedException rejection) {

        private static Outcome accepted(RemoteSessionOperationResponse response) {
            return new Outcome(response, null);
        }

        private static Outcome rejected(RemoteSessionRejectedException rejection) {
            return new Outcome(null, rejection);
        }
    }
}
