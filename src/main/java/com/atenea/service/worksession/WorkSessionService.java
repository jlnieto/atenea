package com.atenea.service.worksession;

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
import com.atenea.persistence.worksession.WorkSessionEntity;
import com.atenea.persistence.worksession.WorkSessionPullRequestStatus;
import com.atenea.persistence.worksession.WorkSessionRepository;
import com.atenea.persistence.worksession.WorkSessionStatus;
import com.atenea.service.project.WorkspaceRepositoryPathValidator;
import com.atenea.service.git.GitRepositoryService;
import com.atenea.service.git.GitRepositoryOperationException;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkSessionService {

    private static final int RECENT_TURN_LIMIT = 20;

    private final ProjectRepository projectRepository;
    private final WorkSessionRepository workSessionRepository;
    private final WorkspaceRepositoryPathValidator workspaceRepositoryPathValidator;
    private final GitRepositoryService gitRepositoryService;
    private final SessionOperationalSnapshotService sessionOperationalSnapshotService;
    private final AgentRunRepository agentRunRepository;
    private final SessionTurnService sessionTurnService;
    private final AgentRunReconciliationService agentRunReconciliationService;
    private final SessionBranchService sessionBranchService;
    private final GitHubClient gitHubClient;

    public WorkSessionService(
            ProjectRepository projectRepository,
            WorkSessionRepository workSessionRepository,
            WorkspaceRepositoryPathValidator workspaceRepositoryPathValidator,
            GitRepositoryService gitRepositoryService,
            SessionOperationalSnapshotService sessionOperationalSnapshotService,
            AgentRunRepository agentRunRepository,
            SessionTurnService sessionTurnService,
            AgentRunReconciliationService agentRunReconciliationService,
            SessionBranchService sessionBranchService,
            GitHubClient gitHubClient
    ) {
        this.projectRepository = projectRepository;
        this.workSessionRepository = workSessionRepository;
        this.workspaceRepositoryPathValidator = workspaceRepositoryPathValidator;
        this.gitRepositoryService = gitRepositoryService;
        this.sessionOperationalSnapshotService = sessionOperationalSnapshotService;
        this.agentRunRepository = agentRunRepository;
        this.sessionTurnService = sessionTurnService;
        this.agentRunReconciliationService = agentRunReconciliationService;
        this.sessionBranchService = sessionBranchService;
        this.gitHubClient = gitHubClient;
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
        AgentRunEntity latestFailedRun = agentRunRepository.findFirstBySessionIdAndStatusOrderByCreatedAtDesc(
                sessionId,
                AgentRunStatus.FAILED).orElse(null);
        AgentRunEntity latestSucceededRun = agentRunRepository.findFirstBySessionIdAndStatusOrderByCreatedAtDesc(
                sessionId,
                AgentRunStatus.SUCCEEDED).orElse(null);

        return new WorkSessionViewResponse(
                sessionResponse,
                sessionResponse.repoState().runInProgress(),
                canCreateTurn(sessionResponse),
                latestRun == null ? null : toLatestRunResponse(latestRun),
                latestFailedRun == null ? null : latestFailedRun.getErrorSummary(),
                latestSucceededRun == null ? null : latestSucceededRun.getOutputSummary()
        );
    }

    @Transactional(readOnly = true)
    public WorkSessionConversationViewResponse getSessionConversationView(Long sessionId) {
        WorkSessionViewResponse view = getSessionView(sessionId);
        List<SessionTurnResponse> turns = sessionTurnService.getTurns(sessionId, null, RECENT_TURN_LIMIT);
        int totalVisibleTurns = sessionTurnService.getTurns(sessionId).size();
        return new WorkSessionConversationViewResponse(
                view,
                turns,
                RECENT_TURN_LIMIT,
                totalVisibleTurns > RECENT_TURN_LIMIT
        );
    }

    @Transactional(noRollbackFor = WorkSessionCloseBlockedException.class)
    public WorkSessionResponse closeSession(Long sessionId) {
        WorkSessionEntity session = workSessionRepository.findWithProjectById(sessionId)
                .orElseThrow(() -> new WorkSessionNotFoundException(sessionId));

        if (session.getStatus() == WorkSessionStatus.CLOSED) {
            throw new WorkSessionNotOpenException(sessionId, session.getStatus());
        }

        Instant now = Instant.now();
        session.setStatus(WorkSessionStatus.CLOSING);
        session.setClosedAt(null);
        clearCloseBlock(session);
        session.setUpdatedAt(now);

        try {
            reconcileClose(session);
        } catch (WorkSessionCloseBlockedException exception) {
            throw exception;
        }

        session.setStatus(WorkSessionStatus.CLOSED);
        session.setClosedAt(now);
        clearCloseBlock(session);
        session.setUpdatedAt(now);
        return toResponse(session);
    }

    @Transactional(noRollbackFor = WorkSessionCloseBlockedException.class)
    public CloseWorkSessionConversationViewResponse closeSessionConversationView(Long sessionId) {
        closeSession(sessionId);
        return new CloseWorkSessionConversationViewResponse(getSessionConversationView(sessionId));
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
                snapshot
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
                run.getErrorSummary()
        );
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
        if (snapshot.runInProgress()) {
            return WorkSessionOperationalState.RUNNING;
        }
        return WorkSessionOperationalState.IDLE;
    }

    private boolean canCreateTurn(WorkSessionResponse session) {
        return session.operationalState() == WorkSessionOperationalState.IDLE;
    }

    private void reconcileClose(WorkSessionEntity session) {
        Long sessionId = session.getId();
        agentRunReconciliationService.reconcileSession(sessionId);
        if (agentRunRepository.existsBySessionIdAndStatus(sessionId, AgentRunStatus.RUNNING)) {
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

        boolean remoteWorkspaceBranchExists = workspaceBranch != null && gitRepositoryService.remoteBranchExists(repoPath, workspaceBranch);

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
            session.setPullRequestUrl(pullRequest.htmlUrl());
            session.setPullRequestStatus(mapPullRequestStatus(pullRequest));
            session.setUpdatedAt(Instant.now());
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
