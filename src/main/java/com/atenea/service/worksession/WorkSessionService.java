package com.atenea.service.worksession;

import com.atenea.attachments.NewWorkSessionAttachmentPolicySnapshotter;
import com.atenea.api.worksession.CreateWorkSessionRequest;
import com.atenea.api.worksession.CloseWorkSessionConversationViewResponse;
import com.atenea.api.worksession.ResolveWorkSessionConversationViewResponse;
import com.atenea.api.worksession.ResolveWorkSessionRequest;
import com.atenea.api.worksession.ResolveWorkSessionResponse;
import com.atenea.api.worksession.ResolveWorkSessionViewResponse;
import com.atenea.api.worksession.SessionTurnResponse;
import com.atenea.api.worksession.SessionOperationalSnapshotResponse;
import com.atenea.api.worksession.WorkSessionConversationViewResponse;
import com.atenea.api.worksession.WorkSessionResponse;
import com.atenea.api.worksession.WorkSessionOperationalState;
import com.atenea.api.worksession.WorkSessionViewLatestRunResponse;
import com.atenea.api.worksession.WorkSessionViewResponse;
import com.atenea.github.GitHubClient;
import com.atenea.github.GitHubIntegrationException;
import com.atenea.github.GitHubPullRequest;
import com.atenea.github.GitHubRepositoryRef;
import com.atenea.persistence.worksession.AgentRunEntity;
import com.atenea.persistence.project.ProjectEntity;
import com.atenea.persistence.project.ProjectRepository;
import com.atenea.persistence.worksession.AgentRunRepository;
import com.atenea.persistence.worksession.AgentRunStatus;
import com.atenea.persistence.worksession.AgentRunRecoveryNextAction;
import com.atenea.persistence.worksession.ExecutionTarget;
import com.atenea.persistence.worksession.RemoteCloseState;
import com.atenea.persistence.worksession.WorkSessionEntity;
import com.atenea.persistence.worksession.WorkSessionPullRequestStatus;
import com.atenea.persistence.worksession.WorkSessionRepository;
import com.atenea.persistence.worksession.WorkSessionStatus;
import com.atenea.mobilepush.MobilePushDispatchService;
import com.atenea.remoteworker.ProjectCodexIdentity;
import com.atenea.remoteworker.RemoteWorkerClient;
import com.atenea.remoteworker.RemoteWorkerException;
import com.atenea.remoteworker.RemoteWorkerFailureCategory;
import com.atenea.remoteworker.RemoteWorkerProperties;
import com.atenea.remoteworker.RemoteRoutingSelector;
import com.atenea.service.project.WorkspaceRepositoryPathValidator;
import com.atenea.service.git.GitRepositoryService;
import com.atenea.service.git.GitRepositoryOperationException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.TransactionDefinition;

@Service
public class WorkSessionService {

    private static final int RECENT_TURN_LIMIT = 20;

    private final ProjectRepository projectRepository;
    private final WorkSessionRepository workSessionRepository;
    private final MobilePushDispatchService mobilePushDispatchService;
    private final WorkspaceRepositoryPathValidator workspaceRepositoryPathValidator;
    private final GitRepositoryService gitRepositoryService;
    private final SessionOperationalSnapshotService sessionOperationalSnapshotService;
    private final AgentRunRepository agentRunRepository;
    private final SessionTurnService sessionTurnService;
    private final AgentRunReconciliationService agentRunReconciliationService;
    private final SessionBranchService sessionBranchService;
    private final GitHubClient gitHubClient;
    private final NewWorkSessionAttachmentPolicySnapshotter attachmentPolicySnapshotter;
    private final RemoteWorkerClient remoteWorkerClient;
    private final RemoteWorkerProperties remoteWorkerProperties;
    private final TransactionTemplate closeTransaction;
    private RemoteRoutingSelector remoteRoutingSelector;

