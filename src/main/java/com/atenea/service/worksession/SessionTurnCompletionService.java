package com.atenea.service.worksession;

import com.atenea.codexappserver.CodexAppServerExecutionResult;
import com.atenea.persistence.worksession.AgentRunEntity;
import com.atenea.persistence.worksession.AgentRunRepository;
import com.atenea.persistence.worksession.AgentRunStatus;
import com.atenea.persistence.worksession.SessionTurnActor;
import com.atenea.persistence.worksession.SessionTurnEntity;
import com.atenea.persistence.worksession.SessionTurnRepository;
import com.atenea.persistence.worksession.WorkSessionEntity;
import com.atenea.persistence.worksession.WorkSessionRepository;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class SessionTurnCompletionService {

    private static final Logger log = LoggerFactory.getLogger(SessionTurnCompletionService.class);

    private final WorkSessionRepository workSessionRepository;
    private final AgentRunRepository agentRunRepository;
    private final SessionTurnRepository sessionTurnRepository;
    private final AgentRunService agentRunService;
    private final AgentRunProgressService agentRunProgressService;
    private final TransactionTemplate requiresNewTransaction;

    public SessionTurnCompletionService(
            WorkSessionRepository workSessionRepository,
            AgentRunRepository agentRunRepository,
            SessionTurnRepository sessionTurnRepository,
            AgentRunService agentRunService,
            AgentRunProgressService agentRunProgressService,
            PlatformTransactionManager transactionManager
    ) {
        this.workSessionRepository = workSessionRepository;
        this.agentRunRepository = agentRunRepository;
        this.sessionTurnRepository = sessionTurnRepository;
        this.agentRunService = agentRunService;
        this.agentRunProgressService = agentRunProgressService;
        this.requiresNewTransaction = new TransactionTemplate(transactionManager);
        this.requiresNewTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void trackCompletion(
            Long sessionId,
            Long runId,
            String initialThreadId,
            String initialTurnId,
            CompletableFuture<CodexAppServerExecutionResult> completionFuture
    ) {
        completionFuture.whenComplete((result, throwable) -> {
            try {
                requiresNewTransaction.executeWithoutResult(status -> finalizeCompletion(
                        sessionId,
                        runId,
                        initialThreadId,
                        initialTurnId,
                        result,
                        throwable));
            } catch (Exception exception) {
                handleCompletionPersistenceFailure(sessionId, runId, initialTurnId, result, throwable, exception);
            }
        });
    }

    private void finalizeCompletion(
            Long sessionId,
            Long runId,
            String initialThreadId,
            String initialTurnId,
            CodexAppServerExecutionResult result,
            Throwable throwable
    ) {
        WorkSessionEntity session = workSessionRepository.findById(sessionId).orElse(null);
        AgentRunEntity run = agentRunRepository.findById(runId).orElse(null);
        if (session == null || run == null) {
            log.warn("dropping WorkSession run completion because session or run no longer exists sessionId={} runId={}",
                    sessionId, runId);
            return;
        }

        String effectiveThreadId = firstNonBlank(
                result == null ? null : result.threadId(),
                initialThreadId,
                session.getExternalThreadId());
        String effectiveTurnId = firstNonBlank(
                result == null ? null : result.turnId(),
                initialTurnId,
                run.getExternalTurnId());

        agentRunProgressService.applyExternalThreadId(session, effectiveThreadId);
        agentRunProgressService.applyExternalTurnId(run, effectiveTurnId);

        if (run.getStatus() != AgentRunStatus.RUNNING) {
            log.warn("ignoring WorkSession run completion because run is already terminal runId={} status={}",
                    runId, run.getStatus());
            return;
        }

        Instant now = Instant.now();
        session.setLastActivityAt(now);
        session.setUpdatedAt(now);

        if (throwable != null) {
            agentRunService.markFailed(runId, effectiveTurnId, throwable.getMessage());
            return;
        }

        if (result == null) {
            agentRunService.markFailed(runId, effectiveTurnId, "Codex App Server completion returned no result");
            return;
        }

        if (result.status() != CodexAppServerExecutionResult.Status.COMPLETED) {
            agentRunService.markFailed(runId, effectiveTurnId, firstNonBlank(
                    result.errorMessage(),
                    "Codex App Server turn failed with status " + result.status()));
            return;
        }

        SessionTurnEntity codexTurn = new SessionTurnEntity();
        codexTurn.setSession(session);
        codexTurn.setActor(SessionTurnActor.CODEX);
        codexTurn.setMessageText(result.finalAnswer() == null ? "" : result.finalAnswer());
        codexTurn.setInternal(false);
        codexTurn.setCreatedAt(now);
        codexTurn = sessionTurnRepository.save(codexTurn);

        agentRunService.markSucceeded(runId, effectiveTurnId, result.finalAnswer(), codexTurn);
    }

    private void handleCompletionPersistenceFailure(
            Long sessionId,
            Long runId,
            String initialTurnId,
            CodexAppServerExecutionResult result,
            Throwable completionThrowable,
            Exception persistenceException
    ) {
        String externalTurnId = firstNonBlank(
                result == null ? null : result.turnId(),
                initialTurnId);
        String terminalError = firstNonBlank(
                completionThrowable == null ? null : completionThrowable.getMessage(),
                result == null ? null : result.errorMessage(),
                persistenceException.getMessage(),
                "Failed to persist asynchronous WorkSession run completion");

        log.error(
                "failed to persist WorkSession run completion sessionId={} runId={}, forcing FAILED fallback if still RUNNING",
                sessionId,
                runId,
                persistenceException);

        try {
            boolean forced = agentRunService.forceMarkFailedIfRunning(runId, externalTurnId, terminalError);
            if (!forced) {
                log.warn(
                        "fallback did not mark AgentRun FAILED because it was no longer RUNNING sessionId={} runId={}",
                        sessionId,
                        runId);
            }
        } catch (Exception fallbackException) {
            log.error("failed fallback while forcing AgentRun FAILED sessionId={} runId={}", sessionId, runId, fallbackException);
        }
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
