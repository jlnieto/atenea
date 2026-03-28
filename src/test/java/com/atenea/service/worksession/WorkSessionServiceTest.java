package com.atenea.service.worksession;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

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
import com.atenea.persistence.worksession.AgentRunEntity;
import com.atenea.persistence.worksession.AgentRunRepository;
import com.atenea.persistence.worksession.AgentRunStatus;
import com.atenea.persistence.project.ProjectEntity;
import com.atenea.persistence.project.ProjectRepository;
import com.atenea.persistence.worksession.WorkSessionEntity;
import com.atenea.persistence.worksession.WorkSessionRepository;
import com.atenea.persistence.worksession.WorkSessionStatus;
import com.atenea.service.project.WorkspaceRepositoryPathValidator;
import com.atenea.service.taskexecution.GitRepositoryService;
import com.atenea.service.taskexecution.TaskLaunchBlockedException;
import com.atenea.codexappserver.CodexAppServerProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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

    @TempDir
    Path tempDir;

    private WorkSessionService workSessionService;

    @BeforeEach
    void setUp() throws IOException {
        Path workspaceRoot = Files.createDirectories(tempDir.resolve("repos"));
        WorkspaceRepositoryPathValidator validator = new WorkspaceRepositoryPathValidator(workspaceRoot.toString());
        lenient().when(codexAppServerProperties.getStaleTimeout()).thenReturn(Duration.ofMinutes(5));
        AgentRunReconciliationService reconciliationService = new AgentRunReconciliationService(
                agentRunRepository,
                codexAppServerProperties
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
                validator,
                gitRepositoryService,
                snapshotService,
                agentRunRepository,
                sessionTurnService,
                reconciliationService,
                new SessionBranchService(gitRepositoryService)
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

        ArgumentCaptor<WorkSessionEntity> captor = ArgumentCaptor.forClass(WorkSessionEntity.class);
        verify(workSessionRepository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        assertEquals("release/2026-q1", captor.getValue().getBaseBranch());
        assertEquals("atenea/session-12", captor.getValue().getWorkspaceBranch());
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
                .thenThrow(new TaskLaunchBlockedException("Git command failed: rev-parse"));

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
        verify(gitRepositoryService).createAndCheckoutBranch(repoPath.toString(), "main", "atenea/session-12");
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

        ResolveWorkSessionResponse response = workSessionService.resolveSession(
                7L,
                new ResolveWorkSessionRequest(" Create canonical session ", " release/2026 "));

        assertTrue(response.created());
        assertEquals(15L, response.session().id());
        assertEquals("Create canonical session", response.session().title());
        assertEquals("release/2026", response.session().baseBranch());
        assertEquals("atenea/session-15", response.session().workspaceBranch());
        assertEquals(WorkSessionOperationalState.IDLE, response.session().operationalState());
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
        when(agentRunRepository.findFirstBySessionIdAndStatusOrderByCreatedAtDesc(12L, AgentRunStatus.FAILED))
                .thenReturn(Optional.empty());
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
        when(agentRunRepository.findFirstBySessionIdAndStatusOrderByCreatedAtDesc(15L, AgentRunStatus.FAILED))
                .thenReturn(Optional.empty());
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
        when(agentRunRepository.findFirstBySessionIdAndStatusOrderByCreatedAtDesc(12L, AgentRunStatus.FAILED))
                .thenReturn(Optional.empty());
        when(agentRunRepository.findFirstBySessionIdAndStatusOrderByCreatedAtDesc(12L, AgentRunStatus.SUCCEEDED))
                .thenReturn(Optional.empty());
        when(sessionTurnService.getTurns(12L, null, 20)).thenReturn(List.of());
        when(sessionTurnService.getTurns(12L)).thenReturn(List.of());

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
        when(agentRunRepository.findFirstBySessionIdAndStatusOrderByCreatedAtDesc(12L, AgentRunStatus.FAILED))
                .thenReturn(Optional.empty());
        when(agentRunRepository.findFirstBySessionIdAndStatusOrderByCreatedAtDesc(12L, AgentRunStatus.SUCCEEDED))
                .thenReturn(Optional.empty());
        when(sessionTurnService.getTurns(12L, null, 20)).thenReturn(turns.subList(5, 25));
        when(sessionTurnService.getTurns(12L)).thenReturn(turns);

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
        when(agentRunRepository.findFirstBySessionIdAndStatusOrderByCreatedAtDesc(12L, AgentRunStatus.FAILED))
                .thenReturn(Optional.of(latestFailedRun));
        when(agentRunRepository.findFirstBySessionIdAndStatusOrderByCreatedAtDesc(12L, AgentRunStatus.SUCCEEDED))
                .thenReturn(Optional.empty());
        when(sessionTurnService.getTurns(12L, null, 20)).thenReturn(turns);
        when(sessionTurnService.getTurns(12L)).thenReturn(turns);

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
        when(agentRunRepository.findFirstBySessionIdAndStatusOrderByCreatedAtDesc(12L, AgentRunStatus.FAILED))
                .thenReturn(Optional.empty());
        when(agentRunRepository.findFirstBySessionIdAndStatusOrderByCreatedAtDesc(12L, AgentRunStatus.SUCCEEDED))
                .thenReturn(Optional.of(latestRun));
        when(sessionTurnService.getTurns(12L, null, 20)).thenReturn(turns);
        when(sessionTurnService.getTurns(12L)).thenReturn(turns);

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
        when(agentRunRepository.findFirstBySessionIdAndStatusOrderByCreatedAtDesc(12L, AgentRunStatus.FAILED))
                .thenReturn(Optional.empty());
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
        when(agentRunRepository.findFirstBySessionIdAndStatusOrderByCreatedAtDesc(12L, AgentRunStatus.FAILED))
                .thenReturn(Optional.empty());
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
        when(agentRunRepository.findFirstBySessionIdAndStatusOrderByCreatedAtDesc(12L, AgentRunStatus.FAILED))
                .thenReturn(Optional.of(staleRun));
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
        when(agentRunRepository.findFirstBySessionIdAndStatusOrderByCreatedAtDesc(12L, AgentRunStatus.FAILED))
                .thenReturn(Optional.of(latestFailedRun));
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

        when(workSessionRepository.findWithProjectById(12L)).thenReturn(Optional.of(session));
        when(agentRunRepository.existsBySessionIdAndStatus(12L, AgentRunStatus.RUNNING)).thenReturn(false);
        when(gitRepositoryService.getCurrentBranch(repoPath.toString())).thenReturn("main");
        when(gitRepositoryService.isWorkingTreeClean(repoPath.toString())).thenReturn(true);

        WorkSessionResponse response = workSessionService.closeSession(12L);

        assertEquals(WorkSessionStatus.CLOSED, response.status());
        assertEquals(WorkSessionOperationalState.CLOSED, response.operationalState());
        assertEquals(WorkSessionStatus.CLOSED, session.getStatus());
        assertEquals(response.closedAt(), session.getClosedAt());
        assertEquals(response.closedAt(), session.getUpdatedAt());
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

        assertThrows(AgentRunAlreadyRunningException.class, () -> workSessionService.closeSession(12L));
    }

    @Test
    void closeSessionAllowsClosingWhenOnlyStaleRunningRunExists() throws IOException {
        Path repoPath = createGitRepo(tempDir.resolve("repos/internal/atenea"));
        WorkSessionEntity session = buildSession(12L, 7L, repoPath, "main");
        AgentRunEntity staleRun = buildRun(55L, session, AgentRunStatus.RUNNING, null, null);
        staleRun.setStartedAt(Instant.now().minus(Duration.ofMinutes(7)));

        when(workSessionRepository.findWithProjectById(12L)).thenReturn(Optional.of(session));
        when(agentRunRepository.findBySessionIdAndStatusOrderByCreatedAtAsc(12L, AgentRunStatus.RUNNING))
                .thenReturn(List.of(staleRun));
        when(agentRunRepository.saveAndFlush(any(AgentRunEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(agentRunRepository.existsBySessionIdAndStatus(12L, AgentRunStatus.RUNNING)).thenReturn(false);
        when(gitRepositoryService.getCurrentBranch(repoPath.toString())).thenReturn("main");
        when(gitRepositoryService.isWorkingTreeClean(repoPath.toString())).thenReturn(true);

        WorkSessionResponse response = workSessionService.closeSession(12L);

        assertEquals(WorkSessionStatus.CLOSED, response.status());
        assertEquals(AgentRunStatus.FAILED, staleRun.getStatus());
    }

    @Test
    void closeSessionThrowsWhenSessionDoesNotExist() {
        when(workSessionRepository.findWithProjectById(12L)).thenReturn(Optional.empty());

        assertThrows(WorkSessionNotFoundException.class, () -> workSessionService.closeSession(12L));
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
        session.setOpenedAt(Instant.parse("2026-03-25T10:05:00Z"));
        session.setLastActivityAt(Instant.parse("2026-03-25T10:05:00Z"));
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
