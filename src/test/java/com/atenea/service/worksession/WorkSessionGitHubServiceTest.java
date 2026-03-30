package com.atenea.service.worksession;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.atenea.api.worksession.PublishWorkSessionRequest;
import com.atenea.api.worksession.SessionOperationalSnapshotResponse;
import com.atenea.api.worksession.WorkSessionOperationalState;
import com.atenea.api.worksession.WorkSessionResponse;
import com.atenea.github.GitHubClient;
import com.atenea.github.GitHubPullRequest;
import com.atenea.github.GitHubRepositoryRef;
import com.atenea.mobilepush.MobilePushDispatchService;
import com.atenea.persistence.project.ProjectEntity;
import com.atenea.persistence.worksession.AgentRunRepository;
import com.atenea.persistence.worksession.AgentRunStatus;
import com.atenea.persistence.worksession.SessionTurnRepository;
import com.atenea.persistence.worksession.WorkSessionEntity;
import com.atenea.persistence.worksession.WorkSessionPullRequestStatus;
import com.atenea.persistence.worksession.WorkSessionRepository;
import com.atenea.persistence.worksession.WorkSessionStatus;
import com.atenea.service.project.WorkspaceRepositoryPathValidator;
import com.atenea.service.git.GitRepositoryService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WorkSessionGitHubServiceTest {

    @Mock
    private WorkSessionRepository workSessionRepository;

    @Mock
    private WorkSessionService workSessionService;

    @Mock
    private AgentRunReconciliationService agentRunReconciliationService;

    @Mock
    private AgentRunRepository agentRunRepository;

    @Mock
    private SessionTurnRepository sessionTurnRepository;

    @Mock
    private SessionBranchService sessionBranchService;

    @Mock
    private GitRepositoryService gitRepositoryService;

    @Mock
    private GitHubClient gitHubClient;

    @Mock
    private MobilePushDispatchService mobilePushDispatchService;

    @TempDir
    Path tempDir;

    private WorkSessionGitHubService workSessionGitHubService;

    @BeforeEach
    void setUp() throws Exception {
        Path workspaceRoot = Files.createDirectories(tempDir.resolve("repos"));
        workSessionGitHubService = new WorkSessionGitHubService(
                workSessionRepository,
                workSessionService,
                agentRunReconciliationService,
                agentRunRepository,
                sessionTurnRepository,
                new WorkspaceRepositoryPathValidator(workspaceRoot.toString()),
                sessionBranchService,
                gitRepositoryService,
                gitHubClient,
                mobilePushDispatchService
        );
    }

    @Test
    void publishSessionStagesCommitsPushesAndPersistsPullRequestMetadata() throws Exception {
        Path repoPath = createRepoPath();
        WorkSessionEntity session = buildSession(repoPath);

        when(workSessionRepository.findWithProjectById(12L)).thenReturn(Optional.of(session));
        when(agentRunRepository.existsBySessionIdAndStatus(12L, AgentRunStatus.RUNNING)).thenReturn(false);
        when(sessionBranchService.prepareWorkspaceBranch(session, repoPath.toString())).thenReturn("atenea/session-12");
        when(gitRepositoryService.isWorkingTreeClean(repoPath.toString())).thenReturn(false);
        when(gitRepositoryService.hasReviewableChanges(repoPath.toString(), "main", "atenea/session-12")).thenReturn(true);
        when(gitRepositoryService.getWorkingTreeStatusEntries(repoPath.toString()))
                .thenReturn(List.of("M  mobile/src/screens/ConversationScreen.tsx"));
        when(gitRepositoryService.getOriginRemoteUrl(repoPath.toString())).thenReturn("git@github.com:acme/atenea.git");
        when(gitRepositoryService.getHeadCommitSha(repoPath.toString())).thenReturn("abc123");
        when(gitHubClient.resolveRepository("git@github.com:acme/atenea.git"))
                .thenReturn(new GitHubRepositoryRef("acme", "atenea"));
        ArgumentCaptor<String> prBodyCaptor = ArgumentCaptor.forClass(String.class);
        when(gitHubClient.createPullRequest(
                eq(new GitHubRepositoryRef("acme", "atenea")),
                eq("Ship current work"),
                prBodyCaptor.capture(),
                eq("atenea/session-12"),
                eq("main")
        )).thenReturn(new GitHubPullRequest(42L, "https://github.com/acme/atenea/pull/42", "open", false));
        when(workSessionRepository.save(any(WorkSessionEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(workSessionService.toResponse(any(WorkSessionEntity.class))).thenAnswer(invocation -> responseFor(invocation.getArgument(0)));

        WorkSessionResponse response = workSessionGitHubService.publishSession(
                12L,
                new PublishWorkSessionRequest("Ship current work"));

        assertEquals("https://github.com/acme/atenea/pull/42", response.pullRequestUrl());
        assertEquals(WorkSessionPullRequestStatus.OPEN, response.pullRequestStatus());
        assertEquals("abc123", response.finalCommitSha());
        assertTrue(prBodyCaptor.getValue().contains("## Summary"));
        assertTrue(prBodyCaptor.getValue().contains("## What changed"));
        assertTrue(prBodyCaptor.getValue().contains("## How to review"));
        assertTrue(prBodyCaptor.getValue().contains("## Notes"));
        assertTrue(prBodyCaptor.getValue().contains("## Atenea metadata"));
    }

    @Test
    void publishSessionRejectsWhenNoReviewableChangesExist() throws Exception {
        Path repoPath = createRepoPath();
        WorkSessionEntity session = buildSession(repoPath);

        when(workSessionRepository.findWithProjectById(12L)).thenReturn(Optional.of(session));
        when(agentRunRepository.existsBySessionIdAndStatus(12L, AgentRunStatus.RUNNING)).thenReturn(false);
        when(sessionBranchService.prepareWorkspaceBranch(session, repoPath.toString())).thenReturn("atenea/session-12");
        when(gitRepositoryService.isWorkingTreeClean(repoPath.toString())).thenReturn(true);
        when(gitRepositoryService.hasReviewableChanges(repoPath.toString(), "main", "atenea/session-12")).thenReturn(false);

        WorkSessionPublishConflictException exception = assertThrows(
                WorkSessionPublishConflictException.class,
                () -> workSessionGitHubService.publishSession(12L, new PublishWorkSessionRequest(null)));

        assertEquals(
                "WorkSession '12' cannot be published: publish requires reviewable changes in the workspace branch",
                exception.getMessage());
    }

    @Test
    void publishSessionGeneratesCommitMessageWhenClientDoesNotProvideOne() throws Exception {
        Path repoPath = createRepoPath();
        WorkSessionEntity session = buildSession(repoPath);

        when(workSessionRepository.findWithProjectById(12L)).thenReturn(Optional.of(session));
        when(agentRunRepository.existsBySessionIdAndStatus(12L, AgentRunStatus.RUNNING)).thenReturn(false);
        when(sessionBranchService.prepareWorkspaceBranch(session, repoPath.toString())).thenReturn("atenea/session-12");
        when(gitRepositoryService.isWorkingTreeClean(repoPath.toString())).thenReturn(false);
        when(gitRepositoryService.hasReviewableChanges(repoPath.toString(), "main", "atenea/session-12")).thenReturn(true);
        when(gitRepositoryService.getWorkingTreeStatusEntries(repoPath.toString()))
                .thenReturn(List.of("A  index.html", "A  styles.css"));
        when(gitRepositoryService.getOriginRemoteUrl(repoPath.toString())).thenReturn("git@github.com:acme/atenea.git");
        when(gitRepositoryService.getHeadCommitSha(repoPath.toString())).thenReturn("abc123");
        when(gitHubClient.resolveRepository("git@github.com:acme/atenea.git"))
                .thenReturn(new GitHubRepositoryRef("acme", "atenea"));
        when(gitHubClient.createPullRequest(
                eq(new GitHubRepositoryRef("acme", "atenea")),
                eq("Add landing page assets"),
                anyString(),
                eq("atenea/session-12"),
                eq("main")
        )).thenReturn(new GitHubPullRequest(42L, "https://github.com/acme/atenea/pull/42", "open", false));
        when(workSessionRepository.save(any(WorkSessionEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(workSessionService.toResponse(any(WorkSessionEntity.class))).thenAnswer(invocation -> responseFor(invocation.getArgument(0)));

        workSessionGitHubService.publishSession(12L, new PublishWorkSessionRequest(null));

        verify(gitRepositoryService).commit(repoPath.toString(), "Add landing page assets");
    }

    @Test
    void syncPullRequestMarksMergedPullRequest() throws Exception {
        Path repoPath = createRepoPath();
        WorkSessionEntity session = buildSession(repoPath);
        session.setPullRequestUrl("https://github.com/acme/atenea/pull/42");
        session.setPullRequestStatus(WorkSessionPullRequestStatus.OPEN);

        when(workSessionRepository.findWithProjectById(12L)).thenReturn(Optional.of(session));
        when(gitRepositoryService.getOriginRemoteUrl(repoPath.toString())).thenReturn("git@github.com:acme/atenea.git");
        when(gitHubClient.resolveRepository("git@github.com:acme/atenea.git"))
                .thenReturn(new GitHubRepositoryRef("acme", "atenea"));
        when(gitHubClient.extractPullRequestNumber("https://github.com/acme/atenea/pull/42")).thenReturn(42L);
        when(gitHubClient.getPullRequest(new GitHubRepositoryRef("acme", "atenea"), 42L))
                .thenReturn(new GitHubPullRequest(42L, "https://github.com/acme/atenea/pull/42", "closed", true));
        when(workSessionRepository.save(any(WorkSessionEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(workSessionService.toResponse(any(WorkSessionEntity.class))).thenAnswer(invocation -> responseFor(invocation.getArgument(0)));

        WorkSessionResponse response = workSessionGitHubService.syncPullRequest(12L);

        assertEquals(WorkSessionPullRequestStatus.MERGED, response.pullRequestStatus());
    }

    private Path createRepoPath() throws Exception {
        Path repoPath = Files.createDirectories(tempDir.resolve("repos/internal/atenea"));
        Files.createDirectories(repoPath.resolve(".git"));
        return repoPath;
    }

    private static WorkSessionEntity buildSession(Path repoPath) {
        ProjectEntity project = new ProjectEntity();
        project.setId(7L);
        project.setName("Atenea");
        project.setRepoPath(repoPath.toString());
        project.setCreatedAt(Instant.now());
        project.setUpdatedAt(Instant.now());

        WorkSessionEntity session = new WorkSessionEntity();
        session.setId(12L);
        session.setProject(project);
        session.setStatus(WorkSessionStatus.OPEN);
        session.setTitle("Inspect project state");
        session.setBaseBranch("main");
        session.setWorkspaceBranch("atenea/session-12");
        session.setExternalThreadId("thread-1");
        session.setPullRequestUrl(null);
        session.setPullRequestStatus(WorkSessionPullRequestStatus.NOT_CREATED);
        session.setFinalCommitSha(null);
        session.setOpenedAt(Instant.parse("2026-03-25T10:05:00Z"));
        session.setLastActivityAt(Instant.parse("2026-03-25T10:06:00Z"));
        session.setPublishedAt(null);
        session.setCloseBlockedState(null);
        session.setCloseBlockedReason(null);
        session.setCloseBlockedAction(null);
        session.setCloseRetryable(false);
        session.setClosedAt(null);
        session.setCreatedAt(Instant.parse("2026-03-25T10:05:00Z"));
        session.setUpdatedAt(Instant.parse("2026-03-25T10:06:00Z"));
        return session;
    }

    private static WorkSessionResponse responseFor(WorkSessionEntity session) {
        return new WorkSessionResponse(
                session.getId(),
                session.getProject().getId(),
                session.getStatus(),
                WorkSessionOperationalState.IDLE,
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
                new SessionOperationalSnapshotResponse(true, true, session.getWorkspaceBranch(), false)
        );
    }
}
