package com.atenea.service.developmentchange;

import com.atenea.api.developmentchange.CreateDevelopmentChangeRequest;
import com.atenea.api.developmentchange.DevelopmentChangeActionResponse;
import com.atenea.api.developmentchange.DevelopmentChangeMutationResponse;
import com.atenea.api.developmentchange.DevelopmentChangePhase;
import com.atenea.api.developmentchange.DevelopmentChangeResponse;
import com.atenea.auth.AuthenticatedOperator;
import com.atenea.developmentchange.DevelopmentChangeIdentity;
import com.atenea.persistence.auth.OperatorEntity;
import com.atenea.persistence.auth.OperatorRepository;
import com.atenea.persistence.developmentchange.DevelopmentChangeEntity;
import com.atenea.persistence.developmentchange.DevelopmentChangeOperationEntity;
import com.atenea.persistence.developmentchange.DevelopmentChangeOperationKind;
import com.atenea.persistence.developmentchange.DevelopmentChangeOperationRepository;
import com.atenea.persistence.developmentchange.DevelopmentChangeOperationState;
import com.atenea.persistence.developmentchange.DevelopmentChangeProjectionState;
import com.atenea.persistence.developmentchange.DevelopmentChangeRepository;
import com.atenea.persistence.developmentchange.DevelopmentChangeSourceState;
import com.atenea.persistence.developmentchange.DevelopmentChangeStatus;
import com.atenea.persistence.developmentchange.DevelopmentChangeWorkspaceState;
import com.atenea.persistence.project.ProjectEntity;
import com.atenea.persistence.project.ProjectRepository;
import com.atenea.persistence.worksession.ExecutionTarget;
import com.atenea.persistence.worksession.WorkSessionEntity;
import com.atenea.persistence.worksession.WorkSessionRepository;
import com.atenea.persistence.worksession.WorkSessionStatus;
import com.atenea.remoteworker.RemoteWorkerProperties;
import com.atenea.remoteworker.ProjectCodexIdentity;
import com.atenea.service.git.GitRepositoryService;
import com.atenea.service.git.GitRepositoryOperationException;
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
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class DevelopmentChangeService {

    private static final Set<WorkSessionStatus> ACTIVE_SESSION_STATES =
            Set.of(WorkSessionStatus.OPEN, WorkSessionStatus.CLOSING);

    private final ProjectRepository projectRepository;
    private final OperatorRepository operatorRepository;
    private final DevelopmentChangeRepository changeRepository;
    private final DevelopmentChangeOperationRepository operationRepository;
    private final WorkSessionRepository workSessionRepository;
    private final DevelopmentChangePolicy policy;
    private final GitRepositoryService gitRepositoryService;
    private final RemoteWorkerProperties remoteWorkerProperties;
    private final V2AuditOutboxService auditService;
    private final TransactionTemplate transaction;

    public DevelopmentChangeService(
            ProjectRepository projectRepository,
            OperatorRepository operatorRepository,
            DevelopmentChangeRepository changeRepository,
            DevelopmentChangeOperationRepository operationRepository,
            WorkSessionRepository workSessionRepository,
            DevelopmentChangePolicy policy,
            GitRepositoryService gitRepositoryService,
            RemoteWorkerProperties remoteWorkerProperties,
            V2AuditOutboxService auditService,
            PlatformTransactionManager transactionManager) {
        this.projectRepository = projectRepository;
        this.operatorRepository = operatorRepository;
        this.changeRepository = changeRepository;
        this.operationRepository = operationRepository;
        this.workSessionRepository = workSessionRepository;
        this.policy = policy;
        this.gitRepositoryService = gitRepositoryService;
        this.remoteWorkerProperties = remoteWorkerProperties;
        this.auditService = auditService;
        transaction = new TransactionTemplate(transactionManager);
    }

    @Transactional(readOnly = true)
    public List<DevelopmentChangeResponse> list(Long projectId) {
        requireProject(projectId);
        return changeRepository.findAllByProjectIdOrderByUpdatedAtDescIdDesc(projectId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public DevelopmentChangeResponse detail(Long projectId, UUID changeKey) {
        requireProject(projectId);
        DevelopmentChangeEntity change = changeRepository
                .findByProjectIdAndChangeKey(projectId, changeKey)
                .orElseThrow(() -> new DevelopmentChangeNotFoundException(changeKey));
        return toResponse(change);
    }

    public DevelopmentChangeMutationResponse create(
            AuthenticatedOperator actor,
            Long projectId,
            UUID idempotencyKey,
            CreateDevelopmentChangeRequest request) {
        return execute(() -> createInTransaction(actor, projectId, idempotencyKey, request));
    }

    public DevelopmentChangeMutationResponse pause(
            AuthenticatedOperator actor,
            Long projectId,
            UUID changeKey,
            UUID idempotencyKey) {
        return execute(() -> transitionInTransaction(
                actor, projectId, changeKey, idempotencyKey,
                DevelopmentChangeOperationKind.PAUSE, DevelopmentChangeStatus.PAUSED));
    }

    public DevelopmentChangeMutationResponse abandon(
            AuthenticatedOperator actor,
            Long projectId,
            UUID changeKey,
            UUID idempotencyKey) {
        return execute(() -> transitionInTransaction(
                actor, projectId, changeKey, idempotencyKey,
                DevelopmentChangeOperationKind.ABANDON, DevelopmentChangeStatus.ABANDONED));
    }

    public DevelopmentChangeMutationResponse bindSession(
            AuthenticatedOperator actor,
            Long projectId,
            UUID changeKey,
            Long sessionId,
            UUID idempotencyKey) {
        return execute(() -> bindInTransaction(
                actor, projectId, changeKey, sessionId, idempotencyKey));
    }

    private DevelopmentChangeMutationResponse execute(Supplier<CommandOutcome> command) {
        CommandOutcome outcome = transaction.execute(ignored -> command.get());
        if (outcome == null) {
            throw new IllegalStateException("Development change command produced no outcome");
        }
        if (outcome.rejection() != null) {
            throw outcome.rejection();
        }
        return outcome.response();
    }

    private CommandOutcome createInTransaction(
            AuthenticatedOperator authenticated,
            Long projectId,
            UUID idempotencyKey,
            CreateDevelopmentChangeRequest request) {
        ProjectEntity project = requireProject(projectId);
        OperatorEntity actor = requireActor(authenticated);
        String title = request == null || request.getTitle() == null
                ? ""
                : request.getTitle().trim();
        String requestFingerprint = fingerprint(
                "CREATE", actor.getId(), projectId, idempotencyKey, title,
                request == null ? Set.of() : request.unsupportedFields());
        String projectTarget = fingerprint(
                "PROJECT", projectId, project.getRepoPath(), project.getDefaultBaseBranch());

        if (idempotencyKey == null || title.isBlank() || title.length() > 200) {
            return denied(actor, project, requestFingerprint, projectTarget,
                    V2FailureCategory.VALIDATION,
                    "DEVELOPMENT_CHANGE_REQUEST_INVALID",
                    "Se requiere título e idempotency key válidos.");
        }
        if (request != null && !request.unsupportedFields().isEmpty()) {
            return denied(actor, project, requestFingerprint, projectTarget,
                    V2FailureCategory.VALIDATION,
                    "DEVELOPMENT_CHANGE_INTERNAL_SELECTOR_REJECTED",
                    "La solicitud contiene selectores que sólo puede resolver el servidor.");
        }
        DevelopmentChangeOperationEntity replay = existingOperation(
                actor.getId(), DevelopmentChangeOperationKind.CREATE, idempotencyKey);
        if (replay != null) {
            return replay(replay, projectId, requestFingerprint);
        }

        DevelopmentChangePolicy.Decision decision = policy.decide(projectId, false);
        if (!decision.allowed()) {
            return policyDenied(actor, project, requestFingerprint, projectTarget, decision);
        }

        if (!validBaseBranch(project.getDefaultBaseBranch())) {
            return denied(actor, project, requestFingerprint, projectTarget,
                    V2FailureCategory.OWNERSHIP,
                    "DEVELOPMENT_CHANGE_BASE_REF_INVALID",
                    "La base server-owned del proyecto no es inequívoca.");
        }
        String baseRef = "refs/heads/" + project.getDefaultBaseBranch();
        String baseCommit;
        String baseTree;
        try {
            baseCommit = gitRepositoryService.resolveExactHeadCommit(
                    project.getRepoPath(), baseRef);
            baseTree = gitRepositoryService.resolveCommitTree(
                    project.getRepoPath(), baseCommit);
        } catch (GitRepositoryOperationException unresolved) {
            return denied(actor, project, requestFingerprint, projectTarget,
                    V2FailureCategory.OWNERSHIP,
                    "DEVELOPMENT_CHANGE_BASE_REF_UNRESOLVED",
                    "La base server-owned no resuelve a una fuente Git exacta.");
        }
        UUID changeKey = UUID.randomUUID();
        DevelopmentChangeIdentity identity;
        try {
            identity = DevelopmentChangeIdentity.create(
                    changeKey, projectId, remoteWorkerProperties.getWorkerId());
        } catch (IllegalArgumentException invalidWorker) {
            return denied(actor, project, requestFingerprint, projectTarget,
                    V2FailureCategory.POLICY,
                    "DEVELOPMENT_CHANGE_WORKER_POLICY_INVALID",
                    "La política server-owned de worker no es válida.");
        }
        boolean branchExists;
        try {
            branchExists = gitRepositoryService.exactLocalHeadExists(
                    project.getRepoPath(), "refs/heads/" + identity.workspaceBranch());
        } catch (GitRepositoryOperationException unavailable) {
            return denied(actor, project, requestFingerprint, projectTarget,
                    V2FailureCategory.OWNERSHIP,
                    "DEVELOPMENT_CHANGE_BRANCH_STATE_UNAVAILABLE",
                    "No se pudo demostrar que la rama server-owned está libre.");
        }
        if (branchExists
                || changeRepository.findByWorkspaceIdentity(identity.workspaceIdentity()).isPresent()) {
            return denied(actor, project, requestFingerprint, projectTarget,
                    V2FailureCategory.OWNERSHIP,
                    "DEVELOPMENT_CHANGE_IDENTITY_COLLISION",
                    "La identidad server-owned ya está ocupada.");
        }
        String sourceFingerprint = fingerprint(
                projectId, baseRef, baseCommit, baseTree,
                identity.workspaceBranch(), identity.workspaceIdentity(),
                decision.projectPolicyRevision());
        String targetFingerprint = fingerprint(
                projectTarget, changeKey, identity.workspaceBranch(),
                identity.workspaceIdentity(), baseCommit, baseTree);
        Instant now = Instant.now();

        DevelopmentChangeOperationEntity operation = requestedOperation(
                actor, project, idempotencyKey, DevelopmentChangeOperationKind.CREATE,
                requestFingerprint, targetFingerprint, null, null, now);
        operationRepository.saveAndFlush(operation);

        DevelopmentChangeEntity change = new DevelopmentChangeEntity();
        change.setChangeKey(changeKey);
        change.setProject(project);
        change.setTitle(title);
        change.setStatus(DevelopmentChangeStatus.OPEN);
        change.setBaseRef(baseRef);
        change.setBaseCommit(baseCommit);
        change.setWorkspaceBranch(identity.workspaceBranch());
        change.setWorkspaceIdentity(identity.workspaceIdentity());
        change.setSelectedWorkerId(identity.selectedWorkerId());
        change.setProjectPolicyRevision(decision.projectPolicyRevision());
        change.setSourceRevision(0);
        change.setSourceFingerprintSha256(sourceFingerprint);
        change.setSourceState(DevelopmentChangeSourceState.CLEAN);
        change.setWorkspaceState(DevelopmentChangeWorkspaceState.NOT_PROVISIONED);
        change.setWorkspaceOperationRevision(0);
        change.setValidationState(DevelopmentChangeProjectionState.NOT_STARTED);
        change.setReviewState(DevelopmentChangeProjectionState.NOT_STARTED);
        change.setIntegrationState(DevelopmentChangeProjectionState.NOT_STARTED);
        change.setReleaseState(DevelopmentChangeProjectionState.NOT_STARTED);
        change.setCreatedAt(now);
        change.setUpdatedAt(now);
        change = changeRepository.saveAndFlush(change);

        complete(operation, change, null, now);
        acceptedAudit(operation, actor, project, "DEVELOPMENT_CHANGE_CREATED", 1);
        return CommandOutcome.accepted(response(operation, change, false));
    }

    private CommandOutcome transitionInTransaction(
            AuthenticatedOperator authenticated,
            Long projectId,
            UUID changeKey,
            UUID idempotencyKey,
            DevelopmentChangeOperationKind kind,
            DevelopmentChangeStatus targetStatus) {
        ProjectEntity project = requireProject(projectId);
        OperatorEntity actor = requireActor(authenticated);
        String requestFingerprint = fingerprint(
                kind, actor.getId(), projectId, changeKey, idempotencyKey);
        String preliminaryTarget = fingerprint("CHANGE", projectId, changeKey);
        if (idempotencyKey == null || changeKey == null) {
            return denied(actor, project, requestFingerprint, preliminaryTarget,
                    V2FailureCategory.VALIDATION,
                    "DEVELOPMENT_CHANGE_REQUEST_INVALID",
                    "Se requieren changeKey e idempotency key válidos.");
        }
        DevelopmentChangeOperationEntity replay = existingOperation(
                actor.getId(), kind, idempotencyKey);
        if (replay != null) {
            return replay(replay, projectId, requestFingerprint);
        }
        DevelopmentChangePolicy.Decision decision = policy.decide(projectId, false);
        if (!decision.allowed()) {
            return policyDenied(actor, project, requestFingerprint, preliminaryTarget, decision);
        }
        ChangeLookup changeLookup = lockedChange(projectId, changeKey, actor, project,
                requestFingerprint, preliminaryTarget);
        if (changeLookup.denied() != null) {
            return changeLookup.denied();
        }
        DevelopmentChangeEntity change = changeLookup.change();
        String targetFingerprint = changeTargetFingerprint(change);
        if (change.getStatus() == targetStatus) {
            return denied(actor, project, requestFingerprint, targetFingerprint,
                    V2FailureCategory.VALIDATION,
                    "DEVELOPMENT_CHANGE_ALREADY_IN_TARGET_STATE",
                    "El cambio ya está en el estado solicitado.");
        }
        boolean validSource = kind == DevelopmentChangeOperationKind.PAUSE
                ? change.getStatus() == DevelopmentChangeStatus.OPEN
                : change.getStatus() == DevelopmentChangeStatus.OPEN
                    || change.getStatus() == DevelopmentChangeStatus.PAUSED;
        if (!validSource) {
            return denied(actor, project, requestFingerprint, targetFingerprint,
                    V2FailureCategory.VALIDATION,
                    "DEVELOPMENT_CHANGE_TRANSITION_INVALID",
                    "El estado actual no permite la transición solicitada.");
        }
        if (activeSession(change) != null) {
            return denied(actor, project, requestFingerprint, targetFingerprint,
                    V2FailureCategory.OWNERSHIP,
                    "DEVELOPMENT_CHANGE_ACTIVE_SESSION_PRESENT",
                    "Cierra o libera primero la sesión activa del cambio.");
        }

        Instant now = Instant.now();
        DevelopmentChangeOperationEntity operation = requestedOperation(
                actor, project, idempotencyKey, kind, requestFingerprint,
                targetFingerprint, change, null, now);
        operationRepository.saveAndFlush(operation);
        change.setStatus(targetStatus);
        change.setUpdatedAt(now);
        changeRepository.saveAndFlush(change);
        complete(operation, change, null, now);
        acceptedAudit(operation, actor, project,
                kind == DevelopmentChangeOperationKind.PAUSE
                        ? "DEVELOPMENT_CHANGE_PAUSED"
                        : "DEVELOPMENT_CHANGE_ABANDONED",
                1);
        return CommandOutcome.accepted(response(operation, change, false));
    }

    private ChangeLookup lockedChange(
            Long projectId,
            UUID changeKey,
            OperatorEntity actor,
            ProjectEntity project,
            String requestFingerprint,
            String targetFingerprint) {
        DevelopmentChangeEntity change = changeRepository.findByChangeKeyForUpdate(changeKey).orElse(null);
        if (change == null || change.getProject() == null
                || !projectId.equals(change.getProject().getId())) {
            return new ChangeLookup(null, denied(actor, project, requestFingerprint, targetFingerprint,
                    V2FailureCategory.OWNERSHIP,
                    "DEVELOPMENT_CHANGE_OWNERSHIP_MISMATCH",
                    "El cambio no pertenece exactamente al proyecto solicitado."));
        }
        return new ChangeLookup(change, null);
    }

    private CommandOutcome bindInTransaction(
            AuthenticatedOperator authenticated,
            Long projectId,
            UUID changeKey,
            Long sessionId,
            UUID idempotencyKey) {
        ProjectEntity project = requireProject(projectId);
        OperatorEntity actor = requireActor(authenticated);
        String requestFingerprint = fingerprint(
                "SESSION_BIND", actor.getId(), projectId, changeKey, sessionId, idempotencyKey);
        String preliminaryTarget = fingerprint("CHANGE_SESSION", projectId, changeKey, sessionId);
        if (idempotencyKey == null || changeKey == null || sessionId == null || sessionId <= 0) {
            return denied(actor, project, requestFingerprint, preliminaryTarget,
                    V2FailureCategory.VALIDATION,
                    "DEVELOPMENT_CHANGE_REQUEST_INVALID",
                    "Se requieren changeKey, sessionId e idempotency key válidos.");
        }
        DevelopmentChangeOperationEntity replay = existingOperation(
                actor.getId(), DevelopmentChangeOperationKind.SESSION_BIND, idempotencyKey);
        if (replay != null) {
            return replay(replay, projectId, requestFingerprint);
        }
        DevelopmentChangePolicy.Decision decision = policy.decide(projectId, true);
        if (!decision.allowed()) {
            return policyDenied(actor, project, requestFingerprint, preliminaryTarget, decision);
        }
        ChangeLookup changeLookup = lockedChange(projectId, changeKey, actor, project,
                requestFingerprint, preliminaryTarget);
        if (changeLookup.denied() != null) {
            return changeLookup.denied();
        }
        DevelopmentChangeEntity change = changeLookup.change();
        String targetFingerprint = changeTargetFingerprint(change, sessionId);
        if (change.getStatus() != DevelopmentChangeStatus.OPEN) {
            return denied(actor, project, requestFingerprint, targetFingerprint,
                    V2FailureCategory.VALIDATION,
                    "DEVELOPMENT_CHANGE_NOT_OPEN",
                    "Sólo un cambio abierto puede recibir una sesión.");
        }
        if (change.getWorkspaceState() != DevelopmentChangeWorkspaceState.READY
                || change.getSourceState() == DevelopmentChangeSourceState.STALE
                || change.getSourceState() == DevelopmentChangeSourceState.BLOCKED) {
            return denied(actor, project, requestFingerprint, targetFingerprint,
                    V2FailureCategory.OWNERSHIP,
                    "DEVELOPMENT_CHANGE_WORKSPACE_NOT_READY",
                    "El workspace exacto debe estar listo antes de vincular una sesión.");
        }
        WorkSessionEntity session = workSessionRepository
                .findLockedWithProjectAndDevelopmentChangeById(sessionId)
                .orElse(null);
        if (!exactSessionOwnership(change, session)) {
            return denied(actor, project, requestFingerprint, targetFingerprint,
                    V2FailureCategory.OWNERSHIP,
                    "DEVELOPMENT_CHANGE_SESSION_OWNERSHIP_MISMATCH",
                    "La sesión no coincide con la identidad server-owned del cambio.");
        }
        WorkSessionEntity active = activeSession(change);
        if (active != null && !sessionId.equals(active.getId())) {
            return denied(actor, project, requestFingerprint, targetFingerprint,
                    V2FailureCategory.OWNERSHIP,
                    "DEVELOPMENT_CHANGE_ACTIVE_SESSION_CONFLICT",
                    "El cambio ya tiene otra sesión activa.");
        }

        Instant now = Instant.now();
        DevelopmentChangeOperationEntity operation = requestedOperation(
                actor, project, idempotencyKey, DevelopmentChangeOperationKind.SESSION_BIND,
                requestFingerprint, targetFingerprint, change, session, now);
        operationRepository.saveAndFlush(operation);
        session.setDevelopmentChange(change);
        workSessionRepository.saveAndFlush(session);
        change.setUpdatedAt(now);
        changeRepository.saveAndFlush(change);
        complete(operation, change, session, now);
        acceptedAudit(operation, actor, project, "DEVELOPMENT_CHANGE_SESSION_BOUND", 1);
        return CommandOutcome.accepted(response(operation, change, false));
    }

    private boolean exactSessionOwnership(
            DevelopmentChangeEntity change,
            WorkSessionEntity session) {
        if (session == null || session.getProject() == null
                || !Objects.equals(session.getProject().getId(), change.getProject().getId())
                || !ACTIVE_SESSION_STATES.contains(session.getStatus())
                || session.getExecutionTarget() != ExecutionTarget.REMOTE
                || session.getRemoteSessionId() == null
                || !Objects.equals(session.getRemoteWorkloadKind(),
                        ProjectCodexIdentity.WORKLOAD_KIND)
                || !Objects.equals(session.getSelectedWorkerId(), change.getSelectedWorkerId())
                || !Objects.equals(session.getWorkspaceBranch(), change.getWorkspaceBranch())
                || !Objects.equals(session.getWorkspaceIdentity(), change.getWorkspaceIdentity())
                || !Objects.equals("refs/heads/" + session.getBaseBranch(), change.getBaseRef())) {
            return false;
        }
        return session.getDevelopmentChange() == null
                || Objects.equals(session.getDevelopmentChange().getId(), change.getId());
    }

    private CommandOutcome replay(
            DevelopmentChangeOperationEntity operation,
            Long projectId,
            String requestFingerprint) {
        if (!Objects.equals(operation.getProject().getId(), projectId)
                || !Objects.equals(operation.getRequestFingerprintSha256(), requestFingerprint)) {
            return denied(
                    operation.getOperator(),
                    operation.getProject(),
                    requestFingerprint,
                    operation.getTargetFingerprintSha256(),
                    V2FailureCategory.OWNERSHIP,
                    "DEVELOPMENT_CHANGE_IDEMPOTENCY_CONFLICT",
                    "La idempotency key ya pertenece a otra solicitud.");
        }
        if (operation.getState() != DevelopmentChangeOperationState.SUCCEEDED
                || operation.getDevelopmentChange() == null
                || operation.getReceiptSha256() == null) {
            return denied(
                    operation.getOperator(),
                    operation.getProject(),
                    requestFingerprint,
                    operation.getTargetFingerprintSha256(),
                    V2FailureCategory.OWNERSHIP,
                    "DEVELOPMENT_CHANGE_OPERATION_INCOMPLETE",
                    "La operación durable no tiene un recibo terminal verificable.");
        }
        return CommandOutcome.accepted(response(
                operation, operation.getDevelopmentChange(), true));
    }

    private DevelopmentChangeOperationEntity existingOperation(
            Long operatorId,
            DevelopmentChangeOperationKind kind,
            UUID idempotencyKey) {
        return operationRepository
                .findByOperatorIdAndOperationKindAndIdempotencyKey(
                        operatorId, kind, idempotencyKey)
                .orElse(null);
    }

    private DevelopmentChangeOperationEntity requestedOperation(
            OperatorEntity actor,
            ProjectEntity project,
            UUID idempotencyKey,
            DevelopmentChangeOperationKind kind,
            String requestFingerprint,
            String targetFingerprint,
            DevelopmentChangeEntity change,
            WorkSessionEntity session,
            Instant now) {
        DevelopmentChangeOperationEntity operation = new DevelopmentChangeOperationEntity();
        operation.setOperationId(UUID.randomUUID());
        operation.setOperator(actor);
        operation.setProject(project);
        operation.setIdempotencyKey(idempotencyKey);
        operation.setOperationKind(kind);
        operation.setRequestFingerprintSha256(requestFingerprint);
        operation.setTargetFingerprintSha256(targetFingerprint);
        operation.setState(DevelopmentChangeOperationState.REQUESTED);
        operation.setRevision(0);
        operation.setDevelopmentChange(change);
        operation.setWorkSession(session);
        operation.setRequestedAt(now);
        operation.setUpdatedAt(now);
        return operation;
    }

    private void complete(
            DevelopmentChangeOperationEntity operation,
            DevelopmentChangeEntity change,
            WorkSessionEntity session,
            Instant now) {
        operation.setDevelopmentChange(change);
        operation.setWorkSession(session);
        operation.setState(DevelopmentChangeOperationState.SUCCEEDED);
        operation.setRevision(1);
        operation.setReceiptSha256(fingerprint(
                operation.getOperationId(), operation.getOperationKind(),
                operation.getProject().getId(), change.getChangeKey(),
                session == null ? null : session.getId(),
                operation.getRequestFingerprintSha256(),
                operation.getTargetFingerprintSha256(), "SUCCEEDED", 1));
        operation.setCompletedAt(now);
        operation.setUpdatedAt(now);
        operationRepository.saveAndFlush(operation);
    }

    private void acceptedAudit(
            DevelopmentChangeOperationEntity operation,
            OperatorEntity actor,
            ProjectEntity project,
            String eventType,
            int itemCount) {
        auditService.record(new V2AuditFact(
                operation.getOperationId(),
                project.getId(),
                actor.getId(),
                DevelopmentChangePolicy.CAPABILITY,
                eventType,
                "SUCCEEDED",
                operation.getRevision(),
                operation.getRequestFingerprintSha256(),
                operation.getTargetFingerprintSha256(),
                null,
                null,
                itemCount,
                0,
                operation.getCompletedAt()));
    }

    private CommandOutcome policyDenied(
            OperatorEntity actor,
            ProjectEntity project,
            String requestFingerprint,
            String targetFingerprint,
            DevelopmentChangePolicy.Decision decision) {
        return denied(actor, project, requestFingerprint, targetFingerprint,
                V2FailureCategory.POLICY,
                decision.failureCode(),
                "La capacidad de DevelopmentChange no está habilitada para este proyecto.");
    }

    private CommandOutcome denied(
            OperatorEntity actor,
            ProjectEntity project,
            String requestFingerprint,
            String targetFingerprint,
            V2FailureCategory category,
            String code,
            String message) {
        UUID auditOperationId = UUID.randomUUID();
        Instant now = Instant.now();
        auditService.record(new V2AuditFact(
                auditOperationId,
                project.getId(),
                actor.getId(),
                DevelopmentChangePolicy.CAPABILITY,
                "DEVELOPMENT_CHANGE_MUTATION_DENIED",
                "DENIED",
                0,
                requestFingerprint,
                targetFingerprint,
                category,
                code,
                0,
                0,
                now));
        DevelopmentChangeActionResponse action = category == V2FailureCategory.POLICY
                ? DevelopmentChangeActionResponse.waitForEnablement()
                : DevelopmentChangeActionResponse.none();
        return CommandOutcome.rejected(rejected(category, code, message, action));
    }

    private DevelopmentChangeRejectedException rejected(
            V2FailureCategory category,
            String code,
            String message,
            DevelopmentChangeActionResponse action) {
        return new DevelopmentChangeRejectedException(category, code, message, action);
    }

    private DevelopmentChangeMutationResponse response(
            DevelopmentChangeOperationEntity operation,
            DevelopmentChangeEntity change,
            boolean replayed) {
        return new DevelopmentChangeMutationResponse(
                operation.getOperationId(),
                operation.getReceiptSha256(),
                replayed,
                toResponse(change));
    }

    private DevelopmentChangeResponse toResponse(DevelopmentChangeEntity change) {
        WorkSessionEntity active = activeSession(change);
        DevelopmentChangePolicy.Decision mutationDecision =
                policy.decide(change.getProject().getId(), false);
        DevelopmentChangePolicy.Decision bindingDecision =
                policy.decide(change.getProject().getId(), true);
        DevelopmentChangePolicy.Decision workspaceDecision =
                policy.decideWorkspace(change.getProject().getId(), false);
        DevelopmentChangePolicy.Decision reconciliationDecision =
                policy.decideWorkspace(change.getProject().getId(), true);
        boolean mutationsEnabled = mutationDecision.allowed();
        boolean workspaceOperationsEnabled = workspaceDecision.allowed();
        DevelopmentChangePhase phase = switch (change.getStatus()) {
            case OPEN -> active == null
                    ? DevelopmentChangePhase.READY
                    : DevelopmentChangePhase.ACTIVE;
            case PAUSED -> DevelopmentChangePhase.PAUSED;
            case ABANDONED -> DevelopmentChangePhase.ABANDONED;
            case COMPLETED -> DevelopmentChangePhase.COMPLETED;
        };
        DevelopmentChangeActionResponse primaryAction;
        if (!mutationsEnabled) {
            primaryAction = DevelopmentChangeActionResponse.waitForEnablement();
        } else if (change.getStatus() != DevelopmentChangeStatus.OPEN) {
            primaryAction = DevelopmentChangeActionResponse.none();
        } else if (change.getWorkspaceState() == DevelopmentChangeWorkspaceState.UNCERTAIN) {
            primaryAction = reconciliationDecision.allowed()
                    ? DevelopmentChangeActionResponse.reconcileWorkspace()
                    : DevelopmentChangeActionResponse.waitForEnablement();
        } else if (change.getWorkspaceState() == DevelopmentChangeWorkspaceState.BLOCKED) {
            primaryAction = workspaceOperationsEnabled
                    ? DevelopmentChangeActionResponse.inspectWorkspace()
                    : DevelopmentChangeActionResponse.waitForEnablement();
        } else if (change.getWorkspaceState()
                == DevelopmentChangeWorkspaceState.NOT_PROVISIONED) {
            primaryAction = workspaceOperationsEnabled
                    ? DevelopmentChangeActionResponse.provisionWorkspace()
                    : DevelopmentChangeActionResponse.waitForEnablement();
        } else if (change.getSourceState() == DevelopmentChangeSourceState.STALE) {
            primaryAction = DevelopmentChangeActionResponse.reviewStaleSource();
        } else if (active != null) {
            primaryAction = DevelopmentChangeActionResponse.continueSession();
        } else if (bindingDecision.allowed()) {
            primaryAction = DevelopmentChangeActionResponse.bindSession();
        } else {
            primaryAction = DevelopmentChangeActionResponse.waitForEnablement();
        }
        return new DevelopmentChangeResponse(
                change.getChangeKey(),
                change.getProject().getId(),
                change.getTitle(),
                change.getStatus(),
                change.getBaseRef(),
                change.getBaseCommit(),
                change.getWorkspaceBranch(),
                change.getWorkspaceIdentity(),
                change.getSelectedWorkerId(),
                change.getProjectPolicyRevision(),
                change.getSourceRevision(),
                change.getSourceFingerprintSha256(),
                change.getSourceState(),
                currentCanonicalCommit(change),
                change.getWorkspaceState(),
                change.getWorkspaceOperationRevision(),
                change.getWorkspaceObservationSha256(),
                workspaceOperationsEnabled,
                change.getValidationState(),
                change.getReviewState(),
                change.getIntegrationState(),
                change.getReleaseState(),
                active == null ? null : active.getId(),
                phase,
                mutationsEnabled,
                primaryAction,
                change.getCreatedAt(),
                change.getUpdatedAt(),
                change.getVersion());
    }

    private String currentCanonicalCommit(DevelopmentChangeEntity change) {
        return change.getObservedCanonicalCommit() == null
                ? change.getBaseCommit()
                : change.getObservedCanonicalCommit();
    }

    private WorkSessionEntity activeSession(DevelopmentChangeEntity change) {
        if (change == null || change.getId() == null) {
            return null;
        }
        return workSessionRepository
                .findFirstByDevelopmentChangeIdAndStatusInOrderByOpenedAtAsc(
                        change.getId(), ACTIVE_SESSION_STATES)
                .orElse(null);
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
            throw rejected(
                    V2FailureCategory.POLICY,
                    "DEVELOPMENT_CHANGE_AUTHENTICATION_REQUIRED",
                    "Se requiere un operador autenticado.",
                    DevelopmentChangeActionResponse.none());
        }
        OperatorEntity actor = operatorRepository
                .findByIdForUpdate(authenticated.operatorId()).orElse(null);
        if (actor == null || !actor.isActive()) {
            throw rejected(
                    V2FailureCategory.POLICY,
                    "DEVELOPMENT_CHANGE_OPERATOR_INACTIVE",
                    "El operador autenticado no está activo.",
                    DevelopmentChangeActionResponse.none());
        }
        return actor;
    }

    private boolean validBaseBranch(String baseBranch) {
        return baseBranch != null && !baseBranch.isBlank()
                && !baseBranch.startsWith("refs/")
                && !baseBranch.contains("..")
                && !baseBranch.contains("//")
                && baseBranch.matches("[A-Za-z0-9][A-Za-z0-9._/-]{0,199}");
    }

    private String changeTargetFingerprint(DevelopmentChangeEntity change, Object... suffix) {
        return fingerprint(
                change.getProject().getId(),
                change.getChangeKey(),
                change.getBaseRef(),
                change.getBaseCommit(),
                change.getWorkspaceBranch(),
                change.getWorkspaceIdentity(),
                change.getSelectedWorkerId(),
                change.getVersion(),
                List.of(suffix));
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

    private record CommandOutcome(
            DevelopmentChangeMutationResponse response,
            DevelopmentChangeRejectedException rejection) {

        private static CommandOutcome accepted(DevelopmentChangeMutationResponse response) {
            return new CommandOutcome(response, null);
        }

        private static CommandOutcome rejected(DevelopmentChangeRejectedException rejection) {
            return new CommandOutcome(null, rejection);
        }
    }

    private record ChangeLookup(
            DevelopmentChangeEntity change,
            CommandOutcome denied) {
    }
}
