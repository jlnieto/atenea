package com.atenea.service.worksession;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import com.atenea.api.worksession.AgentRunResponse;
import com.atenea.api.worksession.CreateSessionTurnRequest;
import com.atenea.api.worksession.CreateSessionTurnResponse;
import com.atenea.codexappserver.CodexAppServerClient.CodexAppServerExecutionHandle;
import com.atenea.codexappserver.CodexAppServerExecutionResult;
import com.atenea.persistence.project.ProjectEntity;
import com.atenea.persistence.worksession.AgentRunEntity;
import com.atenea.persistence.worksession.AgentRunRepository;
import com.atenea.persistence.worksession.AgentRunStatus;
import com.atenea.persistence.worksession.SessionTurnActor;
import com.atenea.persistence.worksession.SessionTurnEntity;
import com.atenea.persistence.worksession.SessionTurnRepository;
import com.atenea.persistence.worksession.WorkSessionEntity;
import com.atenea.persistence.worksession.WorkSessionRepository;
import com.atenea.persistence.worksession.WorkSessionStatus;
import com.atenea.service.project.WorkspaceRepositoryPathValidator;
import com.atenea.service.git.GitRepositoryService;
import com.atenea.service.git.GitRepositoryOperationException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SessionTurnCreateServiceTest {

    @Mock
    private WorkSessionRepository workSessionRepository;

    @Mock
    private SessionTurnRepository sessionTurnRepository;

    @Mock
    private GitRepositoryService gitRepositoryService;

    @Mock
    private AgentRunRepository agentRunRepository;

    @Mock
    private AgentRunService agentRunService;

    @Mock
    private AgentRunReconciliationService agentRunReconciliationService;

    @Mock
    private SessionCodexOrchestrator sessionCodexOrchestrator;

    @Mock
    private SessionTurnCompletionService sessionTurnCompletionService;

    @TempDir
    Path tempDir;

    private SessionTurnService sessionTurnService;

    @BeforeEach
    void setUp() throws IOException {
        Path workspaceRoot = Files.createDirectories(tempDir.resolve("repos"));
        sessionTurnService = new SessionTurnService(
                workSessionRepository,
                sessionTurnRepository,
                new WorkspaceRepositoryPathValidator(workspaceRoot.toString()),
                gitRepositoryService,
                agentRunRepository,
                agentRunService,
                new AgentRunProgressService(),
                agentRunReconciliationService,
                sessionCodexOrchestrator,
                sessionTurnCompletionService
        );
    }

    @Test
    void createTurnFirstTurnCreatesThreadAndPersistsConversation() throws Exception {
        Path repoPath = createRepoPath("internal/atenea");
        WorkSessionEntity session = buildSession(12L, 7L, repoPath.toString(), WorkSessionStatus.OPEN, null);
        AtomicLong turnIds = new AtomicLong(100L);

        when(workSessionRepository.findWithProjectById(12L)).thenReturn(Optional.of(session));
        when(agentRunRepository.existsBySessionIdAndStatus(12L, AgentRunStatus.RUNNING)).thenReturn(false);
        when(gitRepositoryService.getCurrentBranch(repoPath.toString())).thenReturn("main");
        when(sessionTurnRepository.save(any(SessionTurnEntity.class))).thenAnswer(invocation -> {
            SessionTurnEntity turn = invocation.getArgument(0);
            turn.setId(turnIds.incrementAndGet());
            return turn;
        });
        when(agentRunService.createRunningRun(eq(session), any(SessionTurnEntity.class))).thenAnswer(invocation -> {
            SessionTurnEntity originTurn = invocation.getArgument(1);
            AgentRunEntity run = new AgentRunEntity();
            run.setId(55L);
            run.setSession(session);
            run.setOriginTurn(originTurn);
            run.setStatus(AgentRunStatus.RUNNING);
            run.setTargetRepoPath(repoPath.toString());
            run.setStartedAt(Instant.parse("2026-03-25T10:05:01Z"));
            run.setCreatedAt(Instant.parse("2026-03-25T10:05:01Z"));
            return run;
        });
        when(sessionCodexOrchestrator.startTurn(eq(repoPath.toString()), eq("Inspect the project"), eq(null), any()))
                .thenReturn(handle("thread-1", "turn-1"));
        when(agentRunService.toResponse(any(AgentRunEntity.class))).thenAnswer(invocation -> {
            AgentRunEntity run = invocation.getArgument(0);
            return new AgentRunResponse(
                    run.getId(),
                    session.getId(),
                    run.getOriginTurn().getId(),
                    run.getResultTurn() == null ? null : run.getResultTurn().getId(),
                    run.getStatus(),
                    run.getTargetRepoPath(),
                    run.getExternalTurnId(),
                    run.getStartedAt(),
                    run.getFinishedAt(),
                    run.getOutputSummary(),
                    run.getErrorSummary(),
                    run.getCreatedAt()
            );
        });

        CreateSessionTurnResponse response = sessionTurnService.createTurn(
                12L,
                new CreateSessionTurnRequest("Inspect the project"));

        assertEquals("thread-1", session.getExternalThreadId());
        assertEquals("Inspect the project", response.operatorTurn().messageText());
        assertEquals("turn-1", response.run().externalTurnId());
        assertEquals(AgentRunStatus.RUNNING, response.run().status());
        assertNull(response.codexTurn());
        assertNotNull(session.getLastActivityAt());

        ArgumentCaptor<SessionTurnEntity> originTurnCaptor = ArgumentCaptor.forClass(SessionTurnEntity.class);
        verify(agentRunService).createRunningRun(eq(session), originTurnCaptor.capture());
        assertEquals(SessionTurnActor.OPERATOR, originTurnCaptor.getValue().getActor());
        assertEquals("Inspect the project", originTurnCaptor.getValue().getMessageText());
        assertEquals(false, originTurnCaptor.getValue().isInternal());
    }

    @Test
    void createTurnSecondTurnReusesExistingThread() throws Exception {
        Path repoPath = createRepoPath("internal/atenea");
        WorkSessionEntity session = buildSession(12L, 7L, repoPath.toString(), WorkSessionStatus.OPEN, "thread-existing");
        AtomicLong turnIds = new AtomicLong(200L);

        when(workSessionRepository.findWithProjectById(12L)).thenReturn(Optional.of(session));
        when(agentRunRepository.existsBySessionIdAndStatus(12L, AgentRunStatus.RUNNING)).thenReturn(false);
        when(gitRepositoryService.getCurrentBranch(repoPath.toString())).thenReturn("main");
        when(sessionTurnRepository.save(any(SessionTurnEntity.class))).thenAnswer(invocation -> {
            SessionTurnEntity turn = invocation.getArgument(0);
            turn.setId(turnIds.incrementAndGet());
            return turn;
        });
        when(agentRunService.createRunningRun(eq(session), any(SessionTurnEntity.class))).thenAnswer(invocation -> {
            SessionTurnEntity originTurn = invocation.getArgument(1);
            AgentRunEntity run = new AgentRunEntity();
            run.setId(56L);
            run.setSession(session);
            run.setOriginTurn(originTurn);
            run.setStatus(AgentRunStatus.RUNNING);
            run.setTargetRepoPath(repoPath.toString());
            run.setStartedAt(Instant.parse("2026-03-25T10:06:01Z"));
            run.setCreatedAt(Instant.parse("2026-03-25T10:06:01Z"));
            return run;
        });
        when(sessionCodexOrchestrator.startTurn(
                eq(repoPath.toString()),
                eq("Continue with implementation"),
                eq("thread-existing"),
                any()))
                .thenReturn(handle("thread-existing", "turn-2"));
        when(agentRunService.toResponse(any(AgentRunEntity.class))).thenAnswer(invocation -> {
            AgentRunEntity run = invocation.getArgument(0);
            return new AgentRunResponse(
                    run.getId(),
                    session.getId(),
                    run.getOriginTurn().getId(),
                    run.getResultTurn() == null ? null : run.getResultTurn().getId(),
                    run.getStatus(),
                    run.getTargetRepoPath(),
                    run.getExternalTurnId(),
                    run.getStartedAt(),
                    run.getFinishedAt(),
                    run.getOutputSummary(),
                    run.getErrorSummary(),
                    run.getCreatedAt()
            );
        });

        CreateSessionTurnResponse response = sessionTurnService.createTurn(
                12L,
                new CreateSessionTurnRequest("Continue with implementation"));

        assertEquals("thread-existing", session.getExternalThreadId());
        assertEquals("turn-2", response.run().externalTurnId());
        assertNull(response.codexTurn());
        verify(sessionCodexOrchestrator).startTurn(
                eq(repoPath.toString()),
                eq("Continue with implementation"),
                eq("thread-existing"),
                any());
    }

    @Test
    void createTurnMaintainsThreadContinuityAcrossTwoSequentialTurns() throws Exception {
        Path repoPath = createRepoPath("internal/atenea");
        WorkSessionEntity session = buildSession(12L, 7L, repoPath.toString(), WorkSessionStatus.OPEN, null);
        AtomicLong turnIds = new AtomicLong(300L);
        AtomicLong runIds = new AtomicLong(60L);

        when(workSessionRepository.findWithProjectById(12L)).thenReturn(Optional.of(session));
        when(agentRunRepository.existsBySessionIdAndStatus(12L, AgentRunStatus.RUNNING)).thenReturn(false);
        when(gitRepositoryService.getCurrentBranch(repoPath.toString())).thenReturn("main");
        when(sessionTurnRepository.save(any(SessionTurnEntity.class))).thenAnswer(invocation -> {
            SessionTurnEntity turn = invocation.getArgument(0);
            turn.setId(turnIds.incrementAndGet());
            return turn;
        });
        when(agentRunService.createRunningRun(eq(session), any(SessionTurnEntity.class))).thenAnswer(invocation -> {
            SessionTurnEntity originTurn = invocation.getArgument(1);
            AgentRunEntity run = new AgentRunEntity();
            run.setId(runIds.incrementAndGet());
            run.setSession(session);
            run.setOriginTurn(originTurn);
            run.setStatus(AgentRunStatus.RUNNING);
            run.setTargetRepoPath(repoPath.toString());
            run.setStartedAt(Instant.parse("2026-03-25T10:08:01Z"));
            run.setCreatedAt(Instant.parse("2026-03-25T10:08:01Z"));
            return run;
        });
        when(sessionCodexOrchestrator.startTurn(eq(repoPath.toString()), eq("First turn"), eq(null), any()))
                .thenReturn(handle("thread-stable", "turn-a"));
        when(sessionCodexOrchestrator.startTurn(eq(repoPath.toString()), eq("Second turn"), eq("thread-stable"), any()))
                .thenReturn(handle("thread-stable", "turn-b"));
        when(agentRunService.toResponse(any(AgentRunEntity.class))).thenAnswer(invocation -> {
            AgentRunEntity run = invocation.getArgument(0);
            return new AgentRunResponse(
                    run.getId(),
                    session.getId(),
                    run.getOriginTurn().getId(),
                    run.getResultTurn() == null ? null : run.getResultTurn().getId(),
                    run.getStatus(),
                    run.getTargetRepoPath(),
                    run.getExternalTurnId(),
                    run.getStartedAt(),
                    run.getFinishedAt(),
                    run.getOutputSummary(),
                    run.getErrorSummary(),
                    run.getCreatedAt()
            );
        });

        CreateSessionTurnResponse first = sessionTurnService.createTurn(12L, new CreateSessionTurnRequest("First turn"));
        String persistedThreadIdAfterFirstTurn = session.getExternalThreadId();
        CreateSessionTurnResponse second = sessionTurnService.createTurn(12L, new CreateSessionTurnRequest("Second turn"));

        assertEquals("thread-stable", persistedThreadIdAfterFirstTurn);
        assertEquals("thread-stable", session.getExternalThreadId());
        assertEquals("turn-a", first.run().externalTurnId());
        assertEquals("turn-b", second.run().externalTurnId());
    }

    @Test
    void createTurnFailsWhenSessionIsClosed() {
        WorkSessionEntity session = buildSession(12L, 7L, "/workspace/repos/internal/atenea", WorkSessionStatus.CLOSED, null);
        when(workSessionRepository.findWithProjectById(12L)).thenReturn(Optional.of(session));

        assertThrows(
                WorkSessionNotOpenException.class,
                () -> sessionTurnService.createTurn(12L, new CreateSessionTurnRequest("Inspect the project")));
    }

    @Test
    void createTurnFailsWhenSessionIsAlreadyRunning() throws Exception {
        Path repoPath = createRepoPath("internal/atenea");
        WorkSessionEntity session = buildSession(12L, 7L, repoPath.toString(), WorkSessionStatus.OPEN, null);

        when(workSessionRepository.findWithProjectById(12L)).thenReturn(Optional.of(session));
        when(agentRunRepository.existsBySessionIdAndStatus(12L, AgentRunStatus.RUNNING)).thenReturn(true);

        WorkSessionAlreadyRunningException exception = assertThrows(
                WorkSessionAlreadyRunningException.class,
                () -> sessionTurnService.createTurn(12L, new CreateSessionTurnRequest("Inspect the project")));

        assertEquals(
                "WorkSession with id '12' is already RUNNING and does not accept a new executable turn",
                exception.getMessage());
        verify(sessionTurnRepository, never()).save(any(SessionTurnEntity.class));
        verify(agentRunService, never()).createRunningRun(any(WorkSessionEntity.class), any(SessionTurnEntity.class));
    }

    @Test
    void createTurnMarksRunFailedWhenCodexFails() throws Exception {
        Path repoPath = createRepoPath("internal/atenea");
        WorkSessionEntity session = buildSession(12L, 7L, repoPath.toString(), WorkSessionStatus.OPEN, null);
        AtomicLong turnIds = new AtomicLong(100L);

        when(workSessionRepository.findWithProjectById(12L)).thenReturn(Optional.of(session));
        when(agentRunRepository.existsBySessionIdAndStatus(12L, AgentRunStatus.RUNNING)).thenReturn(false);
        when(gitRepositoryService.getCurrentBranch(repoPath.toString())).thenReturn("main");
        when(sessionTurnRepository.save(any(SessionTurnEntity.class))).thenAnswer(invocation -> {
            SessionTurnEntity turn = invocation.getArgument(0);
            turn.setId(turnIds.incrementAndGet());
            return turn;
        });
        when(agentRunService.createRunningRun(eq(session), any(SessionTurnEntity.class))).thenAnswer(invocation -> {
            SessionTurnEntity originTurn = invocation.getArgument(1);
            AgentRunEntity run = new AgentRunEntity();
            run.setId(57L);
            run.setSession(session);
            run.setOriginTurn(originTurn);
            run.setStatus(AgentRunStatus.RUNNING);
            run.setTargetRepoPath(repoPath.toString());
            run.setStartedAt(Instant.parse("2026-03-25T10:07:01Z"));
            run.setCreatedAt(Instant.parse("2026-03-25T10:07:01Z"));
            return run;
        });
        when(sessionCodexOrchestrator.startTurn(eq(repoPath.toString()), eq("Inspect the project"), eq(null), any()))
                .thenThrow(new RuntimeException("Timed out waiting for Codex App Server completion"));
        when(agentRunService.markFailed(eq(57L), eq((String) null), eq("Timed out waiting for Codex App Server completion")))
                .thenAnswer(invocation -> {
                    AgentRunEntity run = new AgentRunEntity();
                    run.setId(57L);
                    run.setSession(session);
                    run.setStatus(AgentRunStatus.FAILED);
                    run.setTargetRepoPath(repoPath.toString());
                    run.setErrorSummary("Timed out waiting for Codex App Server completion");
                    run.setCreatedAt(Instant.parse("2026-03-25T10:07:01Z"));
                    return run;
                });

        WorkSessionTurnExecutionFailedException exception = assertThrows(
                WorkSessionTurnExecutionFailedException.class,
                () -> sessionTurnService.createTurn(12L, new CreateSessionTurnRequest("Inspect the project")));

        assertEquals("Codex execution failed for WorkSession turn", exception.getMessage());
        verify(agentRunService).markFailed(57L, null, "Timed out waiting for Codex App Server completion");
        verify(agentRunService, org.mockito.Mockito.never()).markSucceeded(any(), any(), any(), any());
        assertNull(session.getExternalThreadId());
    }

    @Test
    void createTurnFailsWhenRepoIsNotOperational() throws Exception {
        Path repoPath = createRepoPath("internal/atenea");
        WorkSessionEntity session = buildSession(12L, 7L, repoPath.toString(), WorkSessionStatus.OPEN, null);
        when(workSessionRepository.findWithProjectById(12L)).thenReturn(Optional.of(session));
        when(agentRunRepository.existsBySessionIdAndStatus(12L, AgentRunStatus.RUNNING)).thenReturn(false);
        when(gitRepositoryService.getCurrentBranch(repoPath.toString()))
                .thenThrow(new GitRepositoryOperationException("Git command failed: rev-parse"));

        assertThrows(
                WorkSessionOperationBlockedException.class,
                () -> sessionTurnService.createTurn(12L, new CreateSessionTurnRequest("Inspect the project")));
    }

    private Path createRepoPath(String relativePath) throws IOException {
        Path repoPath = Files.createDirectories(tempDir.resolve("repos").resolve(relativePath));
        Files.createDirectories(repoPath.resolve(".git"));
        return repoPath;
    }

    private static WorkSessionEntity buildSession(
            Long sessionId,
            Long projectId,
            String repoPath,
            WorkSessionStatus status,
            String externalThreadId
    ) {
        ProjectEntity project = new ProjectEntity();
        project.setId(projectId);
        project.setName("Atenea");
        project.setRepoPath(repoPath);
        project.setCreatedAt(Instant.parse("2026-03-25T10:00:00Z"));
        project.setUpdatedAt(Instant.parse("2026-03-25T10:00:00Z"));

        WorkSessionEntity session = new WorkSessionEntity();
        session.setId(sessionId);
        session.setProject(project);
        session.setStatus(status);
        session.setTitle("Inspect project status");
        session.setBaseBranch("main");
        session.setExternalThreadId(externalThreadId);
        session.setOpenedAt(Instant.parse("2026-03-25T10:05:00Z"));
        session.setLastActivityAt(Instant.parse("2026-03-25T10:05:00Z"));
        session.setCreatedAt(Instant.parse("2026-03-25T10:05:00Z"));
        session.setUpdatedAt(Instant.parse("2026-03-25T10:05:00Z"));
        return session;
    }

    private static SessionTurnEntity buildTurn(Long id, WorkSessionEntity session, SessionTurnActor actor, String text) {
        SessionTurnEntity turn = new SessionTurnEntity();
        turn.setId(id);
        turn.setSession(session);
        turn.setActor(actor);
        turn.setMessageText(text);
        turn.setInternal(false);
        turn.setCreatedAt(Instant.parse("2026-03-25T10:05:01Z"));
        return turn;
    }

    private static CodexAppServerExecutionHandle handle(String threadId, String turnId) {
        return new CodexAppServerExecutionHandle(
                threadId,
                turnId,
                CompletableFuture.completedFuture(new CodexAppServerExecutionResult(
                        threadId,
                        turnId,
                        CodexAppServerExecutionResult.Status.COMPLETED,
                        "Completed later",
                        "Completed later",
                        "commentary",
                        null)));
    }
}
