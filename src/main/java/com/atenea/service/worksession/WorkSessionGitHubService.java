package com.atenea.service.worksession;

import com.atenea.api.worksession.PublishWorkSessionRequest;
import com.atenea.api.worksession.WorkSessionResponse;
import com.atenea.github.GitHubClient;
import com.atenea.github.GitHubIntegrationException;
import com.atenea.github.GitHubPullRequest;
import com.atenea.github.GitHubRepositoryRef;
import com.atenea.persistence.worksession.AgentRunStatus;
import com.atenea.persistence.worksession.WorkSessionEntity;
import com.atenea.persistence.worksession.WorkSessionPullRequestStatus;
import com.atenea.persistence.worksession.WorkSessionRepository;
import com.atenea.persistence.worksession.WorkSessionStatus;
import com.atenea.service.project.WorkspaceRepositoryPathValidator;
import com.atenea.service.taskexecution.GitRepositoryService;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkSessionGitHubService {

    private final WorkSessionRepository workSessionRepository;
    private final WorkSessionService workSessionService;
    private final AgentRunReconciliationService agentRunReconciliationService;
    private final com.atenea.persistence.worksession.AgentRunRepository agentRunRepository;
    private final WorkspaceRepositoryPathValidator workspaceRepositoryPathValidator;
    private final SessionBranchService sessionBranchService;
    private final GitRepositoryService gitRepositoryService;
    private final GitHubClient gitHubClient;

    public WorkSessionGitHubService(
            WorkSessionRepository workSessionRepository,
            WorkSessionService workSessionService,
            AgentRunReconciliationService agentRunReconciliationService,
            com.atenea.persistence.worksession.AgentRunRepository agentRunRepository,
            WorkspaceRepositoryPathValidator workspaceRepositoryPathValidator,
            SessionBranchService sessionBranchService,
            GitRepositoryService gitRepositoryService,
            GitHubClient gitHubClient
    ) {
        this.workSessionRepository = workSessionRepository;
        this.workSessionService = workSessionService;
        this.agentRunReconciliationService = agentRunReconciliationService;
        this.agentRunRepository = agentRunRepository;
        this.workspaceRepositoryPathValidator = workspaceRepositoryPathValidator;
        this.sessionBranchService = sessionBranchService;
        this.gitRepositoryService = gitRepositoryService;
        this.gitHubClient = gitHubClient;
    }

    @Transactional
    public WorkSessionResponse publishSession(Long sessionId, PublishWorkSessionRequest request) {
        WorkSessionEntity session = findSession(sessionId);
        ensurePublishable(session);

        String repoPath = workspaceRepositoryPathValidator.normalizeConfiguredRepoPath(session.getProject().getRepoPath());
        String workspaceBranch = sessionBranchService.prepareWorkspaceBranch(session, repoPath);
        session.setWorkspaceBranch(workspaceBranch);
        String commitMessage = normalizeNullableText(request == null ? null : request.commitMessage());
        if (commitMessage == null) {
            commitMessage = session.getTitle();
        }

        boolean workingTreeClean = gitRepositoryService.isWorkingTreeClean(repoPath);
        boolean reviewableChanges = gitRepositoryService.hasReviewableChanges(
                repoPath,
                session.getBaseBranch(),
                workspaceBranch);
        if (!reviewableChanges && workingTreeClean) {
            throw new WorkSessionPublishConflictException(sessionId,
                    "publish requires reviewable changes in the workspace branch");
        }

        if (!workingTreeClean) {
            gitRepositoryService.stageAll(repoPath);
            gitRepositoryService.commit(repoPath, commitMessage);
        }

        gitRepositoryService.pushBranchSetUpstream(repoPath, workspaceBranch);

        GitHubRepositoryRef repository = resolveRepository(session, repoPath);
        GitHubPullRequest pullRequest = gitHubClient.createPullRequest(
                repository,
                session.getTitle(),
                buildPullRequestBody(session),
                workspaceBranch,
                session.getBaseBranch()
        );

        Instant now = Instant.now();
        session.setWorkspaceBranch(workspaceBranch);
        session.setPullRequestUrl(pullRequest.htmlUrl());
        session.setPullRequestStatus(mapPullRequestStatus(pullRequest));
        session.setFinalCommitSha(gitRepositoryService.getHeadCommitSha(repoPath));
        session.setPublishedAt(now);
        session.setUpdatedAt(now);
        return workSessionService.toResponse(workSessionRepository.save(session));
    }

    @Transactional
    public WorkSessionResponse syncPullRequest(Long sessionId) {
        WorkSessionEntity session = findSession(sessionId);
        if (normalizeNullableText(session.getPullRequestUrl()) == null) {
            throw new WorkSessionPublishConflictException(sessionId,
                    "pull request synchronization requires a pullRequestUrl");
        }

        String repoPath = workspaceRepositoryPathValidator.normalizeConfiguredRepoPath(session.getProject().getRepoPath());
        GitHubRepositoryRef repository = resolveRepository(session, repoPath);
        long pullRequestNumber = gitHubClient.extractPullRequestNumber(session.getPullRequestUrl());
        GitHubPullRequest pullRequest = gitHubClient.getPullRequest(repository, pullRequestNumber);

        Instant now = Instant.now();
        session.setPullRequestUrl(pullRequest.htmlUrl());
        session.setPullRequestStatus(mapPullRequestStatus(pullRequest));
        session.setUpdatedAt(now);
        return workSessionService.toResponse(workSessionRepository.save(session));
    }

    private WorkSessionEntity findSession(Long sessionId) {
        return workSessionRepository.findWithProjectById(sessionId)
                .orElseThrow(() -> new WorkSessionNotFoundException(sessionId));
    }

    private void ensurePublishable(WorkSessionEntity session) {
        if (session.getStatus() != WorkSessionStatus.OPEN) {
            throw new WorkSessionNotOpenException(session.getId(), session.getStatus());
        }

        agentRunReconciliationService.reconcileSession(session.getId());
        if (agentRunRepository.existsBySessionIdAndStatus(session.getId(), AgentRunStatus.RUNNING)) {
            throw new AgentRunAlreadyRunningException(session.getId());
        }

        if (session.getPullRequestStatus() != null && session.getPullRequestStatus() != WorkSessionPullRequestStatus.NOT_CREATED) {
            throw new WorkSessionPublishConflictException(session.getId(),
                    "pull request creation requires status NOT_CREATED");
        }
    }

    private GitHubRepositoryRef resolveRepository(WorkSessionEntity session, String repoPath) {
        try {
            String remoteUrl = gitRepositoryService.getOriginRemoteUrl(repoPath);
            return gitHubClient.resolveRepository(remoteUrl);
        } catch (GitHubIntegrationException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new GitHubIntegrationException("Failed to resolve GitHub repository for WorkSession '"
                    + session.getId() + "': " + exception.getMessage(), exception);
        }
    }

    private static WorkSessionPullRequestStatus mapPullRequestStatus(GitHubPullRequest pullRequest) {
        if (pullRequest.merged()) {
            return WorkSessionPullRequestStatus.MERGED;
        }
        if ("open".equalsIgnoreCase(pullRequest.state())) {
            return WorkSessionPullRequestStatus.OPEN;
        }
        return WorkSessionPullRequestStatus.DECLINED;
    }

    private static String buildPullRequestBody(WorkSessionEntity session) {
        return """
                Created by Atenea

                WorkSession id: %s
                WorkSession title: %s
                Base branch: %s
                Workspace branch: %s
                """.formatted(
                session.getId(),
                session.getTitle(),
                session.getBaseBranch(),
                session.getWorkspaceBranch());
    }

    private static String normalizeNullableText(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
