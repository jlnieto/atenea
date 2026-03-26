package com.atenea.service.worksession;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.atenea.api.worksession.CreateWorkSessionRequest;
import com.atenea.api.worksession.SessionOperationalSnapshotResponse;
import com.atenea.api.worksession.WorkSessionResponse;
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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
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

    @TempDir
    Path tempDir;

    private WorkSessionService workSessionService;

    @BeforeEach
    void setUp() throws IOException {
        Path workspaceRoot = Files.createDirectories(tempDir.resolve("repos"));
        WorkspaceRepositoryPathValidator validator = new WorkspaceRepositoryPathValidator(workspaceRoot.toString());
        SessionOperationalSnapshotService snapshotService = new SessionOperationalSnapshotService(
                validator,
                gitRepositoryService,
                agentRunRepository
        );
        workSessionService = new WorkSessionService(
                projectRepository,
                workSessionRepository,
                validator,
                gitRepositoryService,
                snapshotService,
                agentRunRepository
        );
    }

    @Test
    void openSessionUsesRequestBaseBranchWhenProvided() throws IOException {
        Path repoPath = createGitRepo(tempDir.resolve("repos/internal/atenea"));
        ProjectEntity project = buildProject(7L, repoPath);

        when(projectRepository.findById(7L)).thenReturn(Optional.of(project));
        when(workSessionRepository.existsByProjectIdAndStatus(7L, WorkSessionStatus.OPEN)).thenReturn(false);
        when(gitRepositoryService.getCurrentBranch(repoPath.toString())).thenReturn("develop");
        when(gitRepositoryService.isWorkingTreeClean(repoPath.toString())).thenReturn(true);
        when(agentRunRepository.existsBySessionIdAndStatus(12L, AgentRunStatus.RUNNING)).thenReturn(false);
        when(workSessionRepository.save(any(WorkSessionEntity.class))).thenAnswer(invocation -> {
            WorkSessionEntity entity = invocation.getArgument(0);
            entity.setId(12L);
            return entity;
        });

        WorkSessionResponse response = workSessionService.openSession(
                7L,
                new CreateWorkSessionRequest("  Review current status  ", " release/2026-q1 "));

        assertEquals(12L, response.id());
        assertEquals(7L, response.projectId());
        assertEquals(WorkSessionStatus.OPEN, response.status());
        assertEquals("Review current status", response.title());
        assertEquals("release/2026-q1", response.baseBranch());
        assertNull(response.workspaceBranch());
        assertNull(response.externalThreadId());
        assertNull(response.closedAt());
        assertEquals(response.openedAt(), response.lastActivityAt());
        assertEquals(new SessionOperationalSnapshotResponse(true, true, "develop", false), response.repoState());

        ArgumentCaptor<WorkSessionEntity> captor = ArgumentCaptor.forClass(WorkSessionEntity.class);
        verify(workSessionRepository).save(captor.capture());
        assertEquals("release/2026-q1", captor.getValue().getBaseBranch());
    }

    @Test
    void openSessionDefaultsBaseBranchToCurrentRepoBranch() throws IOException {
        Path repoPath = createGitRepo(tempDir.resolve("repos/internal/atenea"));
        ProjectEntity project = buildProject(7L, repoPath);

        when(projectRepository.findById(7L)).thenReturn(Optional.of(project));
        when(workSessionRepository.existsByProjectIdAndStatus(7L, WorkSessionStatus.OPEN)).thenReturn(false);
        when(gitRepositoryService.getCurrentBranch(repoPath.toString())).thenReturn("feature/docs");
        when(gitRepositoryService.isWorkingTreeClean(repoPath.toString())).thenReturn(true);
        when(agentRunRepository.existsBySessionIdAndStatus(12L, AgentRunStatus.RUNNING)).thenReturn(false);
        when(workSessionRepository.save(any(WorkSessionEntity.class))).thenAnswer(invocation -> {
            WorkSessionEntity entity = invocation.getArgument(0);
            entity.setId(12L);
            return entity;
        });

        WorkSessionResponse response = workSessionService.openSession(
                7L,
                new CreateWorkSessionRequest("Inspect project state", "   "));

        assertEquals("feature/docs", response.baseBranch());
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

        assertEquals(new SessionOperationalSnapshotResponse(false, false, null, true), response.repoState());
    }

    @Test
    void getSessionThrowsWhenNotFound() {
        when(workSessionRepository.findWithProjectById(12L)).thenReturn(Optional.empty());

        assertThrows(WorkSessionNotFoundException.class, () -> workSessionService.getSession(12L));
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
}
