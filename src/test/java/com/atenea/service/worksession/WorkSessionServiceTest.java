package com.atenea.service.worksession;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.atenea.attachments.NewWorkSessionAttachmentPolicySnapshotter;
import com.atenea.api.worksession.CreateWorkSessionRequest;
import com.atenea.api.worksession.ResolveWorkSessionConversationViewResponse;
import com.atenea.api.worksession.ResolveWorkSessionRequest;
import com.atenea.api.worksession.ResolveWorkSessionResponse;
import com.atenea.api.worksession.ResolveWorkSessionViewResponse;
import com.atenea.api.worksession.SessionTurnResponse;
import com.atenea.api.worksession.SessionOperationalSnapshotResponse;
import com.atenea.api.worksession.WorkSessionConversationViewResponse;
import com.atenea.api.worksession.WorkSessionOperationalState;
import com.atenea.api.worksession.WorkSessionResponse;
import com.atenea.api.worksession.WorkSessionViewResponse;
import com.atenea.github.GitHubClient;
import com.atenea.github.GitHubPullRequest;
import com.atenea.github.GitHubRepositoryRef;
import com.atenea.mobilepush.MobilePushDispatchService;
import com.atenea.persistence.worksession.AgentRunEntity;
import com.atenea.persistence.worksession.AgentRunRepository;
import com.atenea.persistence.worksession.AgentRunRecoveryNextAction;
import com.atenea.persistence.worksession.AgentRunStatus;
import com.atenea.persistence.project.ProjectEntity;
import com.atenea.persistence.project.ProjectRepository;
import com.atenea.persistence.worksession.WorkSessionEntity;
import com.atenea.persistence.worksession.WorkSessionRepository;
import com.atenea.persistence.worksession.WorkSessionPullRequestStatus;
import com.atenea.persistence.worksession.RemoteCloseState;
import com.atenea.persistence.worksession.ExecutionTarget;
import com.atenea.persistence.worksession.WorkSessionStatus;
import com.atenea.remoteworker.ProjectCodexIdentity;
import com.atenea.remoteworker.RemoteWorkerClient;
import com.atenea.remoteworker.RemoteWorkerException;
import com.atenea.remoteworker.RemoteWorkerFailureCategory;
import com.atenea.remoteworker.RemoteWorkerProperties;
import com.atenea.remoteworker.RemoteRoutingSelector;
import com.atenea.service.project.WorkspaceRepositoryPathValidator;
import com.atenea.service.git.GitRepositoryService;
import com.atenea.service.git.GitRepositoryOperationException;
import com.atenea.codexappserver.CodexAppServerProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

@ExtendWith(MockitoExtension.class)
class WorkSessionServiceTest {

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private WorkSessionRepository workSessionRepository;

    @Mock
    private GitRepositoryService gitRepositoryService;

    @Mock
    private AgentRunRepository agentRunRepository;

    @Mock
    private SessionTurnService sessionTurnService;

    @Mock
    private CodexAppServerProperties codexAppServerProperties;

    @Mock
    private GitHubClient gitHubClient;

    @Mock
    private MobilePushDispatchService mobilePushDispatchService;

    @Mock
    private NewWorkSessionAttachmentPolicySnapshotter attachmentPolicySnapshotter;

    @Mock
    private RemoteRoutingSelector remoteRoutingSelector;

    @Mock
    private RemoteWorkerClient remoteWorkerClient;

    @Mock
    private RemoteWorkerProperties remoteWorkerProperties;

    @Mock
    private PlatformTransactionManager transactionManager;

    @TempDir
    Path tempDir;

    private WorkSessionService workSessionService;
    private WorkspaceRepositoryPathValidator validator;

    @BeforeEach
    void setUp() throws IOException {
        Path workspaceRoot = Files.createDirectories(tempDir.resolve("repos"));
        validator = spy(new WorkspaceRepositoryPathValidator(workspaceRoot.toString()));
        TransactionStatus transactionStatus = org.mockito.Mockito.mock(TransactionStatus.class);
        lenient().when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(transactionStatus);
        lenient().when(codexAppServerProperties.getStaleTimeout()).thenReturn(Duration.ofMinutes(5));
        AgentRunReconciliationService reconciliationService = new AgentRunReconciliationService(
                agentRunRepository,
                codexAppServerProperties,
                mobilePushDispatchService
        );
        SessionOperationalSnapshotService snapshotService = new SessionOperationalSnapshotService(
                validator,
                gitRepositoryService,
                agentRunRepository,
                reconciliationService
        );
        workSessionService = new WorkSessionService(
                projectRepository,
                workSessionRepository,
                mobilePushDispatchService,
                validator,
                gitRepositoryService,
                snapshotService,
                agentRunRepository,
                sessionTurnService,
                reconciliationService,
                new SessionBranchService(gitRepositoryService),
                gitHubClient,
                attachmentPolicySnapshotter,
                remoteWorkerClient,
                remoteWorkerProperties,
                transactionManager
        );
    }

