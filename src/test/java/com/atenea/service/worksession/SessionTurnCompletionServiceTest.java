package com.atenea.service.worksession;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.atenea.codexappserver.CodexAppServerExecutionResult;
import com.atenea.persistence.worksession.AgentRunEntity;
import com.atenea.persistence.worksession.AgentRunRepository;
import com.atenea.persistence.worksession.AgentRunStatus;
import com.atenea.persistence.worksession.SessionTurnRepository;
import com.atenea.persistence.worksession.WorkSessionEntity;
import com.atenea.persistence.worksession.WorkSessionRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

@ExtendWith(MockitoExtension.class)
class SessionTurnCompletionServiceTest {

    @Mock
    private WorkSessionRepository workSessionRepository;

    @Mock
    private AgentRunRepository agentRunRepository;

    @Mock
    private SessionTurnRepository sessionTurnRepository;

    @Mock
    private AgentRunService agentRunService;

    @Mock
    private PlatformTransactionManager transactionManager;

    private SessionTurnCompletionService sessionTurnCompletionService;

    @BeforeEach
    void setUp() {
        TransactionStatus status = new SimpleTransactionStatus();
        when(transactionManager.getTransaction(any(TransactionDefinition.class))).thenReturn(status);
        sessionTurnCompletionService = new SessionTurnCompletionService(
                workSessionRepository,
                agentRunRepository,
                sessionTurnRepository,
                agentRunService,
                new AgentRunProgressService(),
                transactionManager
        );
    }

    @Test
    void completionExceptionalMarksRunFailed() {
        WorkSessionEntity session = buildSession(12L);
        AgentRunEntity run = buildRun(55L, session);

        when(workSessionRepository.findById(12L)).thenReturn(Optional.of(session));
        when(agentRunRepository.findById(55L)).thenReturn(Optional.of(run));

        CompletableFuture<CodexAppServerExecutionResult> completion = new CompletableFuture<>();
        completion.completeExceptionally(new RuntimeException("java.io.IOException: Output closed"));

        sessionTurnCompletionService.trackCompletion(12L, 55L, "thread-1", "turn-1", completion);

        verify(agentRunService).markFailed(55L, "turn-1", "java.io.IOException: Output closed");
    }

    @Test
    void persistenceFailureFallsBackToForceFailedWhenRunIsStillRunning() {
        WorkSessionEntity session = buildSession(12L);
        AgentRunEntity run = buildRun(55L, session);

        when(workSessionRepository.findById(12L)).thenReturn(Optional.of(session));
        when(agentRunRepository.findById(55L)).thenReturn(Optional.of(run));
        when(agentRunService.forceMarkFailedIfRunning(eq(55L), eq("turn-1"), eq("Codex App Server connection closed before turn completion")))
                .thenReturn(true);
        when(agentRunService.markFailed(eq(55L), eq("turn-1"), eq("Codex App Server connection closed before turn completion")))
                .thenThrow(new RuntimeException("database unavailable"));

        CompletableFuture<CodexAppServerExecutionResult> completion = CompletableFuture.completedFuture(
                new CodexAppServerExecutionResult(
                        "thread-1",
                        "turn-1",
                        CodexAppServerExecutionResult.Status.FAILED,
                        null,
                        null,
                        "Codex App Server connection closed before turn completion"));

        sessionTurnCompletionService.trackCompletion(12L, 55L, "thread-1", "turn-1", completion);

        verify(agentRunService).forceMarkFailedIfRunning(
                55L,
                "turn-1",
                "Codex App Server connection closed before turn completion");
    }

    @Test
    void fallbackDoesNotForceFailedWhenRunIsAlreadyTerminal() {
        WorkSessionEntity session = buildSession(12L);
        AgentRunEntity run = buildRun(55L, session);
        run.setStatus(AgentRunStatus.SUCCEEDED);

        when(workSessionRepository.findById(12L)).thenReturn(Optional.of(session));
        when(agentRunRepository.findById(55L)).thenReturn(Optional.of(run));

        CompletableFuture<CodexAppServerExecutionResult> completion = CompletableFuture.completedFuture(
                new CodexAppServerExecutionResult(
                        "thread-1",
                        "turn-1",
                        CodexAppServerExecutionResult.Status.COMPLETED,
                        "Done",
                        null,
                        null));

        sessionTurnCompletionService.trackCompletion(12L, 55L, "thread-1", "turn-1", completion);

        verify(agentRunService, never()).forceMarkFailedIfRunning(any(), any(), any());
    }

    private static WorkSessionEntity buildSession(Long sessionId) {
        WorkSessionEntity session = new WorkSessionEntity();
        session.setId(sessionId);
        session.setExternalThreadId(null);
        session.setCreatedAt(Instant.parse("2026-03-25T10:05:00Z"));
        session.setUpdatedAt(Instant.parse("2026-03-25T10:05:00Z"));
        session.setLastActivityAt(Instant.parse("2026-03-25T10:05:00Z"));
        return session;
    }

    private static AgentRunEntity buildRun(Long runId, WorkSessionEntity session) {
        AgentRunEntity run = new AgentRunEntity();
        run.setId(runId);
        run.setSession(session);
        run.setStatus(AgentRunStatus.RUNNING);
        run.setCreatedAt(Instant.parse("2026-03-25T10:06:00Z"));
        run.setStartedAt(Instant.parse("2026-03-25T10:06:00Z"));
        return run;
    }
}
