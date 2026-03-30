package com.atenea.service.worksession;

import com.atenea.api.worksession.PublishWorkSessionConversationViewResponse;
import com.atenea.api.worksession.PublishWorkSessionRequest;
import com.atenea.api.worksession.SyncWorkSessionPullRequestConversationViewResponse;
import com.atenea.api.worksession.WorkSessionResponse;
import com.atenea.github.GitHubClient;
import com.atenea.github.GitHubIntegrationException;
import com.atenea.github.GitHubPullRequest;
import com.atenea.github.GitHubRepositoryRef;
import com.atenea.persistence.worksession.AgentRunStatus;
import com.atenea.persistence.worksession.SessionTurnActor;
import com.atenea.persistence.worksession.SessionTurnRepository;
import com.atenea.persistence.worksession.WorkSessionEntity;
import com.atenea.persistence.worksession.WorkSessionPullRequestStatus;
import com.atenea.persistence.worksession.WorkSessionRepository;
import com.atenea.persistence.worksession.WorkSessionStatus;
import com.atenea.mobilepush.MobilePushDispatchService;
import com.atenea.service.project.WorkspaceRepositoryPathValidator;
import com.atenea.service.git.GitRepositoryService;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkSessionGitHubService {

    private final WorkSessionRepository workSessionRepository;
    private final WorkSessionService workSessionService;
    private final AgentRunReconciliationService agentRunReconciliationService;
    private final com.atenea.persistence.worksession.AgentRunRepository agentRunRepository;
    private final SessionTurnRepository sessionTurnRepository;
    private final WorkspaceRepositoryPathValidator workspaceRepositoryPathValidator;
    private final SessionBranchService sessionBranchService;
    private final GitRepositoryService gitRepositoryService;
    private final GitHubClient gitHubClient;
    private final MobilePushDispatchService mobilePushDispatchService;

    public WorkSessionGitHubService(
            WorkSessionRepository workSessionRepository,
            WorkSessionService workSessionService,
            AgentRunReconciliationService agentRunReconciliationService,
            com.atenea.persistence.worksession.AgentRunRepository agentRunRepository,
            SessionTurnRepository sessionTurnRepository,
            WorkspaceRepositoryPathValidator workspaceRepositoryPathValidator,
            SessionBranchService sessionBranchService,
            GitRepositoryService gitRepositoryService,
            GitHubClient gitHubClient,
            MobilePushDispatchService mobilePushDispatchService
    ) {
        this.workSessionRepository = workSessionRepository;
        this.workSessionService = workSessionService;
        this.agentRunReconciliationService = agentRunReconciliationService;
        this.agentRunRepository = agentRunRepository;
        this.sessionTurnRepository = sessionTurnRepository;
        this.workspaceRepositoryPathValidator = workspaceRepositoryPathValidator;
        this.sessionBranchService = sessionBranchService;
        this.gitRepositoryService = gitRepositoryService;
        this.gitHubClient = gitHubClient;
        this.mobilePushDispatchService = mobilePushDispatchService;
    }

    @Transactional
    public WorkSessionResponse publishSession(Long sessionId, PublishWorkSessionRequest request) {
        WorkSessionEntity session = findSession(sessionId);
        ensurePublishable(session);

        String repoPath = workspaceRepositoryPathValidator.normalizeConfiguredRepoPath(session.getProject().getRepoPath());
        String workspaceBranch = sessionBranchService.prepareWorkspaceBranch(session, repoPath);
        session.setWorkspaceBranch(workspaceBranch);
        List<String> statusEntries = gitRepositoryService.getWorkingTreeStatusEntries(repoPath);
        String commitMessage = normalizeNullableText(request == null ? null : request.commitMessage());
        if (commitMessage == null) {
            commitMessage = generateAutomaticCommitMessage(session, statusEntries);
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
        String pullRequestTitle = generatePullRequestTitle(session, commitMessage);
        GitHubPullRequest pullRequest = gitHubClient.createPullRequest(
                repository,
                pullRequestTitle,
                buildPullRequestBody(session, statusEntries, commitMessage),
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
    public PublishWorkSessionConversationViewResponse publishSessionConversationView(
            Long sessionId,
            PublishWorkSessionRequest request
    ) {
        publishSession(sessionId, request);
        return new PublishWorkSessionConversationViewResponse(workSessionService.getSessionConversationView(sessionId));
    }

    @Transactional
    public WorkSessionResponse syncPullRequest(Long sessionId) {
        WorkSessionEntity session = findSession(sessionId);
        WorkSessionPullRequestStatus previousStatus = session.getPullRequestStatus();
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
        WorkSessionEntity savedSession = workSessionRepository.save(session);
        if (previousStatus != WorkSessionPullRequestStatus.MERGED
                && savedSession.getPullRequestStatus() == WorkSessionPullRequestStatus.MERGED) {
            mobilePushDispatchService.notifyPullRequestMerged(savedSession);
        }
        return workSessionService.toResponse(savedSession);
    }

    @Transactional
    public SyncWorkSessionPullRequestConversationViewResponse syncPullRequestConversationView(Long sessionId) {
        syncPullRequest(sessionId);
        return new SyncWorkSessionPullRequestConversationViewResponse(
                workSessionService.getSessionConversationView(sessionId));
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

    private String buildPullRequestBody(WorkSessionEntity session, List<String> statusEntries, String commitMessage) {
        String scope = inferScope(statusEntries);
        String summaryLine = scope != null
                ? "- %s `%s`.".formatted(inferVerb(statusEntries), scope)
                : "- %s `%s`.".formatted(inferVerb(statusEntries), normalizeTitleScope(session.getTitle()));
        String changedFiles = statusEntries.isEmpty()
                ? "- No pending file list was available at publish time."
                : statusEntries.stream()
                .map(this::extractPath)
                .filter(path -> path != null && !path.isBlank())
                .distinct()
                .limit(8)
                .map(path -> "- `%s`".formatted(path))
                .reduce((left, right) -> left + "\n" + right)
                .orElse("- No file paths available.");
        String reviewHint = buildReviewHint(statusEntries);
        String notes = buildNotes(session);

        return """
                ## Summary

                %s
                - Generated commit message: `%s`

                ## What changed

                %s

                ## How to review

                - Review the diff for `%s`.
                - %s

                ## Notes

                %s

                ## Atenea metadata

                - WorkSession: %s
                - WorkSession title: %s
                - Base branch: %s
                - Workspace branch: %s
                """.formatted(
                summaryLine,
                commitMessage,
                changedFiles,
                session.getWorkspaceBranch(),
                reviewHint,
                notes,
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

    private String generateAutomaticCommitMessage(WorkSessionEntity session, List<String> statusEntries) {
        if (statusEntries.isEmpty()) {
            return session.getTitle();
        }

        String verb = inferVerb(statusEntries);
        String scope = inferScope(statusEntries);
        if (scope != null) {
            return verb + " " + scope;
        }

        String codexSummary = inferScopeFromLatestCodexTurn(session.getId());
        if (codexSummary != null) {
            return verb + " " + codexSummary;
        }

        return verb + " " + normalizeTitleScope(session.getTitle());
    }

    private String generatePullRequestTitle(WorkSessionEntity session, String commitMessage) {
        String normalized = normalizeNullableText(commitMessage);
        if (normalized != null) {
            return normalized;
        }
        return session.getTitle();
    }

    private String inferVerb(List<String> statusEntries) {
        boolean hasAdded = false;
        boolean hasModified = false;
        boolean hasDeleted = false;

        for (String entry : statusEntries) {
            String status = entry.length() >= 2 ? entry.substring(0, 2) : entry;
            if (status.contains("A") || status.contains("?")) {
                hasAdded = true;
            }
            if (status.contains("M") || status.contains("R") || status.contains("C")) {
                hasModified = true;
            }
            if (status.contains("D")) {
                hasDeleted = true;
            }
        }

        if (hasAdded && !hasModified && !hasDeleted) {
            return "Add";
        }
        if (hasDeleted && !hasAdded && !hasModified) {
            return "Remove";
        }
        return "Update";
    }

    private String inferScope(List<String> statusEntries) {
        List<String> paths = statusEntries.stream()
                .map(this::extractPath)
                .filter(path -> path != null && !path.isBlank())
                .toList();
        if (paths.isEmpty()) {
            return null;
        }

        if (paths.contains("index.html") && paths.contains("styles.css")) {
            return "landing page assets";
        }
        if (paths.size() == 1 && "README.md".equals(paths.get(0))) {
            return "README";
        }
        if (paths.stream().allMatch(path -> path.startsWith("mobile/"))) {
            if (paths.stream().anyMatch(path -> path.contains("ConversationScreen"))) {
                return "mobile conversation workspace";
            }
            return "mobile app";
        }
        if (paths.stream().allMatch(path -> path.startsWith("docs/") || "README.md".equals(path))) {
            return "project documentation";
        }
        if (paths.stream().allMatch(path -> path.startsWith("src/main/java/") || path.startsWith("src/test/java/"))) {
            return "backend work session flow";
        }

        String firstPath = paths.get(0);
        int slash = firstPath.indexOf('/');
        if (slash > 0) {
            return firstPath.substring(0, slash);
        }

        int dot = firstPath.lastIndexOf('.');
        return dot > 0 ? firstPath.substring(0, dot) : firstPath;
    }

    private String extractPath(String statusEntry) {
        if (statusEntry.length() <= 3) {
            return null;
        }
        String path = statusEntry.substring(3).trim();
        int renameMarker = path.indexOf(" -> ");
        if (renameMarker >= 0) {
            return path.substring(renameMarker + 4).trim();
        }
        return path;
    }

    private String inferScopeFromLatestCodexTurn(Long sessionId) {
        List<com.atenea.persistence.worksession.SessionTurnEntity> turns =
                sessionTurnRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
        for (int index = turns.size() - 1; index >= 0; index--) {
            var turn = turns.get(index);
            if (turn.isInternal() || turn.getActor() != SessionTurnActor.CODEX) {
                continue;
            }
            String firstSentence = normalizeNullableText(extractFirstSentence(turn.getMessageText()));
            if (firstSentence == null) {
                continue;
            }
            return firstSentence;
        }
        return null;
    }

    private String extractFirstSentence(String messageText) {
        String normalized = messageText
                .replace("\r\n", "\n")
                .replaceAll("(?m)^#{1,3}\\s+", "")
                .replace("`", "")
                .replaceAll("\\s+", " ")
                .trim();
        int dotIndex = normalized.indexOf('.');
        String candidate = dotIndex >= 0 ? normalized.substring(0, dotIndex) : normalized;
        return candidate.length() > 60 ? candidate.substring(0, 60).trim() : candidate;
    }

    private String normalizeTitleScope(String title) {
        String normalized = title == null ? "session changes" : title.trim();
        if (normalized.isBlank()) {
            return "session changes";
        }
        return Character.toLowerCase(normalized.charAt(0)) + normalized.substring(1);
    }

    private String buildReviewHint(List<String> statusEntries) {
        String scope = inferScope(statusEntries);
        if (scope == null) {
            return "Compare the changed files and verify the resulting behavior.";
        }
        return switch (scope) {
            case "landing page assets" ->
                    "Open the landing locally and verify copy, layout, and responsive sections.";
            case "mobile conversation workspace" ->
                    "Exercise the mobile conversation flow and validate layout, scrolling, and composer behavior.";
            case "mobile app" ->
                    "Exercise the affected mobile screens and validate the updated operator flow.";
            case "project documentation" ->
                    "Read the updated documentation for accuracy against the implemented code.";
            case "backend work session flow" ->
                    "Review the backend diff together with the relevant tests and validate the happy path.";
            default ->
                    "Review the diff for consistency and validate the changed user flow.";
        };
    }

    private String buildNotes(WorkSessionEntity session) {
        String codexSummary = inferScopeFromLatestCodexTurn(session.getId());
        if (codexSummary != null) {
            return "- Latest Codex summary: %s".formatted(codexSummary);
        }
        return "- Generated automatically from the WorkSession context and git diff.";
    }
}
