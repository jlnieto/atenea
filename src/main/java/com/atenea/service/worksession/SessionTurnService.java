package com.atenea.service.worksession;

import com.atenea.api.worksession.CreateSessionTurnRequest;
import com.atenea.api.worksession.CreateSessionTurnResponse;
import com.atenea.api.worksession.SessionTurnResponse;
import com.atenea.codexappserver.CodexAppServerClient.CodexAppServerExecutionHandle;
import com.atenea.codexappserver.CodexAppServerExecutionListener;
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
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class SessionTurnService {

    private static final int MAX_TURN_WINDOW_LIMIT = 100;
    private static final Logger log = LoggerFactory.getLogger(SessionTurnService.class);

    private final WorkSessionRepository workSessionRepository;
    private final SessionTurnRepository sessionTurnRepository;
    private final WorkspaceRepositoryPathValidator workspaceRepositoryPathValidator;
    private final GitRepositoryService gitRepositoryService;
    private final AgentRunRepository agentRunRepository;
    private final AgentRunService agentRunService;
    private final AgentRunProgressService agentRunProgressService;
    private final AgentRunReconciliationService agentRunReconciliationService;
    private final SessionCodexOrchestrator sessionCodexOrchestrator;
    private final SessionTurnCompletionService sessionTurnCompletionService;

    public SessionTurnService(
            WorkSessionRepository workSessionRepository,
            SessionTurnRepository sessionTurnRepository,
            WorkspaceRepositoryPathValidator workspaceRepositoryPathValidator,
            GitRepositoryService gitRepositoryService,
            AgentRunRepository agentRunRepository,
            AgentRunService agentRunService,
            AgentRunProgressService agentRunProgressService,
            AgentRunReconciliationService agentRunReconciliationService,
            SessionCodexOrchestrator sessionCodexOrchestrator,
            SessionTurnCompletionService sessionTurnCompletionService
    ) {
        this.workSessionRepository = workSessionRepository;
        this.sessionTurnRepository = sessionTurnRepository;
        this.workspaceRepositoryPathValidator = workspaceRepositoryPathValidator;
        this.gitRepositoryService = gitRepositoryService;
        this.agentRunRepository = agentRunRepository;
        this.agentRunService = agentRunService;
        this.agentRunProgressService = agentRunProgressService;
        this.agentRunReconciliationService = agentRunReconciliationService;
        this.sessionCodexOrchestrator = sessionCodexOrchestrator;
        this.sessionTurnCompletionService = sessionTurnCompletionService;
    }

    @Transactional(readOnly = true)
    public List<SessionTurnResponse> getTurns(Long sessionId) {
        return getTurns(sessionId, null, null);
    }

    @Transactional(readOnly = true)
    public List<SessionTurnResponse> getTurns(Long sessionId, Long beforeTurnId, Integer limit) {
        if (!workSessionRepository.existsById(sessionId)) {
            throw new WorkSessionNotFoundException(sessionId);
        }

        List<SessionTurnResponse> visibleTurns = sessionTurnRepository.findBySessionIdOrderByCreatedAtAsc(sessionId)
                .stream()
                // Some SessionTurn rows exist only as internal technical markers for the system.
                // They are not part of the operator-visible conversation. Slice 7 should create
                // operator/Codex turns with internal=false and expose only those through this history.
                .filter(turn -> !turn.isInternal())
                .map(this::toResponse)
                .toList();

        Integer effectiveLimit = normalizeOptionalLimit(limit);
        List<SessionTurnResponse> filteredTurns = beforeTurnId == null
                ? visibleTurns
                : visibleTurns.stream()
                        .filter(turn -> turn.id() < beforeTurnId)
                        .toList();

        if (effectiveLimit == null) {
            return filteredTurns;
        }

        int fromIndex = Math.max(0, filteredTurns.size() - effectiveLimit);
        return filteredTurns.subList(fromIndex, filteredTurns.size());
    }

    @Transactional(noRollbackFor = WorkSessionTurnExecutionFailedException.class)
    public CreateSessionTurnResponse createTurn(Long sessionId, CreateSessionTurnRequest request) {
        String message = request.message() == null ? null : request.message().trim();
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("Turn message must not be blank");
        }

        WorkSessionEntity session = workSessionRepository.findWithProjectById(sessionId)
                .orElseThrow(() -> new WorkSessionNotFoundException(sessionId));

        if (session.getStatus() != WorkSessionStatus.OPEN) {
            throw new WorkSessionNotOpenException(sessionId, session.getStatus());
        }
        agentRunReconciliationService.reconcileSession(sessionId);
        if (agentRunRepository.existsBySessionIdAndStatus(sessionId, AgentRunStatus.RUNNING)) {
            throw new WorkSessionAlreadyRunningException(sessionId);
        }

        String repoPath = resolveOperationalRepoPath(session);
        Instant now = Instant.now();
        SessionTurnEntity operatorTurn = createVisibleTurn(session, SessionTurnActor.OPERATOR, message, now);
        touchSession(session, now);

        AgentRunEntity run = agentRunService.createRunningRun(session, operatorTurn);
        ExecutionProgress progress = new ExecutionProgress();

        try {
            CodexAppServerExecutionHandle executionHandle = startTurnWithThreadRecovery(
                    session,
                    repoPath,
                    operatorTurn.getMessageText(),
                    progress);

            String effectiveThreadId = firstNonBlank(
                    executionHandle.threadId(),
                    progress.threadId,
                    session.getExternalThreadId());
            String effectiveTurnId = firstNonBlank(executionHandle.turnId(), progress.turnId);
            persistExecutionProgress(session, run, effectiveThreadId, effectiveTurnId);
            registerCompletionTracking(
                    session.getId(),
                    run.getId(),
                    effectiveThreadId,
                    effectiveTurnId,
                    executionHandle);

            return new CreateSessionTurnResponse(
                    toResponse(operatorTurn),
                    agentRunService.toResponse(run),
                    null
            );
        } catch (WorkSessionTurnExecutionFailedException exception) {
            throw exception;
        } catch (Exception exception) {
            agentRunService.markFailed(run.getId(), progress.turnId, exception.getMessage());
            persistExecutionProgress(session, run, progress.threadId, progress.turnId);
            touchSession(session, Instant.now());
            throw new WorkSessionTurnExecutionFailedException(
                    "Codex execution failed for WorkSession turn",
                    exception);
        }
    }

    private CodexAppServerExecutionHandle startTurnWithThreadRecovery(
            WorkSessionEntity session,
            String repoPath,
            String message,
            ExecutionProgress progress
    ) throws Exception {
        try {
            return startTurn(repoPath, message, session.getExternalThreadId(), progress);
        } catch (Exception exception) {
            if (!shouldRetryWithFreshThread(session.getExternalThreadId(), exception)) {
                throw exception;
            }

            log.warn(
                    "retrying WorkSession turn with a fresh Codex thread after stale externalThreadId sessionId={} threadId={}",
                    session.getId(),
                    session.getExternalThreadId());
            clearPersistedThread(session);
            progress.threadId = null;
            progress.turnId = null;
            return startTurn(repoPath, message, null, progress);
        }
    }

    private CodexAppServerExecutionHandle startTurn(
            String repoPath,
            String message,
            String threadId,
            ExecutionProgress progress
    ) throws Exception {
        return sessionCodexOrchestrator.startTurn(
                repoPath,
                message,
                threadId,
                new CodexAppServerExecutionListener() {
                    @Override
                    public void onThreadStarted(String newThreadId) {
                        progress.threadId = newThreadId;
                    }

                    @Override
                    public void onTurnStarted(String newThreadId, String turnId) {
                        progress.threadId = newThreadId;
                        progress.turnId = turnId;
                    }
                });
    }

    private boolean shouldRetryWithFreshThread(String externalThreadId, Exception exception) {
        if (externalThreadId == null || externalThreadId.isBlank()) {
            return false;
        }
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return false;
        }
        String normalized = message.toLowerCase();
        return normalized.contains("turn/start") && normalized.contains("thread not found");
    }

    private void clearPersistedThread(WorkSessionEntity session) {
        session.setExternalThreadId(null);
        session.setUpdatedAt(Instant.now());
    }

    private SessionTurnResponse toResponse(SessionTurnEntity turn) {
        return new SessionTurnResponse(
                turn.getId(),
                turn.getActor(),
                turn.getMessageText(),
                turn.getCreatedAt()
        );
    }

    private String resolveOperationalRepoPath(WorkSessionEntity session) {
        String repoPath = workspaceRepositoryPathValidator.normalizeConfiguredRepoPath(session.getProject().getRepoPath());
        try {
            gitRepositoryService.getCurrentBranch(repoPath);
            return repoPath;
        } catch (GitRepositoryOperationException exception) {
            throw new WorkSessionOperationBlockedException(
                    "Project repository is not operational for WorkSession turn execution: "
                            + exception.getMessage());
        }
    }

    private SessionTurnEntity createVisibleTurn(
            WorkSessionEntity session,
            SessionTurnActor actor,
            String messageText,
            Instant createdAt
    ) {
        SessionTurnEntity turn = new SessionTurnEntity();
        turn.setSession(session);
        turn.setActor(actor);
        turn.setMessageText(messageText);
        turn.setInternal(false);
        turn.setCreatedAt(createdAt);
        return sessionTurnRepository.save(turn);
    }

    private void persistExecutionProgress(
            WorkSessionEntity session,
            AgentRunEntity run,
            String externalThreadId,
            String externalTurnId
    ) {
        agentRunProgressService.applyExternalThreadId(session, externalThreadId);
        agentRunProgressService.applyExternalTurnId(run, externalTurnId);
    }

    private void touchSession(WorkSessionEntity session, Instant timestamp) {
        session.setLastActivityAt(timestamp);
        session.setUpdatedAt(timestamp);
    }

    private void registerCompletionTracking(
            Long sessionId,
            Long runId,
            String externalThreadId,
            String externalTurnId,
            CodexAppServerExecutionHandle executionHandle
    ) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            sessionTurnCompletionService.trackCompletion(
                    sessionId,
                    runId,
                    externalThreadId,
                    externalTurnId,
                    executionHandle.completionFuture());
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                sessionTurnCompletionService.trackCompletion(
                        sessionId,
                        runId,
                        externalThreadId,
                        externalTurnId,
                        executionHandle.completionFuture());
            }
        });
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private Integer normalizeOptionalLimit(Integer limit) {
        if (limit == null) {
            return null;
        }
        if (limit <= 0) {
            throw new IllegalArgumentException("Turn limit must be greater than zero");
        }
        if (limit > MAX_TURN_WINDOW_LIMIT) {
            throw new IllegalArgumentException("Turn limit must not exceed " + MAX_TURN_WINDOW_LIMIT);
        }
        return limit;
    }

    private static final class ExecutionProgress {
        private String threadId;
        private String turnId;
    }
}