    @Test
    void openSessionUsesRequestBaseBranchWhenProvided() throws IOException {
        Path repoPath = createGitRepo(tempDir.resolve("repos/internal/atenea"));
        ProjectEntity project = buildProject(7L, repoPath);

        when(projectRepository.findById(7L)).thenReturn(Optional.of(project));
        when(workSessionRepository.existsByProjectIdAndStatus(7L, WorkSessionStatus.OPEN)).thenReturn(false);
        when(gitRepositoryService.getCurrentBranch(repoPath.toString()))
                .thenReturn("release/2026-q1", "release/2026-q1", "atenea/session-12");
        when(gitRepositoryService.isWorkingTreeClean(repoPath.toString())).thenReturn(true);
        when(gitRepositoryService.branchExists(repoPath.toString(), "atenea/session-12")).thenReturn(false);
        when(agentRunRepository.existsBySessionIdAndStatus(12L, AgentRunStatus.RUNNING)).thenReturn(false);
        when(workSessionRepository.save(any(WorkSessionEntity.class))).thenAnswer(invocation -> {
            WorkSessionEntity entity = invocation.getArgument(0);
            if (entity.getId() == null) {
                entity.setId(12L);
            }
            return entity;
        });

        WorkSessionResponse response = workSessionService.openSession(
                7L,
                new CreateWorkSessionRequest("  Review current status  ", " release/2026-q1 "));

        assertEquals(12L, response.id());
        assertEquals(7L, response.projectId());
        assertEquals(WorkSessionStatus.OPEN, response.status());
        assertEquals(WorkSessionOperationalState.IDLE, response.operationalState());
        assertEquals("Review current status", response.title());
        assertEquals("release/2026-q1", response.baseBranch());
        assertEquals("atenea/session-12", response.workspaceBranch());
        assertNull(response.externalThreadId());
        assertNull(response.closedAt());
        assertEquals(response.openedAt(), response.lastActivityAt());
        assertEquals(new SessionOperationalSnapshotResponse(true, true, "atenea/session-12", false), response.repoState());
        assertEquals(RemoteCloseState.NOT_REQUIRED, response.remoteCloseState());
        assertNull(response.remoteCloseErrorCode());
        assertEquals(AgentRunRecoveryNextAction.NONE, response.remoteCloseNextAction());

        ArgumentCaptor<WorkSessionEntity> captor = ArgumentCaptor.forClass(WorkSessionEntity.class);
        verify(workSessionRepository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        assertEquals("release/2026-q1", captor.getValue().getBaseBranch());
        assertEquals("atenea/session-12", captor.getValue().getWorkspaceBranch());
        assertEquals(RemoteCloseState.NOT_REQUIRED, captor.getValue().getRemoteCloseState());
        verify(gitRepositoryService).createAndCheckoutBranch(repoPath.toString(), "release/2026-q1", "atenea/session-12");
    }

    @Test
    void openSessionDefaultsBaseBranchToCurrentRepoBranch() throws IOException {
        Path repoPath = createGitRepo(tempDir.resolve("repos/internal/atenea"));
        ProjectEntity project = buildProject(7L, repoPath);
        project.setDefaultBaseBranch(null);

        when(projectRepository.findById(7L)).thenReturn(Optional.of(project));
        when(workSessionRepository.existsByProjectIdAndStatus(7L, WorkSessionStatus.OPEN)).thenReturn(false);
        when(gitRepositoryService.getCurrentBranch(repoPath.toString())).thenReturn("feature/docs");
        when(gitRepositoryService.isWorkingTreeClean(repoPath.toString())).thenReturn(true);
        when(gitRepositoryService.branchExists(repoPath.toString(), "atenea/session-12")).thenReturn(false);
        when(agentRunRepository.existsBySessionIdAndStatus(12L, AgentRunStatus.RUNNING)).thenReturn(false);
        when(workSessionRepository.save(any(WorkSessionEntity.class))).thenAnswer(invocation -> {
            WorkSessionEntity entity = invocation.getArgument(0);
            if (entity.getId() == null) {
                entity.setId(12L);
            }
            return entity;
        });

        WorkSessionResponse response = workSessionService.openSession(
                7L,
                new CreateWorkSessionRequest("Inspect project state", "   "));

        assertEquals("feature/docs", response.baseBranch());
        assertEquals("atenea/session-12", response.workspaceBranch());
    }

    @Test
    void openSessionDefaultsBaseBranchToProjectDefaultBaseBranch() throws IOException {
        Path repoPath = createGitRepo(tempDir.resolve("repos/internal/atenea"));
        ProjectEntity project = buildProject(7L, repoPath);
        project.setDefaultBaseBranch("release/2026-q3");

        when(projectRepository.findById(7L)).thenReturn(Optional.of(project));
        when(workSessionRepository.existsByProjectIdAndStatus(7L, WorkSessionStatus.OPEN)).thenReturn(false);
        when(gitRepositoryService.getCurrentBranch(repoPath.toString())).thenReturn("release/2026-q3");
        when(gitRepositoryService.isWorkingTreeClean(repoPath.toString())).thenReturn(true);
        when(gitRepositoryService.branchExists(repoPath.toString(), "atenea/session-12")).thenReturn(false);
        when(agentRunRepository.existsBySessionIdAndStatus(12L, AgentRunStatus.RUNNING)).thenReturn(false);
        when(workSessionRepository.save(any(WorkSessionEntity.class))).thenAnswer(invocation -> {
            WorkSessionEntity entity = invocation.getArgument(0);
            if (entity.getId() == null) {
                entity.setId(12L);
            }
            return entity;
        });

        WorkSessionResponse response = workSessionService.openSession(
                7L,
                new CreateWorkSessionRequest("Inspect project state", " "));

        assertEquals("release/2026-q3", response.baseBranch());
    }

    @Test
    void openSessionThrowsWhenProjectDoesNotExist() {
        when(projectRepository.findById(7L)).thenReturn(Optional.empty());

        assertThrows(WorkSessionProjectNotFoundException.class, () -> workSessionService.openSession(
                7L,
                new CreateWorkSessionRequest("Inspect project state", null)));
    }

    @Test
    void openSessionThrowsWhenOpenSessionAlreadyExists() throws IOException {
        Path repoPath = createGitRepo(tempDir.resolve("repos/internal/atenea"));
        ProjectEntity project = buildProject(7L, repoPath);

        when(projectRepository.findById(7L)).thenReturn(Optional.of(project));
        when(workSessionRepository.existsByProjectIdAndStatus(7L, WorkSessionStatus.OPEN)).thenReturn(true);

        assertThrows(OpenWorkSessionAlreadyExistsException.class, () -> workSessionService.openSession(
                7L,
                new CreateWorkSessionRequest("Inspect project state", null)));
    }

    @Test
    void openSessionThrowsWhenRepoIsNotOperational() throws IOException {
        Path repoPath = createGitRepo(tempDir.resolve("repos/internal/atenea"));
        ProjectEntity project = buildProject(7L, repoPath);

        when(projectRepository.findById(7L)).thenReturn(Optional.of(project));
        when(workSessionRepository.existsByProjectIdAndStatus(7L, WorkSessionStatus.OPEN)).thenReturn(false);
        when(gitRepositoryService.getCurrentBranch(repoPath.toString()))
                .thenThrow(new GitRepositoryOperationException("Git command failed: rev-parse"));

        WorkSessionOperationBlockedException exception = assertThrows(
                WorkSessionOperationBlockedException.class,
                () -> workSessionService.openSession(7L, new CreateWorkSessionRequest("Inspect project state", null)));

        assertEquals(
                "Project repository is not operational for WorkSession opening: Git command failed: rev-parse",
                exception.getMessage());
    }

    @Test
    void resolveSessionReturnsExistingOpenSessionWithoutCreatingNewOne() throws IOException {
        Path repoPath = createGitRepo(tempDir.resolve("repos/internal/atenea"));
        ProjectEntity project = buildProject(7L, repoPath);
        WorkSessionEntity session = buildSession(12L, 7L, repoPath, "main");

        when(projectRepository.findById(7L)).thenReturn(Optional.of(project));
        when(workSessionRepository.findByProjectIdAndStatus(7L, WorkSessionStatus.OPEN)).thenReturn(Optional.of(session));
        when(gitRepositoryService.getCurrentBranch(repoPath.toString())).thenReturn("main");
        when(gitRepositoryService.isWorkingTreeClean(repoPath.toString())).thenReturn(true);
        when(gitRepositoryService.branchExists(repoPath.toString(), "atenea/session-12")).thenReturn(false);
        when(agentRunRepository.existsBySessionIdAndStatus(12L, AgentRunStatus.RUNNING)).thenReturn(false);

        ResolveWorkSessionResponse response = workSessionService.resolveSession(
                7L,
                new ResolveWorkSessionRequest("Ignored title", "develop"));

        assertFalse(response.created());
        assertEquals(12L, response.session().id());
        assertEquals("main", response.session().baseBranch());
        assertEquals("atenea/session-12", response.session().workspaceBranch());
        assertEquals(WorkSessionOperationalState.IDLE, response.session().operationalState());
        verify(attachmentPolicySnapshotter, never()).snapshotNewSession(any());
        verify(gitRepositoryService).createAndCheckoutBranch(repoPath.toString(), "main", "atenea/session-12");
    }

    @Test
    void resolveSessionAllowsExistingWorkspaceBranchEvenWhenDirty() throws IOException {
        Path repoPath = createGitRepo(tempDir.resolve("repos/internal/atenea"));
        ProjectEntity project = buildProject(7L, repoPath);
        WorkSessionEntity session = buildSession(12L, 7L, repoPath, "main");

        when(projectRepository.findById(7L)).thenReturn(Optional.of(project));
        when(workSessionRepository.findByProjectIdAndStatus(7L, WorkSessionStatus.OPEN)).thenReturn(Optional.of(session));
        when(gitRepositoryService.getCurrentBranch(repoPath.toString())).thenReturn("atenea/session-12");
        when(agentRunRepository.existsBySessionIdAndStatus(12L, AgentRunStatus.RUNNING)).thenReturn(false);

        ResolveWorkSessionResponse response = workSessionService.resolveSession(
                7L,
                new ResolveWorkSessionRequest("Ignored title", "develop"));

        assertFalse(response.created());
        assertEquals(12L, response.session().id());
        assertEquals("atenea/session-12", response.session().workspaceBranch());
    }

    @Test
    void openSessionThrowsWhenRepositoryIsDirtyBeforePreparingWorkspaceBranch() throws IOException {
        Path repoPath = createGitRepo(tempDir.resolve("repos/internal/atenea"));
        ProjectEntity project = buildProject(7L, repoPath);

        when(projectRepository.findById(7L)).thenReturn(Optional.of(project));
        when(workSessionRepository.existsByProjectIdAndStatus(7L, WorkSessionStatus.OPEN)).thenReturn(false);
        when(gitRepositoryService.getCurrentBranch(repoPath.toString())).thenReturn("main");
        when(gitRepositoryService.isWorkingTreeClean(repoPath.toString())).thenReturn(false);
        when(workSessionRepository.save(any(WorkSessionEntity.class))).thenAnswer(invocation -> {
            WorkSessionEntity entity = invocation.getArgument(0);
            if (entity.getId() == null) {
                entity.setId(12L);
            }
            return entity;
        });

        WorkSessionOperationBlockedException exception = assertThrows(
                WorkSessionOperationBlockedException.class,
                () -> workSessionService.openSession(7L, new CreateWorkSessionRequest("Inspect project state", null)));

        assertEquals(
                "Repository '%s' is not clean; cannot prepare WorkSession '12'".formatted(repoPath),
                exception.getMessage());
    }

    @Test
    void openSessionThrowsWhenRepositoryIsOnThirdBranchInsteadOfBaseOrWorkspaceBranch() throws IOException {
        Path repoPath = createGitRepo(tempDir.resolve("repos/internal/atenea"));
        ProjectEntity project = buildProject(7L, repoPath);

        when(projectRepository.findById(7L)).thenReturn(Optional.of(project));
        when(workSessionRepository.existsByProjectIdAndStatus(7L, WorkSessionStatus.OPEN)).thenReturn(false);
        when(gitRepositoryService.getCurrentBranch(repoPath.toString())).thenReturn("feature/random", "feature/random");
        when(gitRepositoryService.isWorkingTreeClean(repoPath.toString())).thenReturn(true);
        when(workSessionRepository.save(any(WorkSessionEntity.class))).thenAnswer(invocation -> {
            WorkSessionEntity entity = invocation.getArgument(0);
            if (entity.getId() == null) {
                entity.setId(12L);
            }
            return entity;
        });

        WorkSessionOperationBlockedException exception = assertThrows(
                WorkSessionOperationBlockedException.class,
                () -> workSessionService.openSession(7L, new CreateWorkSessionRequest("Inspect project state", "main")));

        assertEquals(
                "Repository is on branch 'feature/random' but WorkSession '12' can only prepare workspace branch " +
                        "'atenea/session-12' from base branch 'main' or from the workspace branch itself. " +
                        "Switch branches manually and retry.",
                exception.getMessage());
    }

    @Test
    void resolveSessionCreatesNewSessionWhenNoOpenSessionExists() throws IOException {
        Path repoPath = createGitRepo(tempDir.resolve("repos/internal/atenea"));
        ProjectEntity project = buildProject(7L, repoPath);

        when(projectRepository.findById(7L)).thenReturn(Optional.of(project));
        when(workSessionRepository.findByProjectIdAndStatus(7L, WorkSessionStatus.OPEN)).thenReturn(Optional.empty());
        when(workSessionRepository.existsByProjectIdAndStatus(7L, WorkSessionStatus.OPEN)).thenReturn(false);
        when(gitRepositoryService.getCurrentBranch(repoPath.toString())).thenReturn("release/2026");
        when(gitRepositoryService.isWorkingTreeClean(repoPath.toString())).thenReturn(true);
        when(gitRepositoryService.branchExists(repoPath.toString(), "atenea/session-15")).thenReturn(false);
        when(agentRunRepository.existsBySessionIdAndStatus(15L, AgentRunStatus.RUNNING)).thenReturn(false);
        when(workSessionRepository.save(any(WorkSessionEntity.class))).thenAnswer(invocation -> {
            WorkSessionEntity entity = invocation.getArgument(0);
            if (entity.getId() == null) {
                entity.setId(15L);
            }
            return entity;
        });
        workSessionService.setRemoteRoutingSelector(remoteRoutingSelector);

        ResolveWorkSessionResponse response = workSessionService.resolveSession(
                7L,
                new ResolveWorkSessionRequest(" Create canonical session ", " release/2026 "));

        assertTrue(response.created());
        assertEquals(15L, response.session().id());
        assertEquals("Create canonical session", response.session().title());
        assertEquals("release/2026", response.session().baseBranch());
        assertEquals("atenea/session-15", response.session().workspaceBranch());
        assertEquals(WorkSessionOperationalState.IDLE, response.session().operationalState());
        var snapshotOrder = inOrder(remoteRoutingSelector, attachmentPolicySnapshotter);
        snapshotOrder.verify(remoteRoutingSelector).pinNewSession(any(WorkSessionEntity.class));
        snapshotOrder.verify(attachmentPolicySnapshotter).snapshotNewSession(any(WorkSessionEntity.class));
    }

    @Test
    void resolveSessionRequiresTitleWhenNoOpenSessionExists() throws IOException {
        Path repoPath = createGitRepo(tempDir.resolve("repos/internal/atenea"));
        ProjectEntity project = buildProject(7L, repoPath);

        when(projectRepository.findById(7L)).thenReturn(Optional.of(project));
        when(workSessionRepository.findByProjectIdAndStatus(7L, WorkSessionStatus.OPEN)).thenReturn(Optional.empty());

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> workSessionService.resolveSession(7L, new ResolveWorkSessionRequest("   ", null)));

        assertEquals("Session title is required when no open WorkSession exists", exception.getMessage());
    }