    public WorkSessionService(
            ProjectRepository projectRepository,
            WorkSessionRepository workSessionRepository,
            MobilePushDispatchService mobilePushDispatchService,
            WorkspaceRepositoryPathValidator workspaceRepositoryPathValidator,
            GitRepositoryService gitRepositoryService,
            SessionOperationalSnapshotService sessionOperationalSnapshotService,
            AgentRunRepository agentRunRepository,
            SessionTurnService sessionTurnService,
            AgentRunReconciliationService agentRunReconciliationService,
            SessionBranchService sessionBranchService,
            GitHubClient gitHubClient,
            NewWorkSessionAttachmentPolicySnapshotter attachmentPolicySnapshotter,
            RemoteWorkerClient remoteWorkerClient,
            RemoteWorkerProperties remoteWorkerProperties,
            PlatformTransactionManager transactionManager
    ) {
        this.projectRepository = projectRepository;
        this.workSessionRepository = workSessionRepository;
        this.mobilePushDispatchService = mobilePushDispatchService;
        this.workspaceRepositoryPathValidator = workspaceRepositoryPathValidator;
        this.gitRepositoryService = gitRepositoryService;
        this.sessionOperationalSnapshotService = sessionOperationalSnapshotService;
        this.agentRunRepository = agentRunRepository;
        this.sessionTurnService = sessionTurnService;
        this.agentRunReconciliationService = agentRunReconciliationService;
        this.sessionBranchService = sessionBranchService;
        this.gitHubClient = gitHubClient;
        this.attachmentPolicySnapshotter = attachmentPolicySnapshotter;
        this.remoteWorkerClient = remoteWorkerClient;
        this.remoteWorkerProperties = remoteWorkerProperties;
        this.closeTransaction = new TransactionTemplate(transactionManager);
        this.closeTransaction.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Autowired(required = false)
    void setRemoteRoutingSelector(RemoteRoutingSelector remoteRoutingSelector) {
        this.remoteRoutingSelector = remoteRoutingSelector;
    }

    @Transactional
    public WorkSessionResponse openSession(Long projectId, CreateWorkSessionRequest request) {
        ProjectEntity project = projectRepository.findById(projectId)
                .orElseThrow(() -> new WorkSessionProjectNotFoundException(projectId));

        if (workSessionRepository.existsByProjectIdAndStatus(projectId, WorkSessionStatus.OPEN)
                || workSessionRepository.existsByProjectIdAndStatus(projectId, WorkSessionStatus.CLOSING)) {
            throw new OpenWorkSessionAlreadyExistsException(projectId);
        }

        String normalizedRepoPath = workspaceRepositoryPathValidator.normalizeConfiguredRepoPath(project.getRepoPath());
        String currentBranch = resolveCurrentBranch(normalizedRepoPath);
        String normalizedBaseBranch = normalizeNullableText(request.baseBranch());
        String projectDefaultBaseBranch = normalizeNullableText(project.getDefaultBaseBranch());
        String baseBranch = normalizedBaseBranch != null
                ? normalizedBaseBranch
                : (projectDefaultBaseBranch != null ? projectDefaultBaseBranch : currentBranch);

        Instant now = Instant.now();

        WorkSessionEntity session = new WorkSessionEntity();
        session.setProject(project);
        session.setStatus(WorkSessionStatus.OPEN);
        session.setTitle(request.title().trim());
        session.setBaseBranch(baseBranch);
        session.setWorkspaceBranch(null);
        session.setExternalThreadId(null);
        session.setExecutionTarget(ExecutionTarget.LOCAL);
        session.setSelectedWorkerId(null);
        session.setWorkspaceIdentity("local:pending");
        session.setRemoteSessionId(null);
        session.setRemoteWorkloadKind(null);
        session.setRemoteCloseState(RemoteCloseState.NOT_REQUIRED);
        session.setAttachmentPolicyRevision(null);
        session.setPullRequestUrl(null);
        session.setPullRequestStatus(WorkSessionPullRequestStatus.NOT_CREATED);
        session.setFinalCommitSha(null);
        session.setOpenedAt(now);
        session.setLastActivityAt(now);
        session.setPublishedAt(null);
        session.setCloseBlockedState(null);
        session.setCloseBlockedReason(null);
        session.setCloseBlockedAction(null);
        session.setCloseRetryable(false);
        session.setClosedAt(null);
        session.setCreatedAt(now);
        session.setUpdatedAt(now);

        WorkSessionEntity persistedSession = workSessionRepository.save(session);
        if (remoteRoutingSelector == null) {
            persistedSession.setWorkspaceIdentity("local:work-session:" + persistedSession.getId());
        } else {
            remoteRoutingSelector.pinNewSession(persistedSession);
        }
        attachmentPolicySnapshotter.snapshotNewSession(persistedSession);
        persistedSession.setWorkspaceBranch(sessionBranchService.prepareWorkspaceBranch(persistedSession, normalizedRepoPath));
        persistedSession.setUpdatedAt(Instant.now());

        return toResponse(workSessionRepository.save(persistedSession));
    }

    @Transactional
    public ResolveWorkSessionResponse resolveSession(Long projectId, ResolveWorkSessionRequest request) {
        projectRepository.findById(projectId)
                .orElseThrow(() -> new WorkSessionProjectNotFoundException(projectId));

        WorkSessionEntity openSession = nullSafe(workSessionRepository.findByProjectIdAndStatus(projectId, WorkSessionStatus.OPEN))
                .orElseGet(() -> nullSafe(workSessionRepository.findByProjectIdAndStatus(projectId, WorkSessionStatus.CLOSING))
                        .orElse(null));
        if (openSession != null) {
            if (openSession.getStatus() == WorkSessionStatus.OPEN) {
                prepareWorkspaceBranch(openSession);
            }
            return new ResolveWorkSessionResponse(false, toResponse(openSession));
        }

        if (request == null || normalizeNullableText(request.title()) == null) {
            throw new IllegalArgumentException("Session title is required when no open WorkSession exists");
        }

        WorkSessionResponse createdSession = openSession(
                projectId,
                new CreateWorkSessionRequest(request.title(), request.baseBranch()));
        return new ResolveWorkSessionResponse(true, createdSession);
    }

    @Transactional
    public ResolveWorkSessionViewResponse resolveSessionView(Long projectId, ResolveWorkSessionRequest request) {
        ResolveWorkSessionResponse resolved = resolveSession(projectId, request);
        WorkSessionViewResponse view = getSessionView(resolved.session().id());
        return new ResolveWorkSessionViewResponse(resolved.created(), view);
    }

    @Transactional
    public ResolveWorkSessionConversationViewResponse resolveSessionConversationView(
            Long projectId,
            ResolveWorkSessionRequest request
    ) {
        ResolveWorkSessionResponse resolved = resolveSession(projectId, request);
        WorkSessionConversationViewResponse view = getSessionConversationView(resolved.session().id());
        return new ResolveWorkSessionConversationViewResponse(resolved.created(), view);
    }

    @Transactional(readOnly = true)
    public WorkSessionResponse getSession(Long sessionId) {
        WorkSessionEntity session = workSessionRepository.findWithProjectById(sessionId)
                .orElseThrow(() -> new WorkSessionNotFoundException(sessionId));
        return toResponse(session);
    }

    @Transactional(readOnly = true)
    public WorkSessionViewResponse getSessionView(Long sessionId) {
        WorkSessionEntity session = workSessionRepository.findWithProjectById(sessionId)
                .orElseThrow(() -> new WorkSessionNotFoundException(sessionId));

        WorkSessionResponse sessionResponse = toResponse(session);
        AgentRunEntity latestRun = agentRunRepository.findFirstBySessionIdOrderByCreatedAtDesc(sessionId).orElse(null);
        AgentRunEntity latestSucceededRun = agentRunRepository.findFirstBySessionIdAndStatusOrderByCreatedAtDesc(
                sessionId,
                AgentRunStatus.SUCCEEDED).orElse(null);

        return new WorkSessionViewResponse(
                sessionResponse,
                sessionResponse.repoState().runInProgress(),
                canCreateTurn(sessionResponse),
                latestRun == null ? null : toLatestRunResponse(latestRun),
                latestRun != null && latestRun.getStatus() == AgentRunStatus.FAILED
                        ? latestRun.getErrorSummary()
                        : null,
                latestSucceededRun == null ? null : latestSucceededRun.getOutputSummary()
        );
    }

    @Transactional(readOnly = true)
    public WorkSessionConversationViewResponse getSessionConversationView(Long sessionId) {
        WorkSessionViewResponse view = getSessionView(sessionId);
        List<SessionTurnResponse> turns = sessionTurnService.getTurns(sessionId, null, RECENT_TURN_LIMIT);
        long totalVisibleTurns = sessionTurnService.countVisibleTurns(sessionId);
        return new WorkSessionConversationViewResponse(
                view,
                turns,
                RECENT_TURN_LIMIT,
                totalVisibleTurns > RECENT_TURN_LIMIT
        );
    }

    @Transactional(readOnly = true)
    public boolean canCloseUnpublishedSession(Long sessionId) {
        WorkSessionEntity session = workSessionRepository.findWithProjectById(sessionId)
                .orElseThrow(() -> new WorkSessionNotFoundException(sessionId));
        if (session.getStatus() != WorkSessionStatus.OPEN
                || session.getPullRequestStatus() != WorkSessionPullRequestStatus.NOT_CREATED
                || agentRunRepository.existsBySessionIdAndStatusIn(
                        sessionId, AgentRunStatus.nonTerminalStatuses())) {
            return false;
        }
        try {
            String repoPath = workspaceRepositoryPathValidator
                    .normalizeConfiguredRepoPath(session.getProject().getRepoPath());
            String currentBranch = gitRepositoryService.getCurrentBranch(repoPath);
            String workspaceBranch = normalizeNullableText(session.getWorkspaceBranch());
            if (!gitRepositoryService.isWorkingTreeClean(repoPath)
                    || (!currentBranch.equals(session.getBaseBranch())
                        && !currentBranch.equals(workspaceBranch))) {
                return false;
            }
            return workspaceBranch == null
                    || !gitRepositoryService.branchExists(repoPath, workspaceBranch)
                    || !gitRepositoryService.branchContainsCommitsBeyond(
                            repoPath, session.getBaseBranch(), workspaceBranch);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    public WorkSessionResponse closeSession(Long sessionId) {
        ClosePreparation preparation = closeTransaction.execute(
                ignored -> prepareClose(sessionId));
        if (preparation.failure() != null) {
            throw preparation.failure();
        }
        if (preparation.localResponse() != null) {
            return preparation.localResponse();
        }

        RemoteCloseInvocation invocation = closeTransaction.execute(
                ignored -> persistRemoteCloseRequest(sessionId));
        RemoteWorkerClient.WorkspaceRelease receipt;
        try {
            receipt = remoteWorkerClient.releaseWorkspace(invocation.session());
        } catch (RemoteWorkerException exception) {
            WorkSessionCloseBlockedException failure = closeTransaction.execute(
                    ignored -> persistRemoteCloseFailure(
                            sessionId, invocation.operationId(), exception));
            throw failure;
        }
        return closeTransaction.execute(
                ignored -> persistReleasedClose(
                        sessionId, invocation.operationId(), receipt));
    }

    public CloseWorkSessionConversationViewResponse closeSessionConversationView(Long sessionId) {
        closeSession(sessionId);
        return new CloseWorkSessionConversationViewResponse(getSessionConversationView(sessionId));
    }

    private ClosePreparation prepareClose(Long sessionId) {
        WorkSessionEntity session = workSessionRepository.findWithProjectById(sessionId)
                .orElseThrow(() -> new WorkSessionNotFoundException(sessionId));

        if (session.getStatus() != WorkSessionStatus.OPEN
                && session.getStatus() != WorkSessionStatus.CLOSING) {
            throw new WorkSessionNotOpenException(sessionId, session.getStatus());
        }

        Instant now = Instant.now();
        session.setStatus(WorkSessionStatus.CLOSING);
        session.setClosedAt(null);
        clearCloseBlock(session);
        session.setUpdatedAt(now);

        try {
            reconcileClose(session);
            if (session.getExecutionTarget() == ExecutionTarget.LOCAL) {
                session.setStatus(WorkSessionStatus.CLOSED);
                session.setClosedAt(now);
                clearCloseBlock(session);
                session.setUpdatedAt(now);
                workSessionRepository.saveAndFlush(session);
                return new ClosePreparation(toResponse(session), null);
            }
            if (!ProjectCodexIdentity.matches(session)
                    || !remoteWorkerProperties.isRemoteCloseReleaseEnabledFor(
                            ProjectCodexIdentity.PROJECT_IDENTITY)) {
                blockClose(
                        session,
                        "remote_close_disabled",
                        "Exact remote workspace release is not enabled for this project",
                        "Enable the reviewed Atenea remote-close release gate before retrying close",
                        false);
            }
            workSessionRepository.saveAndFlush(session);
            return new ClosePreparation(null, null);
        } catch (WorkSessionCloseBlockedException exception) {
            workSessionRepository.saveAndFlush(session);
            return new ClosePreparation(null, exception);
        }
    }

    private RemoteCloseInvocation persistRemoteCloseRequest(Long sessionId) {
        WorkSessionEntity session = workSessionRepository.findLockedWithProjectById(sessionId)
                .orElseThrow(() -> new WorkSessionNotFoundException(sessionId));
        if (session.getStatus() != WorkSessionStatus.CLOSING
                || session.getExecutionTarget() != ExecutionTarget.REMOTE
                || !ProjectCodexIdentity.matches(session)) {
            throw new WorkSessionNotOpenException(sessionId, session.getStatus());
        }
        Instant now = Instant.now();
        if (session.getRemoteCloseState() == RemoteCloseState.NOT_STARTED) {
            session.setRemoteCloseOperationId(UUID.randomUUID());
            session.setRemoteCloseState(RemoteCloseState.REQUESTED);
            session.setRemoteCloseRevision(1);
            session.setRemoteCloseReceiptSha256(null);
            session.setRemoteCloseErrorCode(null);
            session.setRemoteCloseRequestedAt(now);
            session.setRemoteCloseUpdatedAt(now);
            session.setRemoteCloseReleasedAt(null);
        } else if (session.getRemoteCloseState() == RemoteCloseState.BLOCKED) {
            session.setRemoteCloseState(RemoteCloseState.RECONCILING);
            session.setRemoteCloseRevision(session.getRemoteCloseRevision() + 1);
            session.setRemoteCloseErrorCode(null);
            session.setRemoteCloseUpdatedAt(now);
        } else if (session.getRemoteCloseState() != RemoteCloseState.REQUESTED
                && session.getRemoteCloseState() != RemoteCloseState.RECONCILING) {
            throw new IllegalStateException(
                    "Persisted remote close state cannot invoke release: "
                            + session.getRemoteCloseState());
        }
        clearCloseBlock(session);
        WorkSessionEntity persisted = workSessionRepository.saveAndFlush(session);
        return new RemoteCloseInvocation(
                persisted.getRemoteCloseOperationId(), persisted);
    }

    private WorkSessionCloseBlockedException persistRemoteCloseFailure(
            Long sessionId,
            UUID operationId,
            RemoteWorkerException workerFailure
    ) {
        WorkSessionEntity session = exactRemoteCloseOwner(sessionId, operationId);
        boolean reconciling = workerFailure.getCategory() == RemoteWorkerFailureCategory.TRANSPORT
                || (workerFailure.getStatusCode() >= 500
                    && workerFailure.getCategory() != RemoteWorkerFailureCategory.PROTOCOL);
        String failureCode = workerFailure.getFailureCode() == null
                ? "REMOTE_CLOSE_REJECTED"
                : workerFailure.getFailureCode();
        String action = reconciling
                ? "Retry reconciliation with the same remote-close operation"
                : "Resolve the exact worker rejection before retrying remote close";
        Instant now = Instant.now();
        session.setRemoteCloseState(
                reconciling ? RemoteCloseState.RECONCILING : RemoteCloseState.BLOCKED);
        session.setRemoteCloseRevision(session.getRemoteCloseRevision() + 1);
        session.setRemoteCloseErrorCode(failureCode);
        session.setRemoteCloseUpdatedAt(now);
        session.setCloseBlockedState(failureCode);
        session.setCloseBlockedReason("Remote workspace release did not return an accepted receipt");
        session.setCloseBlockedAction(action);
        session.setCloseRetryable(reconciling || workerFailure.isRetryable());
        session.setUpdatedAt(now);
        workSessionRepository.saveAndFlush(session);
        mobilePushDispatchService.notifyCloseBlocked(
                session, failureCode, session.getCloseBlockedReason());
        return new WorkSessionCloseBlockedException(
                "WorkSession '%s' cannot finish closing: remote workspace release failed"
                        .formatted(sessionId),
                failureCode,
                session.getCloseBlockedReason(),
                action,
                session.isCloseRetryable(),
                List.of(
                        "state: " + failureCode,
                        "action: " + action,
                        "retryable: " + session.isCloseRetryable()));
    }

    private WorkSessionResponse persistReleasedClose(
            Long sessionId,
            UUID operationId,
            RemoteWorkerClient.WorkspaceRelease receipt
    ) {
        WorkSessionEntity session = exactRemoteCloseOwner(sessionId, operationId);
        if (!operationId.toString().equals(receipt.operationId())
                || !"RELEASED".equals(receipt.state())) {
            throw new IllegalStateException("Remote close receipt identity changed before commit");
        }
        Instant now = Instant.now();
        session.setRemoteCloseState(RemoteCloseState.RELEASED);
        session.setRemoteCloseRevision(Math.max(
                session.getRemoteCloseRevision() + 1, receipt.revision()));
        session.setRemoteCloseReceiptSha256(receipt.receiptSha256());
        session.setRemoteCloseErrorCode(null);
        session.setRemoteCloseUpdatedAt(now);
        session.setRemoteCloseReleasedAt(now);
        session.setStatus(WorkSessionStatus.CLOSED);
        session.setClosedAt(now);
        clearCloseBlock(session);
        session.setUpdatedAt(now);
        return toResponse(workSessionRepository.saveAndFlush(session));
    }

    private WorkSessionEntity exactRemoteCloseOwner(Long sessionId, UUID operationId) {
        WorkSessionEntity session = workSessionRepository.findLockedWithProjectById(sessionId)
                .orElseThrow(() -> new WorkSessionNotFoundException(sessionId));
        if (session.getStatus() != WorkSessionStatus.CLOSING
                || session.getExecutionTarget() != ExecutionTarget.REMOTE
                || !operationId.equals(session.getRemoteCloseOperationId())
                || !ProjectCodexIdentity.matches(session)) {
            throw new IllegalStateException("Persisted remote close ownership changed");
        }
        return session;
    }

    private record ClosePreparation(
            WorkSessionResponse localResponse,
            WorkSessionCloseBlockedException failure
    ) {
    }

    private record RemoteCloseInvocation(
            UUID operationId,
            WorkSessionEntity session
    ) {
    }

    private String resolveCurrentBranch(String repoPath) {
        try {
            return gitRepositoryService.getCurrentBranch(repoPath);
        } catch (GitRepositoryOperationException exception) {
            throw new WorkSessionOperationBlockedException(
                    "Project repository is not operational for WorkSession opening: " + exception.getMessage());
        }
    }

    private void prepareWorkspaceBranch(WorkSessionEntity session) {
        String normalizedRepoPath = workspaceRepositoryPathValidator.normalizeConfiguredRepoPath(session.getProject().getRepoPath());
        String workspaceBranch = sessionBranchService.prepareWorkspaceBranch(session, normalizedRepoPath);
        if (!workspaceBranch.equals(session.getWorkspaceBranch())) {
            session.setWorkspaceBranch(workspaceBranch);
            session.setUpdatedAt(Instant.now());
            workSessionRepository.save(session);
        }
    }

    WorkSessionResponse toResponse(WorkSessionEntity session) {
        SessionOperationalSnapshotResponse snapshot = sessionOperationalSnapshotService.snapshot(session);
        return new WorkSessionResponse(
                session.getId(),
                session.getProject().getId(),
                session.getStatus(),
                resolveOperationalState(session, snapshot),
                session.getTitle(),
                session.getBaseBranch(),
                session.getWorkspaceBranch(),
                session.getExternalThreadId(),
                session.getPullRequestUrl(),
                session.getPullRequestStatus(),
                session.getFinalCommitSha(),
                session.getOpenedAt(),
                session.getLastActivityAt(),
                session.getPublishedAt(),
                session.getClosedAt(),
                session.getCloseBlockedState(),
                session.getCloseBlockedReason(),
                session.getCloseBlockedAction(),
                session.isCloseRetryable(),
                session.getExecutionTarget(),
                session.getSelectedWorkerId(),
                session.getWorkspaceIdentity(),
                snapshot,
                session.getRemoteCloseState(),
                session.getRemoteCloseErrorCode(),
                remoteCloseNextAction(session.getRemoteCloseState())
        );
    }

    private WorkSessionViewLatestRunResponse toLatestRunResponse(AgentRunEntity run) {
        return new WorkSessionViewLatestRunResponse(
                run.getId(),
                run.getStatus(),
                run.getOriginTurn() == null ? null : run.getOriginTurn().getId(),
                run.getResultTurn() == null ? null : run.getResultTurn().getId(),
                run.getExternalTurnId(),
                run.getStartedAt(),
                run.getFinishedAt(),
                run.getOutputSummary(),
                run.getErrorSummary(),
                run.getExecutionTarget(),
                run.getSelectedWorkerId(),
                run.getWorkspaceIdentity(),
                run.getDispatchId(),
                run.getRemoteExecutionId(),
                run.getWorkloadClass(),
                run.getLifecycleRevision(),
                run.getStatusReason(),
                run.getFailureCode(),
                run.getRecoveryNextAction()
        );
    }

    private AgentRunRecoveryNextAction remoteCloseNextAction(RemoteCloseState state) {
        return switch (state) {
            case NOT_REQUIRED, NOT_STARTED, RELEASED -> AgentRunRecoveryNextAction.NONE;
            case REQUESTED, RECONCILING -> AgentRunRecoveryNextAction.REQUEST_RECONCILIATION;
            case BLOCKED -> AgentRunRecoveryNextAction.CONTACT_PLATFORM_ADMINISTRATOR;
            case UNVERIFIED_LEGACY -> AgentRunRecoveryNextAction.RECONCILE_REMOTE_CLOSE;
        };
    }

    private WorkSessionOperationalState resolveOperationalState(
            WorkSessionEntity session,
            SessionOperationalSnapshotResponse snapshot
    ) {
        if (session.getStatus() == WorkSessionStatus.CLOSED) {
            return WorkSessionOperationalState.CLOSED;
        }
        if (session.getStatus() == WorkSessionStatus.CLOSING) {
            return WorkSessionOperationalState.CLOSING;
        }
        if (session.getStatus() == WorkSessionStatus.DRAFT_BLOCKED) {
            return WorkSessionOperationalState.DRAFT_BLOCKED;
        }
        if (snapshot.runInProgress()) {
            return WorkSessionOperationalState.RUNNING;
        }
        return WorkSessionOperationalState.IDLE;
    }

    private boolean canCreateTurn(WorkSessionResponse session) {
        return session.status() == WorkSessionStatus.OPEN
                && session.operationalState() == WorkSessionOperationalState.IDLE;
    }

    private void reconcileClose(WorkSessionEntity session) {
        Long sessionId = session.getId();
        agentRunReconciliationService.reconcileSession(sessionId);
        if (agentRunRepository.existsBySessionIdAndStatus(sessionId, AgentRunStatus.RUNNING)
                || agentRunRepository.existsBySessionIdAndStatusIn(
                        sessionId,
                        AgentRunStatus.nonTerminalStatuses())) {
            blockClose(
                    session,
                    "running_run",
                    "WorkSession still has a running AgentRun",
                    "Wait for the run to finish or reconcile it before retrying close",
                    true);
        }

        String repoPath;
        try {
            repoPath = workspaceRepositoryPathValidator.normalizeConfiguredRepoPath(session.getProject().getRepoPath());
        } catch (RuntimeException exception) {
            blockClose(
                    session,
                    "repo_invalid",
                    exception.getMessage(),
                    "Fix the project repository path and retry close",
                    false);
            return;
        }

        String workspaceBranch = normalizeNullableText(session.getWorkspaceBranch());
        String baseBranch = session.getBaseBranch();

        String currentBranch;
        boolean workingTreeClean;
        try {
            currentBranch = gitRepositoryService.getCurrentBranch(repoPath);
            workingTreeClean = gitRepositoryService.isWorkingTreeClean(repoPath);
        } catch (GitRepositoryOperationException exception) {
            blockClose(
                    session,
                    "repo_unavailable",
                    "Could not inspect repository state: " + exception.getMessage(),
                    "Resolve the repository problem and retry close",
                    true);
            return;
        }

        if (workspaceBranch != null
                && !currentBranch.equals(baseBranch)
                && !currentBranch.equals(workspaceBranch)) {
            blockClose(
                    session,
                    "unexpected_branch",
                    "Repository is on branch '%s' but close only supports '%s' or '%s'"
                            .formatted(currentBranch, baseBranch, workspaceBranch),
                    "Checkout the session branch or the project base branch and retry close",
                    false);
        }

        if (!workingTreeClean) {
            blockClose(
                    session,
                    "dirty_worktree",
                    "Repository working tree is not clean",
                    "Clean or discard local changes manually before retrying close",
                    false);
        }

        boolean localWorkspaceBranchExists = workspaceBranch != null && gitRepositoryService.branchExists(repoPath, workspaceBranch);
        boolean sessionHasPublishedPullRequest = hasPublishedPullRequest(session);
        boolean remoteChecksAvailable = sessionHasPublishedPullRequest;
        if (remoteChecksAvailable) {
            try {
                gitRepositoryService.fetchOrigin(repoPath);
            } catch (GitRepositoryOperationException exception) {
                blockClose(
                        session,
                        "fetch_failed",
                        "Could not fetch origin: " + exception.getMessage(),
                        "Verify the repository remote configuration and retry close",
                        true);
            }
        }

        boolean remoteWorkspaceBranchExists = remoteChecksAvailable
                && workspaceBranch != null
                && gitRepositoryService.remoteBranchExists(repoPath, workspaceBranch);
        if (sessionHasPublishedPullRequest) {
            syncPullRequestStateForClose(session, repoPath);
            if (session.getPullRequestStatus() != WorkSessionPullRequestStatus.MERGED) {
                blockClose(
                        session,
                        "pull_request_not_merged",
                        "WorkSession pull request is not merged yet",
                        "Merge the pull request and retry close",
                        true);
            }
        } else {
            if (remoteWorkspaceBranchExists) {
                blockClose(
                        session,
                        "unexpected_remote_branch",
                        "Session branch still exists on origin even though the WorkSession was never published",
                        "Inspect the remote branch manually and remove it or publish properly before retrying close",
                        false);
            }
            if (localWorkspaceBranchExists
                    && gitRepositoryService.branchContainsCommitsBeyond(repoPath, baseBranch, workspaceBranch)) {
                blockClose(
                        session,
                        "unpublished_commits",
                        "Session branch contains commits that were never published",
                        "Publish the WorkSession or discard the branch changes manually before retrying close",
                        false);
            }
        }

        if (!currentBranch.equals(baseBranch)) {
            try {
                gitRepositoryService.checkoutBranch(repoPath, baseBranch);
                currentBranch = baseBranch;
            } catch (GitRepositoryOperationException exception) {
                blockClose(
                        session,
                        "checkout_base_failed",
                        "Could not switch back to base branch '%s': %s".formatted(baseBranch, exception.getMessage()),
                        "Fix the branch state manually and retry close",
                        false);
            }
        }

        if (remoteChecksAvailable) {
            try {
                gitRepositoryService.fastForwardCurrentBranchToOrigin(repoPath, baseBranch);
            } catch (GitRepositoryOperationException exception) {
                blockClose(
                        session,
                        "base_not_aligned",
                        "Base branch '%s' could not be aligned with origin/%s without a local merge"
                                .formatted(baseBranch, baseBranch),
                        "Align the base branch manually without creating a local merge and retry close",
                        false);
            }
        }

        if (localWorkspaceBranchExists) {
            try {
                gitRepositoryService.deleteLocalBranch(repoPath, workspaceBranch);
            } catch (GitRepositoryOperationException exception) {
                blockClose(
                        session,
                        "delete_local_branch_failed",
                        "Could not delete local session branch '%s': %s".formatted(workspaceBranch, exception.getMessage()),
                        "Delete the local session branch manually and retry close",
                        false);
            }
        }

        if (remoteWorkspaceBranchExists) {
            try {
                gitRepositoryService.deleteRemoteBranch(repoPath, workspaceBranch);
            } catch (GitRepositoryOperationException exception) {
                blockClose(
                        session,
                        "delete_remote_branch_failed",
                        "Could not delete remote session branch '%s': %s".formatted(workspaceBranch, exception.getMessage()),
                        "Delete the remote session branch manually and retry close",
                        false);
            }
        }

        try {
            if (!gitRepositoryService.getCurrentBranch(repoPath).equals(baseBranch)) {
                blockClose(
                        session,
                        "final_branch_mismatch",
                        "Repository did not end on the base branch after close reconciliation",
                        "Switch to the base branch manually and retry close",
                        false);
            }
            if (!gitRepositoryService.isWorkingTreeClean(repoPath)) {
                blockClose(
                        session,
                        "final_dirty_worktree",
                        "Repository is still dirty after close reconciliation",
                        "Clean the worktree manually and retry close",
                        false);
            }
            if (workspaceBranch != null && gitRepositoryService.branchExists(repoPath, workspaceBranch)) {
                blockClose(
                        session,
                        "local_branch_still_exists",
                        "Local session branch still exists after close reconciliation",
                        "Delete the local session branch manually and retry close",
                        false);
            }
            if (workspaceBranch != null && gitRepositoryService.remoteBranchExists(repoPath, workspaceBranch)) {
                blockClose(
                        session,
                        "remote_branch_still_exists",
                        "Remote session branch still exists after close reconciliation",
                        "Delete the remote session branch manually and retry close",
                        false);
            }
        } catch (GitRepositoryOperationException exception) {
            blockClose(
                    session,
                    "final_verification_failed",
                    "Could not verify final repository state: " + exception.getMessage(),
                    "Inspect the repository manually and retry close",
                    true);
        }
    }

    private void syncPullRequestStateForClose(WorkSessionEntity session, String repoPath) {
        String pullRequestUrl = normalizeNullableText(session.getPullRequestUrl());
        if (pullRequestUrl == null) {
            blockClose(
                    session,
                    "published_without_pull_request_url",
                    "WorkSession is marked as published but has no pullRequestUrl",
                    "Inspect the WorkSession delivery metadata manually before retrying close",
                    false);
        }

        GitHubRepositoryRef repository;
        try {
            repository = gitHubClient.resolveRepository(gitRepositoryService.getOriginRemoteUrl(repoPath));
        } catch (GitHubIntegrationException | GitRepositoryOperationException exception) {
            blockClose(
                    session,
                    "github_repository_unavailable",
                    "Could not resolve the GitHub repository during close: " + exception.getMessage(),
                    "Restore GitHub access and retry close",
                    true);
            return;
        }

        try {
            long pullRequestNumber = gitHubClient.extractPullRequestNumber(pullRequestUrl);
            GitHubPullRequest pullRequest = gitHubClient.getPullRequest(repository, pullRequestNumber);
            WorkSessionPullRequestIdentity.validate(session, repository, pullRequestNumber, pullRequest);
            session.setPullRequestUrl(pullRequest.htmlUrl());
            session.setPullRequestStatus(mapPullRequestStatus(pullRequest));
            session.setUpdatedAt(Instant.now());
        } catch (WorkSessionPublishConflictException exception) {
            blockClose(
                    session,
                    "pull_request_identity_conflict",
                    exception.getMessage(),
                    "Restore the exact WorkSession pull request identity before retrying close",
                    false);
        } catch (GitHubIntegrationException exception) {
            blockClose(
                    session,
                    "github_pull_request_unavailable",
                    "Could not verify the WorkSession pull request during close: " + exception.getMessage(),
                    "Restore GitHub access or inspect the pull request manually before retrying close",
                    true);
        }
    }

    private boolean hasPublishedPullRequest(WorkSessionEntity session) {
        return session.getPublishedAt() != null
                || normalizeNullableText(session.getPullRequestUrl()) != null
                || session.getPullRequestStatus() != WorkSessionPullRequestStatus.NOT_CREATED;
    }

    private WorkSessionPullRequestStatus mapPullRequestStatus(GitHubPullRequest pullRequest) {
        if (pullRequest.merged()) {
            return WorkSessionPullRequestStatus.MERGED;
        }
        if ("open".equalsIgnoreCase(pullRequest.state())) {
            return WorkSessionPullRequestStatus.OPEN;
        }
        return WorkSessionPullRequestStatus.DECLINED;
    }

    private void clearCloseBlock(WorkSessionEntity session) {
        session.setCloseBlockedState(null);
        session.setCloseBlockedReason(null);
        session.setCloseBlockedAction(null);
        session.setCloseRetryable(false);
    }

    private void blockClose(
            WorkSessionEntity session,
            String state,
            String reason,
            String action,
            boolean retryable
    ) {
        session.setStatus(WorkSessionStatus.CLOSING);
        session.setCloseBlockedState(state);
        session.setCloseBlockedReason(reason);
        session.setCloseBlockedAction(action);
        session.setCloseRetryable(retryable);
        session.setUpdatedAt(Instant.now());
        mobilePushDispatchService.notifyCloseBlocked(session, state, reason);
        throw new WorkSessionCloseBlockedException(
                "WorkSession '%s' cannot finish closing: %s".formatted(session.getId(), reason),
                state,
                reason,
                action,
                retryable,
                List.of(
                        "state: " + state,
                        "reason: " + reason,
                        "action: " + action,
                        "retryable: " + retryable));
    }

    private String normalizeNullableText(String value) {
        return workspaceRepositoryPathValidator.normalizeNullableText(value);
    }

    private <T> java.util.Optional<T> nullSafe(java.util.Optional<T> value) {
        return value == null ? java.util.Optional.empty() : value;
    }
}
