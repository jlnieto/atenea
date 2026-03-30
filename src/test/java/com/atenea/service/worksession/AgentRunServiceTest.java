package com.atenea.service.worksession;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import com.atenea.mobilepush.MobilePushDispatchService;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AgentRunServiceTest {

    @Mock
    private WorkSessionRepository workSessionRepository;

    @Mock
    private AgentRunRepository agentRunRepository;

    @Mock
    private SessionTurnRepository sessionTurnRepository;

    @Mock
    private com.atenea.codexappserver.CodexAppServerProperties codexAppServerProperties;

    @Mock
    private MobilePushDispatchService mobilePushDispatchService;

    private AgentRunService agentRunService;
    private AgentRunReconciliationService agentRunReconciliationService;

    @BeforeEach
    void setUp() {
        agentRunReconciliationService = new AgentRunReconciliationService(agentRunRepository, codexAppServerProperties);
        agentRunService = new AgentRunService(
                workSessionRepository,
                agentRunRepository,
                sessionTurnRepository,
                new AgentRunProgressService(),
                mobilePushDispatchService
        );
    }

    @Test
    void createRunningRunCreatesRunForExistingSession() {
        WorkSessionEntity session = buildSession(12L, 7L, "/workspace/repos/internal/atenea");

        when(workSessionRepository.findWithProjectById(12L)).thenReturn(Optional.of(session));
        when(agentRunRepository.existsBySessionIdAndStatus(12L, AgentRunStatus.RUNNING)).thenReturn(false);
        when(sessionTurnRepository.save(any(SessionTurnEntity.class))).thenAnswer(invocation -> {
            SessionTurnEntity turn = invocation.getArgument(0);
            turn.setId(101L);
            return turn;
        });
        when(agentRunRepository.save(any(AgentRunEntity.class))).thenAnswer(invocation -> {
            AgentRunEntity run = invocation.getArgument(0);
            run.setId(55L);
            return run;
        });

        AgentRunEntity run = agentRunService.createRunningRun(12L);

        assertEquals(55L, run.getId());
        assertEquals(AgentRunStatus.RUNNING, run.getStatus());
        assertEquals("/workspace/repos/internal/atenea", run.getTargetRepoPath());
        assertNull(run.getExternalTurnId());
        assertNotNull(run.getStartedAt());
        assertNull(run.getFinishedAt());
        assertEquals(101L, run.getOriginTurn().getId());

        ArgumentCaptor<SessionTurnEntity> turnCaptor = ArgumentCaptor.forClass(SessionTurnEntity.class);
        verify(sessionTurnRepository).save(turnCaptor.capture());
        assertEquals(SessionTurnActor.ATENEA, turnCaptor.getValue().getActor());
        assertEquals("Internal AgentRun origin", turnCaptor.getValue().getMessageText());
        assertEquals(true, turnCaptor.getValue().isInternal());
    }

    @Test
    void markSucceededTransitionsRunningRunAndStoresExternalTurnId() {
        AgentRunEntity run = buildRun(55L, AgentRunStatus.RUNNING);

        when(agentRunRepository.findWithSessionById(55L)).thenReturn(Optional.of(run));
        when(agentRunRepository.save(any(AgentRunEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AgentRunEntity updated = agentRunService.markSucceeded(55L, " turn_123 ", "Completed successfully");

        assertEquals(AgentRunStatus.SUCCEEDED, updated.getStatus());
        assertEquals("turn_123", updated.getExternalTurnId());
        assertEquals("Completed successfully", updated.getOutputSummary());
        assertNull(updated.getErrorSummary());
        assertNotNull(updated.getFinishedAt());
    }

    @Test
    void markFailedTransitionsRunningRunAndStoresErrorSummary() {
        AgentRunEntity run = buildRun(55L, AgentRunStatus.RUNNING);

        when(agentRunRepository.findWithSessionById(55L)).thenReturn(Optional.of(run));
        when(agentRunRepository.save(any(AgentRunEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AgentRunEntity updated = agentRunService.markFailed(55L, "turn_456", "Codex execution failed");

        assertEquals(AgentRunStatus.FAILED, updated.getStatus());
        assertEquals("turn_456", updated.getExternalTurnId());
        assertEquals("Codex execution failed", updated.getErrorSummary());
        assertNull(updated.getOutputSummary());
        assertNotNull(updated.getFinishedAt());
    }

    @Test
    void forceMarkFailedIfRunningUsesConditionalRepositoryUpdate() {
        when(agentRunRepository.forceMarkFailedIfRunning(eq(55L), eq("turn_456"), eq("Codex execution failed"), any()))
                .thenReturn(1);

        boolean updated = agentRunService.forceMarkFailedIfRunning(55L, " turn_456 ", " Codex execution failed ");

        assertTrue(updated);
    }

    @Test
    void createRunningRunFailsWhenSessionAlreadyHasRunningRun() {
        WorkSessionEntity session = buildSession(12L, 7L, "/workspace/repos/internal/atenea");

        when(workSessionRepository.findWithProjectById(12L)).thenReturn(Optional.of(session));
        when(agentRunRepository.existsBySessionIdAndStatus(12L, AgentRunStatus.RUNNING)).thenReturn(true);

        assertThrows(AgentRunAlreadyRunningException.class, () -> agentRunService.createRunningRun(12L));
    }

    @Test
    void reconcileSessionMarksRunningRunFailedWhenItExceededTimeoutWindow() {
        AgentRunEntity run = buildRun(55L, AgentRunStatus.RUNNING);
        run.setStartedAt(Instant.now().minus(Duration.ofMinutes(7)));

        when(codexAppServerProperties.getStaleTimeout()).thenReturn(Duration.ofMinutes(5));
        when(agentRunRepository.findBySessionIdAndStatusOrderByCreatedAtAsc(12L, AgentRunStatus.RUNNING))
                .thenReturn(java.util.List.of(run));
        when(agentRunRepository.saveAndFlush(any(AgentRunEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        boolean reconciled = agentRunReconciliationService.reconcileSession(12L);

        assertEquals(true, reconciled);
        assertEquals(AgentRunStatus.FAILED, run.getStatus());
        assertEquals(
                "Marked FAILED during reconciliation because the run stayed RUNNING past the stale timeout window",
                run.getErrorSummary());
        assertNotNull(run.getFinishedAt());
        assertNull(run.getOutputSummary());
    }

    @Test
    void reconcileSessionKeepsRecentRunningRunUntouched() {
        AgentRunEntity run = buildRun(55L, AgentRunStatus.RUNNING);
        run.setStartedAt(Instant.now().minus(Duration.ofMinutes(2)));

        when(codexAppServerProperties.getStaleTimeout()).thenReturn(Duration.ofMinutes(5));
        when(agentRunRepository.findBySessionIdAndStatusOrderByCreatedAtAsc(12L, AgentRunStatus.RUNNING))
                .thenReturn(java.util.List.of(run));

        boolean reconciled = agentRunReconciliationService.reconcileSession(12L);

        assertEquals(false, reconciled);
        assertEquals(AgentRunStatus.RUNNING, run.getStatus());
        assertNull(run.getFinishedAt());
        assertNull(run.getErrorSummary());
    }

    @Test
    void reconcileRunningRunsAfterStartupMarksAllPersistedRunningRunsFailed() {
        AgentRunEntity firstRun = buildRun(55L, AgentRunStatus.RUNNING);
        AgentRunEntity secondRun = buildRun(56L, AgentRunStatus.RUNNING);

        when(agentRunRepository.findByStatusOrderByCreatedAtAsc(AgentRunStatus.RUNNING))
                .thenReturn(java.util.List.of(firstRun, secondRun));
        when(agentRunRepository.saveAndFlush(any(AgentRunEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        int reconciledCount = agentRunReconciliationService.reconcileRunningRunsAfterStartup();

        assertEquals(2, reconciledCount);
        assertEquals(AgentRunStatus.FAILED, firstRun.getStatus());
        assertEquals(AgentRunStatus.FAILED, secondRun.getStatus());
        assertEquals(
                "Marked FAILED during startup reconciliation because Atenea restarted while the run was still RUNNING",
                firstRun.getErrorSummary());
        assertNotNull(firstRun.getFinishedAt());
        assertNull(firstRun.getOutputSummary());
    }

    private static WorkSessionEntity buildSession(Long sessionId, Long projectId, String repoPath) {
        ProjectEntity project = new ProjectEntity();
        project.setId(projectId);
        project.setName("Atenea");
        project.setRepoPath(repoPath);
        project.setCreatedAt(Instant.parse("2026-03-25T10:00:00Z"));
        project.setUpdatedAt(Instant.parse("2026-03-25T10:00:00Z"));

        WorkSessionEntity session = new WorkSessionEntity();
        session.setId(sessionId);
        session.setProject(project);
        session.setStatus(WorkSessionStatus.OPEN);
        session.setTitle("Inspect project status");
        session.setBaseBranch("main");
        session.setOpenedAt(Instant.parse("2026-03-25T10:05:00Z"));
        session.setLastActivityAt(Instant.parse("2026-03-25T10:05:00Z"));
        session.setCreatedAt(Instant.parse("2026-03-25T10:05:00Z"));
        session.setUpdatedAt(Instant.parse("2026-03-25T10:05:00Z"));
        return session;
    }

    private static AgentRunEntity buildRun(Long runId, AgentRunStatus status) {
        WorkSessionEntity session = buildSession(12L, 7L, "/workspace/repos/internal/atenea");

        SessionTurnEntity originTurn = new SessionTurnEntity();
        originTurn.setId(101L);
        originTurn.setSession(session);
        originTurn.setActor(SessionTurnActor.ATENEA);
        originTurn.setMessageText("Internal AgentRun origin");
        originTurn.setInternal(true);
        originTurn.setCreatedAt(Instant.parse("2026-03-25T10:06:00Z"));

        AgentRunEntity run = new AgentRunEntity();
        run.setId(runId);
        run.setSession(session);
        run.setOriginTurn(originTurn);
        run.setStatus(status);
        run.setTargetRepoPath("/workspace/repos/internal/atenea");
        run.setStartedAt(Instant.parse("2026-03-25T10:06:00Z"));
        run.setCreatedAt(Instant.parse("2026-03-25T10:06:00Z"));
        return run;
    }
}