    @Test
    void resolveSessionThrowsWhenProjectDoesNotExist() {
        when(projectRepository.findById(7L)).thenReturn(Optional.empty());

        assertThrows(WorkSessionProjectNotFoundException.class, () -> workSessionService.resolveSession(
                7L,
                new ResolveWorkSessionRequest("Inspect project state", null)));
    }

    @Test
    void resolveSessionViewReturnsExistingOpenSessionViewWithoutCreating() throws IOException {
        Path repoPath = createGitRepo(tempDir.resolve("repos/internal/atenea"));
        ProjectEntity project = buildProject(7L, repoPath);
        WorkSessionEntity session = buildSession(12L, 7L, repoPath, "main");
        AgentRunEntity latestRun = buildRun(55L, session, AgentRunStatus.SUCCEEDED, "Current status summary", null);

        when(projectRepository.findById(7L)).thenReturn(Optional.of(project));
        when(workSessionRepository.findByProjectIdAndStatus(7L, WorkSessionStatus.OPEN)).thenReturn(Optional.of(session));
        when(workSessionRepository.findWithProjectById(12L)).thenReturn(Optional.of(session));
        when(gitRepositoryService.getCurrentBranch(repoPath.toString())).thenReturn("main");
        when(gitRepositoryService.isWorkingTreeClean(repoPath.toString())).thenReturn(true);
        when(agentRunRepository.existsBySessionIdAndStatus(12L, AgentRunStatus.RUNNING)).thenReturn(false);
        when(agentRunRepository.findFirstBySessionIdOrderByCreatedAtDesc(12L)).thenReturn(Optional.of(latestRun));
        when(agentRunRepository.findFirstBySessionIdAndStatusOrderByCreatedAtDesc(12L, AgentRunStatus.SUCCEEDED))
                .thenReturn(Optional.of(latestRun));

        ResolveWorkSessionViewResponse response = workSessionService.resolveSessionView(
                7L,
                new ResolveWorkSessionRequest("Ignored title", "main"));

        assertFalse(response.created());
        assertEquals(12L, response.view().session().id());
        assertEquals(WorkSessionOperationalState.IDLE, response.view().session().operationalState());
        assertTrue(response.view().canCreateTurn());
        assertEquals("Current status summary", response.view().lastAgentResponse());
    }

    @Test
    void resolveSessionViewCreatesNewSessionWhenNoOpenSessionExists() throws IOException {
        Path repoPath = createGitRepo(tempDir.resolve("repos/internal/atenea"));
        ProjectEntity project = buildProject(7L, repoPath);

        when(projectRepository.findById(7L)).thenReturn(Optional.of(project));
        when(workSessionRepository.findByProjectIdAndStatus(7L, WorkSessionStatus.OPEN)).thenReturn(Optional.empty());
        when(workSessionRepository.existsByProjectIdAndStatus(7L, WorkSessionStatus.OPEN)).thenReturn(false);
        when(gitRepositoryService.getCurrentBranch(repoPath.toString())).thenReturn("release/2026");
        when(gitRepositoryService.isWorkingTreeClean(repoPath.toString())).thenReturn(true);
        when(gitRepositoryService.branchExists(repoPath.toString(), "atenea/session-15")).thenReturn(false);
        when(agentRunRepository.existsBySessionIdAndStatus(15L, AgentRunStatus.RUNNING)).thenReturn(false);
        when(workSessionRepository.save(any(WorkSessionEntity.class))).thenAnswer(invocation -> {
            WorkSessionEntity entity = invocation.getArgument(0);
            if (entity.getId() == null) {
                entity.setId(15L);
            }
            return entity;
        });
        WorkSessionEntity persistedSession = buildSession(15L, 7L, repoPath, "release/2026");
        persistedSession.setWorkspaceBranch("atenea/session-15");
        when(workSessionRepository.findWithProjectById(15L)).thenReturn(Optional.of(persistedSession));
        when(agentRunRepository.findFirstBySessionIdOrderByCreatedAtDesc(15L)).thenReturn(Optional.empty());
        when(agentRunRepository.findFirstBySessionIdAndStatusOrderByCreatedAtDesc(15L, AgentRunStatus.SUCCEEDED))
                .thenReturn(Optional.empty());

        ResolveWorkSessionViewResponse response = workSessionService.resolveSessionView(
                7L,
                new ResolveWorkSessionRequest(" Create canonical session ", " release/2026 "));

        assertTrue(response.created());
        assertEquals(15L, response.view().session().id());
        assertEquals("release/2026", response.view().session().baseBranch());
        assertEquals("atenea/session-15", response.view().session().workspaceBranch());
        assertEquals(WorkSessionOperationalState.IDLE, response.view().session().operationalState());
    }

    @Test
    void resolveSessionViewRequiresTitleWhenNoOpenSessionExists() throws IOException {
        Path repoPath = createGitRepo(tempDir.resolve("repos/internal/atenea"));
        ProjectEntity project = buildProject(7L, repoPath);

        when(projectRepository.findById(7L)).thenReturn(Optional.of(project));
        when(workSessionRepository.findByProjectIdAndStatus(7L, WorkSessionStatus.OPEN)).thenReturn(Optional.empty());

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> workSessionService.resolveSessionView(7L, new ResolveWorkSessionRequest("   ", null)));

        assertEquals("Session title is required when no open WorkSession exists", exception.getMessage());
    }

    @Test
    void resolveSessionViewThrowsWhenProjectDoesNotExist() {
        when(projectRepository.findById(7L)).thenReturn(Optional.empty());

        assertThrows(WorkSessionProjectNotFoundException.class, () -> workSessionService.resolveSessionView(
                7L,
                new ResolveWorkSessionRequest("Inspect project state", null)));
    }

    @Test
    void getSessionConversationViewReturnsIdleSessionWithNoTurns() throws IOException {
        Path repoPath = createGitRepo(tempDir.resolve("repos/internal/atenea"));
        WorkSessionEntity session = buildSession(12L, 7L, repoPath, "main");

        when(workSessionRepository.findWithProjectById(12L)).thenReturn(Optional.of(session));
        when(gitRepositoryService.getCurrentBranch(repoPath.toString())).thenReturn("main");
        when(gitRepositoryService.isWorkingTreeClean(repoPath.toString())).thenReturn(true);
        when(agentRunRepository.existsBySessionIdAndStatus(12L, AgentRunStatus.RUNNING)).thenReturn(false);
        when(agentRunRepository.findFirstBySessionIdOrderByCreatedAtDesc(12L)).thenReturn(Optional.empty());
        when(agentRunRepository.findFirstBySessionIdAndStatusOrderByCreatedAtDesc(12L, AgentRunStatus.SUCCEEDED))
                .thenReturn(Optional.empty());
        when(sessionTurnService.getTurns(12L, null, 20)).thenReturn(List.of());
        when(sessionTurnService.countVisibleTurns(12L)).thenReturn(0L);

        WorkSessionConversationViewResponse response = workSessionService.getSessionConversationView(12L);

        assertEquals(WorkSessionOperationalState.IDLE, response.view().session().operationalState());
        assertEquals(List.of(), response.recentTurns());
        assertEquals(20, response.recentTurnLimit());
        assertFalse(response.historyTruncated());
    }

    @Test
    void getSessionConversationViewReturnsRecentVisibleTurnsOnly() throws IOException {
        Path repoPath = createGitRepo(tempDir.resolve("repos/internal/atenea"));
        WorkSessionEntity session = buildSession(12L, 7L, repoPath, "main");
        AgentRunEntity latestRun = buildRun(55L, session, AgentRunStatus.RUNNING, null, null);
        List<SessionTurnResponse> turns = java.util.stream.LongStream.rangeClosed(1, 25)
                .mapToObj(id -> new SessionTurnResponse(
                        id,
                        id % 2 == 0
                                ? com.atenea.persistence.worksession.SessionTurnActor.CODEX
                                : com.atenea.persistence.worksession.SessionTurnActor.OPERATOR,
                        "Turn " + id,
                        Instant.parse("2026-03-25T10:%02d:00Z".formatted((int) (id % 60)))))
                .toList();

        when(workSessionRepository.findWithProjectById(12L)).thenReturn(Optional.of(session));
        when(gitRepositoryService.getCurrentBranch(repoPath.toString())).thenReturn("main");
        when(gitRepositoryService.isWorkingTreeClean(repoPath.toString())).thenReturn(true);
        when(agentRunRepository.existsBySessionIdAndStatus(12L, AgentRunStatus.RUNNING)).thenReturn(true);
        when(agentRunRepository.findFirstBySessionIdOrderByCreatedAtDesc(12L)).thenReturn(Optional.of(latestRun));
        when(agentRunRepository.findFirstBySessionIdAndStatusOrderByCreatedAtDesc(12L, AgentRunStatus.SUCCEEDED))
                .thenReturn(Optional.empty());
        when(sessionTurnService.getTurns(12L, null, 20)).thenReturn(turns.subList(5, 25));
        when(sessionTurnService.countVisibleTurns(12L)).thenReturn((long) turns.size());

        WorkSessionConversationViewResponse response = workSessionService.getSessionConversationView(12L);

        assertEquals(WorkSessionOperationalState.RUNNING, response.view().session().operationalState());
        assertEquals(20, response.recentTurns().size());
        assertEquals(6L, response.recentTurns().get(0).id());
        assertEquals(25L, response.recentTurns().get(19).id());
        assertTrue(response.historyTruncated());
    }

    @Test
    void getSessionConversationViewReturnsClosedSessionWithVisibleTurns() throws IOException {
        Path repoPath = createGitRepo(tempDir.resolve("repos/internal/atenea"));
        WorkSessionEntity session = buildSession(12L, 7L, repoPath, "main");
        session.setStatus(WorkSessionStatus.CLOSED);
        session.setClosedAt(Instant.parse("2026-03-25T10:10:00Z"));
        AgentRunEntity latestFailedRun = buildRun(56L, session, AgentRunStatus.FAILED, null, "Timed out");
        List<SessionTurnResponse> turns = List.of(
                new SessionTurnResponse(101L, com.atenea.persistence.worksession.SessionTurnActor.OPERATOR,
                        "Inspect project", Instant.parse("2026-03-25T10:05:00Z")),
                new SessionTurnResponse(102L, com.atenea.persistence.worksession.SessionTurnActor.CODEX,
                        "Timed out previously", Instant.parse("2026-03-25T10:06:00Z"))
        );

        when(workSessionRepository.findWithProjectById(12L)).thenReturn(Optional.of(session));
        when(gitRepositoryService.getCurrentBranch(repoPath.toString())).thenReturn("main");
        when(gitRepositoryService.isWorkingTreeClean(repoPath.toString())).thenReturn(false);
        when(agentRunRepository.existsBySessionIdAndStatus(12L, AgentRunStatus.RUNNING)).thenReturn(false);
        when(agentRunRepository.findFirstBySessionIdOrderByCreatedAtDesc(12L)).thenReturn(Optional.of(latestFailedRun));
        when(agentRunRepository.findFirstBySessionIdAndStatusOrderByCreatedAtDesc(12L, AgentRunStatus.SUCCEEDED))
                .thenReturn(Optional.empty());
        when(sessionTurnService.getTurns(12L, null, 20)).thenReturn(turns);
        when(sessionTurnService.countVisibleTurns(12L)).thenReturn((long) turns.size());

        WorkSessionConversationViewResponse response = workSessionService.getSessionConversationView(12L);

        assertEquals(WorkSessionOperationalState.CLOSED, response.view().session().operationalState());
        assertEquals(2, response.recentTurns().size());
        assertFalse(response.historyTruncated());
    }

    @Test
    void getSessionConversationViewThrowsWhenSessionDoesNotExist() {
        when(workSessionRepository.findWithProjectById(12L)).thenReturn(Optional.empty());

        assertThrows(WorkSessionNotFoundException.class, () -> workSessionService.getSessionConversationView(12L));
    }

    @Test
    void resolveSessionConversationViewReturnsExistingOpenSessionConversation() throws IOException {
        Path repoPath = createGitRepo(tempDir.resolve("repos/internal/atenea"));
        ProjectEntity project = buildProject(7L, repoPath);
        WorkSessionEntity session = buildSession(12L, 7L, repoPath, "main");
        AgentRunEntity latestRun = buildRun(55L, session, AgentRunStatus.SUCCEEDED, "Current status summary", null);
        List<SessionTurnResponse> turns = List.of(
                new SessionTurnResponse(101L, com.atenea.persistence.worksession.SessionTurnActor.OPERATOR,
                        "Inspect project", Instant.parse("2026-03-25T10:05:00Z")),
                new SessionTurnResponse(102L, com.atenea.persistence.worksession.SessionTurnActor.CODEX,
                        "Current status summary", Instant.parse("2026-03-25T10:06:00Z"))
        );

        when(projectRepository.findById(7L)).thenReturn(Optional.of(project));
        when(workSessionRepository.findByProjectIdAndStatus(7L, WorkSessionStatus.OPEN)).thenReturn(Optional.of(session));
        when(workSessionRepository.findWithProjectById(12L)).thenReturn(Optional.of(session));
        when(gitRepositoryService.getCurrentBranch(repoPath.toString())).thenReturn("main");
        when(gitRepositoryService.isWorkingTreeClean(repoPath.toString())).thenReturn(true);
        when(agentRunRepository.existsBySessionIdAndStatus(12L, AgentRunStatus.RUNNING)).thenReturn(false);
        when(agentRunRepository.findFirstBySessionIdOrderByCreatedAtDesc(12L)).thenReturn(Optional.of(latestRun));
        when(agentRunRepository.findFirstBySessionIdAndStatusOrderByCreatedAtDesc(12L, AgentRunStatus.SUCCEEDED))
                .thenReturn(Optional.of(latestRun));
        when(sessionTurnService.getTurns(12L, null, 20)).thenReturn(turns);
        when(sessionTurnService.countVisibleTurns(12L)).thenReturn((long) turns.size());

        ResolveWorkSessionConversationViewResponse response = workSessionService.resolveSessionConversationView(
                7L,
                new ResolveWorkSessionRequest("Ignored title", "main"));

        assertFalse(response.created());
        assertEquals(12L, response.view().view().session().id());
        assertEquals(2, response.view().recentTurns().size());
    }

    @Test
    void getSessionReturnsMappedResponse() throws IOException {
        Path repoPath = createGitRepo(tempDir.resolve("repos/internal/atenea"));
        WorkSessionEntity session = buildSession(12L, 7L, repoPath, "main");

        when(workSessionRepository.findWithProjectById(12L)).thenReturn(Optional.of(session));
        when(gitRepositoryService.getCurrentBranch(repoPath.toString())).thenReturn("main");
        when(gitRepositoryService.isWorkingTreeClean(repoPath.toString())).thenReturn(true);
        when(agentRunRepository.existsBySessionIdAndStatus(12L, AgentRunStatus.RUNNING)).thenReturn(false);

        WorkSessionResponse response = workSessionService.getSession(12L);

        assertEquals(12L, response.id());
        assertEquals(7L, response.projectId());
        assertEquals("main", response.baseBranch());
        assertEquals(WorkSessionOperationalState.IDLE, response.operationalState());
        assertEquals(new SessionOperationalSnapshotResponse(true, true, "main", false), response.repoState());
    }

    @Test
    void getSessionProjectsRemoteCloseStateErrorAndOperatorAction() throws IOException {
        Path repoPath = createGitRepo(tempDir.resolve("repos/internal/atenea"));
        WorkSessionEntity session = buildSession(12L, 7L, repoPath, "main");
        session.setRemoteCloseState(RemoteCloseState.BLOCKED);
        session.setRemoteCloseErrorCode("REMOTE_CLOSE_OWNERSHIP_MISMATCH");

        when(workSessionRepository.findWithProjectById(12L)).thenReturn(Optional.of(session));
        when(gitRepositoryService.getCurrentBranch(repoPath.toString())).thenReturn("main");
        when(gitRepositoryService.isWorkingTreeClean(repoPath.toString())).thenReturn(true);
        when(agentRunRepository.existsBySessionIdAndStatus(12L, AgentRunStatus.RUNNING)).thenReturn(false);

        WorkSessionResponse response = workSessionService.getSession(12L);

        assertEquals(RemoteCloseState.BLOCKED, response.remoteCloseState());
        assertEquals("REMOTE_CLOSE_OWNERSHIP_MISMATCH", response.remoteCloseErrorCode());
        assertEquals(
                AgentRunRecoveryNextAction.CONTACT_PLATFORM_ADMINISTRATOR,
                response.remoteCloseNextAction());
    }

    @Test
    void getSessionReturnsRepoInvalidSnapshotWhenProjectRepoIsNoLongerOperational() throws IOException {
        Path repoPath = createGitRepo(tempDir.resolve("repos/internal/atenea"));
        WorkSessionEntity session = buildSession(12L, 7L, repoPath, "main");
        Files.delete(repoPath.resolve(".git"));

        when(workSessionRepository.findWithProjectById(12L)).thenReturn(Optional.of(session));
        when(agentRunRepository.existsBySessionIdAndStatus(12L, AgentRunStatus.RUNNING)).thenReturn(true);

        WorkSessionResponse response = workSessionService.getSession(12L);

        assertEquals(WorkSessionOperationalState.RUNNING, response.operationalState());
        assertEquals(new SessionOperationalSnapshotResponse(false, false, null, true), response.repoState());
    }

    @Test
    void getSessionThrowsWhenNotFound() {
        when(workSessionRepository.findWithProjectById(12L)).thenReturn(Optional.empty());

        assertThrows(WorkSessionNotFoundException.class, () -> workSessionService.getSession(12L));
    }

    @Test
    void getSessionViewReturnsIdleSessionWithNoRuns() throws IOException {
        Path repoPath = createGitRepo(tempDir.resolve("repos/internal/atenea"));
        WorkSessionEntity session = buildSession(12L, 7L, repoPath, "main");

        when(workSessionRepository.findWithProjectById(12L)).thenReturn(Optional.of(session));
        when(gitRepositoryService.getCurrentBranch(repoPath.toString())).thenReturn("main");
        when(gitRepositoryService.isWorkingTreeClean(repoPath.toString())).thenReturn(true);
        when(agentRunRepository.existsBySessionIdAndStatus(12L, AgentRunStatus.RUNNING)).thenReturn(false);
        when(agentRunRepository.findFirstBySessionIdOrderByCreatedAtDesc(12L)).thenReturn(Optional.empty());
        when(agentRunRepository.findFirstBySessionIdAndStatusOrderByCreatedAtDesc(12L, AgentRunStatus.SUCCEEDED))
                .thenReturn(Optional.empty());

        WorkSessionViewResponse response = workSessionService.getSessionView(12L);

        assertEquals(WorkSessionOperationalState.IDLE, response.session().operationalState());
        assertFalse(response.runInProgress());
        assertTrue(response.canCreateTurn());
        assertNull(response.latestRun());
        assertNull(response.lastError());
        assertNull(response.lastAgentResponse());
    }

    @Test
    void getSessionViewReturnsRunningSessionWithLatestRun() throws IOException {
        Path repoPath = createGitRepo(tempDir.resolve("repos/internal/atenea"));
        WorkSessionEntity session = buildSession(12L, 7L, repoPath, "main");
        AgentRunEntity latestRun = buildRun(55L, session, AgentRunStatus.RUNNING, null, null);

        when(workSessionRepository.findWithProjectById(12L)).thenReturn(Optional.of(session));
        when(gitRepositoryService.getCurrentBranch(repoPath.toString())).thenReturn("main");
        when(gitRepositoryService.isWorkingTreeClean(repoPath.toString())).thenReturn(true);
        when(agentRunRepository.existsBySessionIdAndStatus(12L, AgentRunStatus.RUNNING)).thenReturn(true);
        when(agentRunRepository.findFirstBySessionIdOrderByCreatedAtDesc(12L)).thenReturn(Optional.of(latestRun));
        when(agentRunRepository.findFirstBySessionIdAndStatusOrderByCreatedAtDesc(12L, AgentRunStatus.SUCCEEDED))
                .thenReturn(Optional.empty());

        WorkSessionViewResponse response = workSessionService.getSessionView(12L);

        assertEquals(WorkSessionOperationalState.RUNNING, response.session().operationalState());
        assertTrue(response.runInProgress());
        assertFalse(response.canCreateTurn());
        assertEquals(55L, response.latestRun().id());
        assertEquals(AgentRunStatus.RUNNING, response.latestRun().status());
    }

    @Test
    void getSessionViewDoesNotExposeStaleLastErrorWhileNewRunIsRunning() throws IOException {
        Path repoPath = createGitRepo(tempDir.resolve("repos/internal/atenea"));
        WorkSessionEntity session = buildSession(12L, 7L, repoPath, "main");
        AgentRunEntity latestRun = buildRun(57L, session, AgentRunStatus.RUNNING, null, null);
        AgentRunEntity latestSucceededRun = buildRun(55L, session, AgentRunStatus.SUCCEEDED, "Previous answer", null);

        when(workSessionRepository.findWithProjectById(12L)).thenReturn(Optional.of(session));
        when(gitRepositoryService.getCurrentBranch(repoPath.toString())).thenReturn("main");
        when(gitRepositoryService.isWorkingTreeClean(repoPath.toString())).thenReturn(true);
        when(agentRunRepository.existsBySessionIdAndStatus(12L, AgentRunStatus.RUNNING)).thenReturn(true);
        when(agentRunRepository.findFirstBySessionIdOrderByCreatedAtDesc(12L)).thenReturn(Optional.of(latestRun));
        when(agentRunRepository.findFirstBySessionIdAndStatusOrderByCreatedAtDesc(12L, AgentRunStatus.SUCCEEDED))
                .thenReturn(Optional.of(latestSucceededRun));

        WorkSessionViewResponse response = workSessionService.getSessionView(12L);

        assertEquals(AgentRunStatus.RUNNING, response.latestRun().status());
        assertNull(response.lastError());
        assertEquals("Previous answer", response.lastAgentResponse());
    }

    @Test
    void getSessionViewReconcilesStaleRunningRunAndReleasesSessionToIdle() throws IOException {
        Path repoPath = createGitRepo(tempDir.resolve("repos/internal/atenea"));
        WorkSessionEntity session = buildSession(12L, 7L, repoPath, "main");
        AgentRunEntity staleRun = buildRun(55L, session, AgentRunStatus.RUNNING, null, null);
        staleRun.setStartedAt(Instant.now().minus(Duration.ofMinutes(7)));

        when(workSessionRepository.findWithProjectById(12L)).thenReturn(Optional.of(session));
        when(gitRepositoryService.getCurrentBranch(repoPath.toString())).thenReturn("main");
        when(gitRepositoryService.isWorkingTreeClean(repoPath.toString())).thenReturn(true);
        when(agentRunRepository.findBySessionIdAndStatusOrderByCreatedAtAsc(12L, AgentRunStatus.RUNNING))
                .thenReturn(List.of(staleRun));
        when(agentRunRepository.saveAndFlush(any(AgentRunEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(agentRunRepository.existsBySessionIdAndStatus(12L, AgentRunStatus.RUNNING)).thenReturn(false);
        when(agentRunRepository.findFirstBySessionIdOrderByCreatedAtDesc(12L)).thenReturn(Optional.of(staleRun));
        when(agentRunRepository.findFirstBySessionIdAndStatusOrderByCreatedAtDesc(12L, AgentRunStatus.SUCCEEDED))
                .thenReturn(Optional.empty());

        WorkSessionViewResponse response = workSessionService.getSessionView(12L);

        assertEquals(WorkSessionOperationalState.IDLE, response.session().operationalState());
        assertTrue(response.canCreateTurn());
        assertEquals(AgentRunStatus.FAILED, response.latestRun().status());
        assertEquals(
                "Marked FAILED during reconciliation because the run stayed RUNNING past the stale timeout window",
                response.lastError());
    }

    @Test
    void getSessionViewReturnsClosedSessionWithLastErrorAndLastAgentResponse() throws IOException {
        Path repoPath = createGitRepo(tempDir.resolve("repos/internal/atenea"));
        WorkSessionEntity session = buildSession(12L, 7L, repoPath, "main");
        session.setStatus(WorkSessionStatus.CLOSED);
        session.setClosedAt(Instant.parse("2026-03-25T10:10:00Z"));
        AgentRunEntity latestFailedRun = buildRun(56L, session, AgentRunStatus.FAILED, null, "Timed out");
        AgentRunEntity latestSucceededRun = buildRun(55L, session, AgentRunStatus.SUCCEEDED, "Implemented change", null);

        when(workSessionRepository.findWithProjectById(12L)).thenReturn(Optional.of(session));
        when(gitRepositoryService.getCurrentBranch(repoPath.toString())).thenReturn("main");
        when(gitRepositoryService.isWorkingTreeClean(repoPath.toString())).thenReturn(false);
        when(agentRunRepository.existsBySessionIdAndStatus(12L, AgentRunStatus.RUNNING)).thenReturn(false);
        when(agentRunRepository.findFirstBySessionIdOrderByCreatedAtDesc(12L)).thenReturn(Optional.of(latestFailedRun));
        when(agentRunRepository.findFirstBySessionIdAndStatusOrderByCreatedAtDesc(12L, AgentRunStatus.SUCCEEDED))
                .thenReturn(Optional.of(latestSucceededRun));

        WorkSessionViewResponse response = workSessionService.getSessionView(12L);

        assertEquals(WorkSessionOperationalState.CLOSED, response.session().operationalState());
        assertFalse(response.runInProgress());
        assertFalse(response.canCreateTurn());
        assertEquals(56L, response.latestRun().id());
        assertEquals("Timed out", response.lastError());
        assertEquals("Implemented change", response.lastAgentResponse());
        assertEquals(new SessionOperationalSnapshotResponse(true, false, "main", false), response.session().repoState());
    }

    @Test
    void getSessionViewThrowsWhenNotFound() {
        when(workSessionRepository.findWithProjectById(12L)).thenReturn(Optional.empty());

        assertThrows(WorkSessionNotFoundException.class, () -> workSessionService.getSessionView(12L));
    }

    @Test
    void closeSessionClosesOpenSessionWithoutRunningRun() throws IOException {
        Path repoPath = createGitRepo(tempDir.resolve("repos/internal/atenea"));
        WorkSessionEntity session = buildSession(12L, 7L, repoPath, "main");
        session.setWorkspaceBranch("atenea/session-12");

        when(workSessionRepository.findWithProjectById(12L)).thenReturn(Optional.of(session));
        when(agentRunRepository.existsBySessionIdAndStatus(12L, AgentRunStatus.RUNNING)).thenReturn(false);
        when(gitRepositoryService.getCurrentBranch(repoPath.toString())).thenReturn("atenea/session-12", "main", "main");
        when(gitRepositoryService.isWorkingTreeClean(repoPath.toString())).thenReturn(true, true);
        when(gitRepositoryService.branchExists(repoPath.toString(), "atenea/session-12")).thenReturn(true, false);
        when(gitRepositoryService.remoteBranchExists(repoPath.toString(), "atenea/session-12")).thenReturn(false, false);

        WorkSessionResponse response = workSessionService.closeSession(12L);

        assertEquals(WorkSessionStatus.CLOSED, response.status());
        assertEquals(WorkSessionOperationalState.CLOSED, response.operationalState());
        assertEquals(WorkSessionStatus.CLOSED, session.getStatus());
        assertEquals(response.closedAt(), session.getClosedAt());
        assertEquals(response.closedAt(), session.getUpdatedAt());
        assertEquals(RemoteCloseState.NOT_REQUIRED, session.getRemoteCloseState());
        verify(remoteWorkerClient, never()).releaseWorkspace(any());
        verify(gitRepositoryService).checkoutBranch(repoPath.toString(), "main");
        verify(gitRepositoryService, never()).fetchOrigin(repoPath.toString());
        verify(gitRepositoryService, never()).fastForwardCurrentBranchToOrigin(repoPath.toString(), "main");
        verify(gitRepositoryService).deleteLocalBranch(repoPath.toString(), "atenea/session-12");
    }

    @Test
    void remoteCloseCommitsDeliveryThenOperationThenExactReleasedReceipt() throws IOException {
        Path repoPath = createGitRepo(tempDir.resolve("repos/internal/atenea-remote-close"));
        WorkSessionEntity session = remoteSession(repoPath);
        prepareSuccessfulCloseMocks(session, repoPath);
        when(remoteWorkerProperties.isRemoteCloseReleaseEnabledFor(
                ProjectCodexIdentity.PROJECT_IDENTITY)).thenReturn(true);
        when(remoteWorkerClient.releaseWorkspace(session)).thenAnswer(
                ignored -> releasedReceipt(session));

        WorkSessionResponse response = workSessionService.closeSession(12L);

        assertEquals(WorkSessionStatus.CLOSED, response.status());
        assertEquals(RemoteCloseState.RELEASED, response.remoteCloseState());
        assertEquals(WorkSessionStatus.CLOSED, session.getStatus());
        assertEquals(RemoteCloseState.RELEASED, session.getRemoteCloseState());
        assertTrue(session.getRemoteCloseOperationId() != null);
        assertEquals("9".repeat(64), session.getRemoteCloseReceiptSha256());
        assertEquals(6, session.getRemoteCloseRevision());
        assertTrue(session.getRemoteCloseRequestedAt() != null);
        assertTrue(session.getRemoteCloseReleasedAt() != null);
        assertEquals(session.getRemoteCloseReleasedAt(), session.getClosedAt());

        org.mockito.InOrder order = inOrder(transactionManager, remoteWorkerClient);
        order.verify(transactionManager).getTransaction(any(TransactionDefinition.class));
        order.verify(transactionManager).commit(any(TransactionStatus.class));
        order.verify(transactionManager).getTransaction(any(TransactionDefinition.class));
        order.verify(transactionManager).commit(any(TransactionStatus.class));
        order.verify(remoteWorkerClient).releaseWorkspace(session);
        order.verify(transactionManager).getTransaction(any(TransactionDefinition.class));
        order.verify(transactionManager).commit(any(TransactionStatus.class));

        WorkSessionResponse repeated = workSessionService.closeSession(12L);

        assertEquals(WorkSessionStatus.CLOSED, repeated.status());
        assertEquals(RemoteCloseState.RELEASED, repeated.remoteCloseState());
        assertEquals("9".repeat(64), session.getRemoteCloseReceiptSha256());
        verify(remoteWorkerClient, times(1)).releaseWorkspace(session);
    }

    @Test
    void remoteCloseRaceAfterPreparationReusesAlreadyReleasedOperation() throws IOException {
        Path repoPath = createGitRepo(tempDir.resolve("repos/internal/atenea-close-race"));
        WorkSessionEntity preparing = remoteSession(repoPath);
        prepareSuccessfulCloseMocks(preparing, repoPath);
        when(remoteWorkerProperties.isRemoteCloseReleaseEnabledFor(
                ProjectCodexIdentity.PROJECT_IDENTITY)).thenReturn(true);

        UUID operationId = UUID.fromString("22222222-2222-4222-8222-222222222222");
        WorkSessionEntity released = remoteSession(repoPath);
        markReleased(released, operationId);
        when(workSessionRepository.findLockedWithProjectById(12L))
                .thenReturn(Optional.of(released));
        when(remoteWorkerClient.releaseWorkspace(released)).thenReturn(releasedReceipt(released));

        WorkSessionResponse response = workSessionService.closeSession(12L);

        assertEquals(WorkSessionStatus.CLOSED, response.status());
        assertEquals(RemoteCloseState.RELEASED, response.remoteCloseState());
        assertEquals(operationId, released.getRemoteCloseOperationId());
        assertEquals("9".repeat(64), released.getRemoteCloseReceiptSha256());
        verify(remoteWorkerClient, times(1)).releaseWorkspace(released);
    }

    @Test
    void malformedPersistedReleasedProjectionIsNotAcceptedAsIdempotentClose() throws IOException {
        Path repoPath = createGitRepo(tempDir.resolve("repos/internal/atenea-malformed-release"));
        WorkSessionEntity released = remoteSession(repoPath);
        markReleased(released, UUID.fromString("22222222-2222-4222-8222-222222222222"));
        released.setRemoteCloseRevision(5);
        when(workSessionRepository.findWithProjectById(12L)).thenReturn(Optional.of(released));

        assertThrows(WorkSessionNotOpenException.class,
                () -> workSessionService.closeSession(12L));

        verify(remoteWorkerClient, never()).releaseWorkspace(any());
    }

    @Test
    void lateTransportFailureCannotDowngradeAlreadyReleasedClose() throws IOException {
        Path repoPath = createGitRepo(tempDir.resolve("repos/internal/atenea-late-failure"));
        UUID operationId = UUID.fromString("22222222-2222-4222-8222-222222222222");
        WorkSessionEntity requested = remoteSession(repoPath);
        requested.setStatus(WorkSessionStatus.CLOSING);
        requested.setRemoteCloseState(RemoteCloseState.REQUESTED);
        requested.setRemoteCloseOperationId(operationId);
        requested.setRemoteCloseRevision(1);
        requested.setRemoteCloseRequestedAt(Instant.parse("2026-08-03T10:00:00Z"));
        requested.setRemoteCloseUpdatedAt(Instant.parse("2026-08-03T10:00:00Z"));
        WorkSessionEntity released = remoteSession(repoPath);
        markReleased(released, operationId);
        when(remoteWorkerProperties.isRemoteCloseReconciliationEnabledFor(
                ProjectCodexIdentity.PROJECT_IDENTITY)).thenReturn(true);
        when(workSessionRepository.findLockedWithProjectById(12L))
                .thenReturn(Optional.of(requested), Optional.of(released));
        when(workSessionRepository.saveAndFlush(requested)).thenReturn(requested);
        when(remoteWorkerClient.releaseWorkspace(requested)).thenThrow(
                new RemoteWorkerException("Remote worker I/O failed", new IOException("closed")));

        WorkSessionResponse response = workSessionService.reconcileRemoteClose(12L);

        assertEquals(WorkSessionStatus.CLOSED, response.status());
        assertEquals(RemoteCloseState.RELEASED, response.remoteCloseState());
        assertEquals(operationId, released.getRemoteCloseOperationId());
        assertEquals("9".repeat(64), released.getRemoteCloseReceiptSha256());
        assertNull(released.getRemoteCloseErrorCode());
    }

    @Test
    void remoteCloseGateDisabledKeepsSessionClosingWithoutCreatingOperation() throws IOException {
        Path repoPath = createGitRepo(tempDir.resolve("repos/internal/atenea-gate-off"));
        WorkSessionEntity session = remoteSession(repoPath);
        prepareSuccessfulCloseMocks(session, repoPath);
        when(remoteWorkerProperties.isRemoteCloseReleaseEnabledFor(
                ProjectCodexIdentity.PROJECT_IDENTITY)).thenReturn(false);

        WorkSessionCloseBlockedException exception = assertThrows(
                WorkSessionCloseBlockedException.class,
                () -> workSessionService.closeSession(12L));

        assertEquals("remote_close_disabled", exception.getState());
        assertEquals(WorkSessionStatus.CLOSING, session.getStatus());
        assertEquals(RemoteCloseState.NOT_STARTED, session.getRemoteCloseState());
        assertNull(session.getRemoteCloseOperationId());
        assertNull(session.getClosedAt());
        verify(remoteWorkerClient, never()).releaseWorkspace(any());
    }

    @Test
    void remoteCloseTransportFailureRetainsCommittedOperationForReconciliation() throws IOException {
        Path repoPath = createGitRepo(tempDir.resolve("repos/internal/atenea-transport"));
        WorkSessionEntity session = remoteSession(repoPath);
        prepareSuccessfulCloseMocks(session, repoPath);
        when(remoteWorkerProperties.isRemoteCloseReleaseEnabledFor(
                ProjectCodexIdentity.PROJECT_IDENTITY)).thenReturn(true);
        when(remoteWorkerProperties.isRemoteCloseReconciliationEnabledFor(
                ProjectCodexIdentity.PROJECT_IDENTITY)).thenReturn(true);
        when(remoteWorkerClient.releaseWorkspace(session)).thenThrow(
                new RemoteWorkerException("Remote worker I/O failed", new IOException("closed")));

        WorkSessionCloseBlockedException exception = assertThrows(
                WorkSessionCloseBlockedException.class,
                () -> workSessionService.closeSession(12L));

        assertEquals("REMOTE_WORKER_TRANSPORT_FAILURE", exception.getState());
        assertEquals(true, exception.isRetryable());
        assertEquals(WorkSessionStatus.CLOSING, session.getStatus());
        assertEquals(RemoteCloseState.RECONCILING, session.getRemoteCloseState());
        assertTrue(session.getRemoteCloseOperationId() != null);
        assertEquals(2, session.getRemoteCloseRevision());
        assertNull(session.getRemoteCloseReceiptSha256());
        assertNull(session.getRemoteCloseReleasedAt());
        assertNull(session.getClosedAt());

        UUID operationId = session.getRemoteCloseOperationId();
        doReturn(releasedReceipt(session)).when(remoteWorkerClient).releaseWorkspace(session);

        WorkSessionResponse reconciled = workSessionService.reconcileRemoteClose(12L);

        assertEquals(operationId, session.getRemoteCloseOperationId());
        assertEquals(WorkSessionStatus.CLOSED, reconciled.status());
        assertEquals(RemoteCloseState.RELEASED, reconciled.remoteCloseState());
        verify(remoteWorkerClient, times(2)).releaseWorkspace(session);
    }

    @Test
    void crashAfterRequestCommitReusesOperationWithoutRepeatingDelivery() throws IOException {
        Path repoPath = createGitRepo(tempDir.resolve("repos/internal/atenea-after-request"));
        WorkSessionEntity session = remoteSession(repoPath);
        prepareSuccessfulCloseMocks(session, repoPath);
        when(remoteWorkerProperties.isRemoteCloseReleaseEnabledFor(
                ProjectCodexIdentity.PROJECT_IDENTITY)).thenReturn(true);
        when(remoteWorkerProperties.isRemoteCloseReconciliationEnabledFor(
                ProjectCodexIdentity.PROJECT_IDENTITY)).thenReturn(true);
        when(remoteWorkerClient.releaseWorkspace(session))
                .thenThrow(new IllegalStateException("simulated process stop"));

        assertThrows(IllegalStateException.class, () -> workSessionService.closeSession(12L));
        UUID operationId = session.getRemoteCloseOperationId();
        assertTrue(operationId != null);
        assertEquals(RemoteCloseState.REQUESTED, session.getRemoteCloseState());
        assertEquals(WorkSessionStatus.CLOSING, session.getStatus());

        doReturn(releasedReceipt(session)).when(remoteWorkerClient).releaseWorkspace(session);
        WorkSessionResponse reconciled = workSessionService.reconcileRemoteClose(12L);

        assertEquals(operationId, session.getRemoteCloseOperationId());
        assertEquals(WorkSessionStatus.CLOSED, reconciled.status());
        assertEquals(RemoteCloseState.RELEASED, reconciled.remoteCloseState());
        verify(gitRepositoryService, times(1)).checkoutBranch(repoPath.toString(), "main");
        verify(remoteWorkerClient, times(2)).releaseWorkspace(session);
    }

    @Test
    void crashBeforeFinalCommitRepeatsReceiptWithSameOperationAndClosesOnce() throws IOException {
        Path repoPath = createGitRepo(tempDir.resolve("repos/internal/atenea-before-commit"));
        WorkSessionEntity session = remoteSession(repoPath);
        prepareSuccessfulCloseMocks(session, repoPath);
        when(remoteWorkerProperties.isRemoteCloseReleaseEnabledFor(
                ProjectCodexIdentity.PROJECT_IDENTITY)).thenReturn(true);
        when(remoteWorkerProperties.isRemoteCloseReconciliationEnabledFor(
                ProjectCodexIdentity.PROJECT_IDENTITY)).thenReturn(true);
        when(remoteWorkerClient.releaseWorkspace(session)).thenAnswer(
                ignored -> releasedReceipt(session));
        java.util.concurrent.atomic.AtomicBoolean failFinalCommit =
                new java.util.concurrent.atomic.AtomicBoolean(true);
        lenient().when(workSessionRepository.saveAndFlush(any(WorkSessionEntity.class)))
                .thenAnswer(invocation -> {
                    WorkSessionEntity value = invocation.getArgument(0);
                    if (value.getRemoteCloseState() == RemoteCloseState.RELEASED
                            && failFinalCommit.getAndSet(false)) {
                        throw new IllegalStateException("simulated final commit loss");
                    }
                    return value;
                });

        assertThrows(IllegalStateException.class, () -> workSessionService.closeSession(12L));
        UUID operationId = session.getRemoteCloseOperationId();
        Instant requestedAt = session.getRemoteCloseRequestedAt();

        session.setStatus(WorkSessionStatus.CLOSING);
        session.setClosedAt(null);
        session.setRemoteCloseState(RemoteCloseState.REQUESTED);
        session.setRemoteCloseRevision(1);
        session.setRemoteCloseReceiptSha256(null);
        session.setRemoteCloseReleasedAt(null);
        session.setRemoteCloseUpdatedAt(requestedAt);

        WorkSessionResponse reconciled = workSessionService.reconcileRemoteClose(12L);

        assertEquals(operationId, session.getRemoteCloseOperationId());
        assertEquals(WorkSessionStatus.CLOSED, reconciled.status());
        assertEquals(RemoteCloseState.RELEASED, reconciled.remoteCloseState());
        assertEquals("9".repeat(64), session.getRemoteCloseReceiptSha256());
        verify(remoteWorkerClient, times(2)).releaseWorkspace(session);
    }

    @Test
    void deterministicRemoteCloseRejectionBlocksWithoutFalseClosure() throws IOException {
        Path repoPath = createGitRepo(tempDir.resolve("repos/internal/atenea-rejected"));
        WorkSessionEntity session = remoteSession(repoPath);
        prepareSuccessfulCloseMocks(session, repoPath);
        when(remoteWorkerProperties.isRemoteCloseReleaseEnabledFor(
                ProjectCodexIdentity.PROJECT_IDENTITY)).thenReturn(true);
        when(remoteWorkerClient.releaseWorkspace(session)).thenThrow(new RemoteWorkerException(
                "Remote worker rejected request with HTTP 409",
                409,
                "WORKSPACE_RELEASE_OWNERSHIP_CONFLICT",
                RemoteWorkerFailureCategory.OWNERSHIP,
                false,
                AgentRunRecoveryNextAction.CONTACT_PLATFORM_ADMINISTRATOR,
                null));

        WorkSessionCloseBlockedException exception = assertThrows(
                WorkSessionCloseBlockedException.class,
                () -> workSessionService.closeSession(12L));

        assertEquals("WORKSPACE_RELEASE_OWNERSHIP_CONFLICT", exception.getState());
        assertEquals(false, exception.isRetryable());
        assertEquals(WorkSessionStatus.CLOSING, session.getStatus());
        assertEquals(RemoteCloseState.BLOCKED, session.getRemoteCloseState());
        assertTrue(session.getRemoteCloseOperationId() != null);
        assertNull(session.getRemoteCloseReceiptSha256());
        assertNull(session.getClosedAt());
    }

    @Test
    void deterministicFourHundredCannotMasqueradeAsTransportReconciliation()
            throws IOException {
        Path repoPath = createGitRepo(tempDir.resolve("repos/internal/atenea-false-transport"));
        WorkSessionEntity session = remoteSession(repoPath);
        prepareSuccessfulCloseMocks(session, repoPath);
        when(remoteWorkerProperties.isRemoteCloseReleaseEnabledFor(
                ProjectCodexIdentity.PROJECT_IDENTITY)).thenReturn(true);
        when(remoteWorkerClient.releaseWorkspace(session)).thenThrow(new RemoteWorkerException(
                "incompatible typed rejection",
                403,
                "WORKER_AUTHORIZATION_REJECTED",
                RemoteWorkerFailureCategory.TRANSPORT,
                true,
                AgentRunRecoveryNextAction.REQUEST_RECONCILIATION,
                null));

        WorkSessionCloseBlockedException exception = assertThrows(
                WorkSessionCloseBlockedException.class,
                () -> workSessionService.closeSession(12L));

        assertEquals("REMOTE_WORKER_PROTOCOL_FAILURE", exception.getState());
        assertFalse(exception.isRetryable());
        assertEquals(WorkSessionStatus.CLOSING, session.getStatus());
        assertEquals(RemoteCloseState.BLOCKED, session.getRemoteCloseState());
        assertNull(session.getRemoteCloseReceiptSha256());
        assertNull(session.getClosedAt());
    }

    @Test
    void cleanConversationalSessionWithoutCommitsCanCloseWithoutPullRequest() throws IOException {
        Path repoPath = createGitRepo(tempDir.resolve("repos/internal/atenea"));
        WorkSessionEntity session = buildSession(12L, 7L, repoPath, "main");
        session.setWorkspaceBranch("atenea/session-12");

        when(workSessionRepository.findWithProjectById(12L)).thenReturn(Optional.of(session));
        when(agentRunRepository.existsBySessionIdAndStatusIn(
                12L, AgentRunStatus.nonTerminalStatuses())).thenReturn(false);
        when(gitRepositoryService.getCurrentBranch(repoPath.toString()))
                .thenReturn("atenea/session-12");
        when(gitRepositoryService.isWorkingTreeClean(repoPath.toString())).thenReturn(true);
        when(gitRepositoryService.branchExists(repoPath.toString(), "atenea/session-12"))
                .thenReturn(false);

        assertTrue(workSessionService.canCloseUnpublishedSession(12L));
    }

    @Test
    void unpublishedSessionCommitKeepsPublishAsRequiredAction() throws IOException {
        Path repoPath = createGitRepo(tempDir.resolve("repos/internal/atenea"));
        WorkSessionEntity session = buildSession(12L, 7L, repoPath, "main");
        session.setWorkspaceBranch("atenea/session-12");

        when(workSessionRepository.findWithProjectById(12L)).thenReturn(Optional.of(session));
        when(agentRunRepository.existsBySessionIdAndStatusIn(
                12L, AgentRunStatus.nonTerminalStatuses())).thenReturn(false);
        when(gitRepositoryService.getCurrentBranch(repoPath.toString()))
                .thenReturn("atenea/session-12");
        when(gitRepositoryService.isWorkingTreeClean(repoPath.toString())).thenReturn(true);
        when(gitRepositoryService.branchExists(repoPath.toString(), "atenea/session-12"))
                .thenReturn(true);
        when(gitRepositoryService.branchContainsCommitsBeyond(
                repoPath.toString(), "main", "atenea/session-12")).thenReturn(true);

        assertFalse(workSessionService.canCloseUnpublishedSession(12L));
    }

    @Test
    void closeSessionAllowsUnpublishedSessionWithoutOriginRemote() throws IOException {
        Path repoPath = createGitRepo(tempDir.resolve("repos/internal/atenea"));
        WorkSessionEntity session = buildSession(12L, 7L, repoPath, "main");

        when(workSessionRepository.findWithProjectById(12L)).thenReturn(Optional.of(session));
        when(agentRunRepository.existsBySessionIdAndStatus(12L, AgentRunStatus.RUNNING)).thenReturn(false);
        when(gitRepositoryService.getCurrentBranch(repoPath.toString())).thenReturn("main", "main");
        when(gitRepositoryService.isWorkingTreeClean(repoPath.toString())).thenReturn(true, true);

        WorkSessionResponse response = workSessionService.closeSession(12L);

        assertEquals(WorkSessionStatus.CLOSED, response.status());
        verify(gitRepositoryService, never()).fetchOrigin(repoPath.toString());
        verify(gitRepositoryService, never()).fastForwardCurrentBranchToOrigin(repoPath.toString(), "main");
    }

    @Test
    void closeSessionThrowsWhenSessionIsAlreadyClosed() throws IOException {
        Path repoPath = createGitRepo(tempDir.resolve("repos/internal/atenea"));
        WorkSessionEntity session = buildSession(12L, 7L, repoPath, "main");
        session.setStatus(WorkSessionStatus.CLOSED);
        session.setClosedAt(Instant.parse("2026-03-25T10:10:00Z"));

        when(workSessionRepository.findWithProjectById(12L)).thenReturn(Optional.of(session));

        assertThrows(WorkSessionNotOpenException.class, () -> workSessionService.closeSession(12L));
    }

    @Test
    void closeSessionThrowsWhenRunningRunExists() throws IOException {
        Path repoPath = createGitRepo(tempDir.resolve("repos/internal/atenea"));
        WorkSessionEntity session = buildSession(12L, 7L, repoPath, "main");

        when(workSessionRepository.findWithProjectById(12L)).thenReturn(Optional.of(session));
        when(agentRunRepository.existsBySessionIdAndStatus(12L, AgentRunStatus.RUNNING)).thenReturn(true);

        WorkSessionCloseBlockedException exception = assertThrows(
                WorkSessionCloseBlockedException.class,
                () -> workSessionService.closeSession(12L));

        assertEquals(
                "WorkSession '12' cannot finish closing: WorkSession still has a running AgentRun",
                exception.getMessage());
        assertEquals(WorkSessionStatus.CLOSING, session.getStatus());
    }

    @Test
    void closeSessionAllowsClosingWhenOnlyStaleRunningRunExists() throws IOException {
        Path repoPath = createGitRepo(tempDir.resolve("repos/internal/atenea"));
        WorkSessionEntity session = buildSession(12L, 7L, repoPath, "main");
        session.setWorkspaceBranch("atenea/session-12");
        AgentRunEntity staleRun = buildRun(55L, session, AgentRunStatus.RUNNING, null, null);
        staleRun.setStartedAt(Instant.now().minus(Duration.ofMinutes(7)));

        when(workSessionRepository.findWithProjectById(12L)).thenReturn(Optional.of(session));
        when(agentRunRepository.findBySessionIdAndStatusOrderByCreatedAtAsc(12L, AgentRunStatus.RUNNING))
                .thenReturn(List.of(staleRun));
        when(agentRunRepository.saveAndFlush(any(AgentRunEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(agentRunRepository.existsBySessionIdAndStatus(12L, AgentRunStatus.RUNNING)).thenReturn(false);
        when(gitRepositoryService.getCurrentBranch(repoPath.toString())).thenReturn("atenea/session-12", "main", "main");
        when(gitRepositoryService.isWorkingTreeClean(repoPath.toString())).thenReturn(true, true);
        when(gitRepositoryService.branchExists(repoPath.toString(), "atenea/session-12")).thenReturn(true, false);
        when(gitRepositoryService.remoteBranchExists(repoPath.toString(), "atenea/session-12")).thenReturn(false, false);

        WorkSessionResponse response = workSessionService.closeSession(12L);

        assertEquals(WorkSessionStatus.CLOSED, response.status());
        assertEquals(AgentRunStatus.FAILED, staleRun.getStatus());
    }

    @Test
    void closeSessionRejectsCrossSessionPullRequestIdentityBeforeGitMutation() throws IOException {
        Path repoPath = createGitRepo(tempDir.resolve("repos/internal/atenea"));
        WorkSessionEntity session = buildSession(12L, 7L, repoPath, "main");
        session.setWorkspaceBranch("atenea/session-12");
        session.setPullRequestUrl("https://github.com/acme/atenea/pull/42");
        session.setPullRequestStatus(WorkSessionPullRequestStatus.OPEN);
        session.setFinalCommitSha("abc123");
        session.setPublishedAt(Instant.parse("2026-03-25T10:08:00Z"));

        when(workSessionRepository.findWithProjectById(12L)).thenReturn(Optional.of(session));
        when(agentRunRepository.existsBySessionIdAndStatus(12L, AgentRunStatus.RUNNING)).thenReturn(false);
        when(gitRepositoryService.getCurrentBranch(repoPath.toString())).thenReturn("atenea/session-12");
        when(gitRepositoryService.isWorkingTreeClean(repoPath.toString())).thenReturn(true);
        when(gitRepositoryService.branchExists(repoPath.toString(), "atenea/session-12")).thenReturn(true);
        when(gitRepositoryService.remoteBranchExists(repoPath.toString(), "atenea/session-12")).thenReturn(true);
        when(gitRepositoryService.getOriginRemoteUrl(repoPath.toString()))
                .thenReturn("git@github.com:acme/atenea.git");
        when(gitHubClient.resolveRepository("git@github.com:acme/atenea.git"))
                .thenReturn(new GitHubRepositoryRef("acme", "atenea"));
        when(gitHubClient.extractPullRequestNumber(session.getPullRequestUrl())).thenReturn(42L);
        when(gitHubClient.getPullRequest(new GitHubRepositoryRef("acme", "atenea"), 42L))
                .thenReturn(new GitHubPullRequest(
                        42L,
                        "https://github.com/acme/atenea/pull/42",
                        "closed",
                        true,
                        "acme/atenea",
                        "main",
                        "acme/atenea",
                        "atenea/session-99",
                        "def456"));

        WorkSessionCloseBlockedException exception = assertThrows(
                WorkSessionCloseBlockedException.class,
                () -> workSessionService.closeSession(12L));

        assertTrue(exception.getMessage().contains("pull request identity does not match"));
        assertEquals(WorkSessionStatus.CLOSING, session.getStatus());
        assertEquals("pull_request_identity_conflict", session.getCloseBlockedState());
        verify(gitRepositoryService, never()).checkoutBranch(any(), any());
        verify(gitRepositoryService, never()).deleteLocalBranch(any(), any());
        verify(gitRepositoryService, never()).deleteRemoteBranch(any(), any());
    }

    @Test
    void closeSessionThrowsWhenSessionDoesNotExist() {
        when(workSessionRepository.findWithProjectById(12L)).thenReturn(Optional.empty());

        assertThrows(WorkSessionNotFoundException.class, () -> workSessionService.closeSession(12L));
    }

    private WorkSessionEntity remoteSession(Path repoPath) {
        WorkSessionEntity session = buildSession(12L, 7L, repoPath, ProjectCodexIdentity.BRANCH);
        UUID remoteSessionId = UUID.fromString("11111111-1111-4111-8111-111111111111");
        session.getProject().setRepoPath(ProjectCodexIdentity.REPO_PATH);
        session.setWorkspaceBranch("atenea/session-" + remoteSessionId);
        session.setExecutionTarget(ExecutionTarget.REMOTE);
        session.setSelectedWorkerId(ProjectCodexIdentity.WORKER_ID);
        session.setRemoteSessionId(remoteSessionId);
        session.setRemoteWorkloadKind(ProjectCodexIdentity.WORKLOAD_KIND);
        session.setWorkspaceIdentity(
                "remote:" + ProjectCodexIdentity.WORKER_ID + ":work-session:" + remoteSessionId);
        session.setCanonicalSourceRef("refs/heads/" + ProjectCodexIdentity.BRANCH);
        session.setCanonicalSourceCommit("1".repeat(40));
        session.setCanonicalSourceObservationSha256("2".repeat(64));
        session.setCanonicalSourceObservedAt(Instant.parse("2026-08-03T09:00:00Z"));
        session.setRemoteCloseState(RemoteCloseState.NOT_STARTED);
        lenient().doReturn(repoPath.toString()).when(validator)
                .normalizeConfiguredRepoPath(ProjectCodexIdentity.REPO_PATH);
        return session;
    }

    private void prepareSuccessfulCloseMocks(WorkSessionEntity session, Path repoPath) {
        when(workSessionRepository.findWithProjectById(12L)).thenReturn(Optional.of(session));
        lenient().when(workSessionRepository.findLockedWithProjectById(12L))
                .thenReturn(Optional.of(session));
        lenient().when(workSessionRepository.saveAndFlush(any(WorkSessionEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(agentRunRepository.existsBySessionIdAndStatus(12L, AgentRunStatus.RUNNING))
                .thenReturn(false);
        when(agentRunRepository.existsBySessionIdAndStatusIn(
                12L, AgentRunStatus.nonTerminalStatuses())).thenReturn(false);
        when(gitRepositoryService.getCurrentBranch(repoPath.toString()))
                .thenReturn(session.getWorkspaceBranch(), "main", "main");
        when(gitRepositoryService.isWorkingTreeClean(repoPath.toString()))
                .thenReturn(true, true, true);
        when(gitRepositoryService.branchExists(repoPath.toString(), session.getWorkspaceBranch()))
                .thenReturn(true, false, false);
        when(gitRepositoryService.remoteBranchExists(
                repoPath.toString(), session.getWorkspaceBranch())).thenReturn(false, false);
        when(gitRepositoryService.branchContainsCommitsBeyond(
                repoPath.toString(), "main", session.getWorkspaceBranch())).thenReturn(false);
    }

    private RemoteWorkerClient.WorkspaceRelease releasedReceipt(WorkSessionEntity session) {
        String operationId = session.getRemoteCloseOperationId().toString();
        return new RemoteWorkerClient.WorkspaceRelease(
                "project-workspace-release-v1",
                "RELEASED",
                operationId,
                operationId,
                session.getRemoteSessionId().toString(),
                session.getWorkspaceIdentity(),
                ProjectCodexIdentity.PROJECT_IDENTITY,
                ProjectCodexIdentity.REPOSITORY,
                ProjectCodexIdentity.BRANCH,
                session.getCanonicalSourceCommit(),
                ProjectCodexIdentity.MANIFEST_SHA256,
                session.getWorkspaceBranch(),
                ProjectCodexIdentity.WORKER_ID,
                "3".repeat(64),
                6,
                java.util.Map.of(
                        "runtimeContainers", 0,
                        "runtimeNetworks", 0,
                        "sessionImages", 0,
                        "previewResources", 0,
                        "brokerResources", 0,
                        "browserProcesses", 0),
                java.util.Map.of(
                        "registration", true,
                        "normalAdmission", true,
                        "heavyAdmission", true,
                        "allocation", true),
                java.util.Map.of(
                        "workspaceRecord", true,
                        "worktree", true,
                        "git", true,
                        "turns", true,
                        "agentRuns", true,
                        "attachments", true,
                        "logs", true,
                        "artifacts", true,
                        "backups", true,
                        "policyVolumes", true),
                "4".repeat(64),
                "9".repeat(64),
                false);
    }

    private void markReleased(WorkSessionEntity session, UUID operationId) {
        Instant requestedAt = Instant.parse("2026-08-03T10:00:00Z");
        Instant releasedAt = Instant.parse("2026-08-03T10:01:00Z");
        session.setStatus(WorkSessionStatus.CLOSED);
        session.setClosedAt(releasedAt);
        session.setRemoteCloseState(RemoteCloseState.RELEASED);
        session.setRemoteCloseOperationId(operationId);
        session.setRemoteCloseRevision(6);
        session.setRemoteCloseReceiptSha256("9".repeat(64));
        session.setRemoteCloseRequestedAt(requestedAt);
        session.setRemoteCloseUpdatedAt(releasedAt);
        session.setRemoteCloseReleasedAt(releasedAt);
        session.setUpdatedAt(releasedAt);
    }

    private static ProjectEntity buildProject(Long projectId, Path repoPath) {
        ProjectEntity project = new ProjectEntity();
        project.setId(projectId);
        project.setName("Atenea");
        project.setDescription("Self-hosted Atenea");
        project.setRepoPath(repoPath.toString());
        project.setDefaultBaseBranch("main");
        project.setCreatedAt(Instant.parse("2026-03-25T10:00:00Z"));
        project.setUpdatedAt(Instant.parse("2026-03-25T10:01:00Z"));
        return project;
    }

    private static WorkSessionEntity buildSession(Long sessionId, Long projectId, Path repoPath, String baseBranch) {
        WorkSessionEntity session = new WorkSessionEntity();
        session.setId(sessionId);
        session.setProject(buildProject(projectId, repoPath));
        session.setStatus(WorkSessionStatus.OPEN);
        session.setTitle("Inspect project state");
        session.setBaseBranch(baseBranch);
        session.setWorkspaceBranch(null);
        session.setExternalThreadId(null);
        session.setPullRequestUrl(null);
        session.setPullRequestStatus(com.atenea.persistence.worksession.WorkSessionPullRequestStatus.NOT_CREATED);
        session.setFinalCommitSha(null);
        session.setOpenedAt(Instant.parse("2026-03-25T10:05:00Z"));
        session.setLastActivityAt(Instant.parse("2026-03-25T10:05:00Z"));
        session.setPublishedAt(null);
        session.setCloseBlockedState(null);
        session.setCloseBlockedReason(null);
        session.setCloseBlockedAction(null);
        session.setCloseRetryable(false);
        session.setClosedAt(null);
        session.setCreatedAt(Instant.parse("2026-03-25T10:05:00Z"));
        session.setUpdatedAt(Instant.parse("2026-03-25T10:05:00Z"));
        return session;
    }

    private static Path createGitRepo(Path repoPath) throws IOException {
        Files.createDirectories(repoPath.resolve(".git"));
        return repoPath;
    }

    private static AgentRunEntity buildRun(
            Long runId,
            WorkSessionEntity session,
            AgentRunStatus status,
            String outputSummary,
            String errorSummary
    ) {
        AgentRunEntity run = new AgentRunEntity();
        run.setId(runId);
        run.setSession(session);
        run.setStatus(status);
        run.setExternalTurnId("turn-" + runId);
        run.setStartedAt(Instant.parse("2026-03-25T10:06:00Z"));
        run.setFinishedAt(status == AgentRunStatus.RUNNING ? null : Instant.parse("2026-03-25T10:07:00Z"));
        run.setOutputSummary(outputSummary);
        run.setErrorSummary(errorSummary);
        run.setCreatedAt(Instant.parse(runId == 56L ? "2026-03-25T10:08:00Z" : "2026-03-25T10:06:00Z"));

        com.atenea.persistence.worksession.SessionTurnEntity originTurn =
                new com.atenea.persistence.worksession.SessionTurnEntity();
        originTurn.setId(runId + 100);
        run.setOriginTurn(originTurn);

        if (status == AgentRunStatus.SUCCEEDED) {
            com.atenea.persistence.worksession.SessionTurnEntity resultTurn =
                    new com.atenea.persistence.worksession.SessionTurnEntity();
            resultTurn.setId(runId + 200);
            run.setResultTurn(resultTurn);
        }
        return run;
    }
}
