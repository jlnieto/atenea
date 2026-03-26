package com.atenea.service.worksession;

import com.atenea.api.worksession.CreateSessionTurnRequest;
import com.atenea.api.worksession.CreateSessionTurnResponse;
import com.atenea.api.worksession.SessionTurnResponse;
import com.atenea.codexappserver.CodexAppServerExecutionListener;
import com.atenea.codexappserver.CodexAppServerExecutionResult;
import com.atenea.persistence.worksession.AgentRunEntity;
import com.atenea.persistence.worksession.SessionTurnActor;
import com.atenea.persistence.worksession.SessionTurnEntity;
import com.atenea.persistence.worksession.SessionTurnRepository;
import com.atenea.persistence.worksession.WorkSessionEntity;
import com.atenea.persistence.worksession.WorkSessionRepository;
import com.atenea.persistence.worksession.WorkSessionStatus;
import com.atenea.service.project.WorkspaceRepositoryPathValidator;
import com.atenea.service.taskexecution.GitRepositoryService;
import com.atenea.service.taskexecution.TaskLaunchBlockedException;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SessionTurnService {

    private final WorkSessionRepository workSessionRepository;
    private final SessionTurnRepository sessionTurnRepository;
    private final WorkspaceRepositoryPathValidator workspaceRepositoryPathValidator;
    private final GitRepositoryService gitRepositoryService;
    private final AgentRunService agentRunService;
    private final AgentRunProgressService agentRunProgressService;
    private final SessionCodexOrchestrator sessionCodexOrchestrator;

    public SessionTurnService(
            WorkSessionRepository workSessionRepository,
            SessionTurnRepository sessionTurnRepository,
            WorkspaceRepositoryPathValidator workspaceRepositoryPathValidator,
            GitRepositoryService gitRepositoryService,
            AgentRunService agentRunService,
            AgentRunProgressService agentRunProgressService,
            SessionCodexOrchestrator sessionCodexOrchestrator
    ) {
        this.workSessionRepository = workSessionRepository;
        this.sessionTurnRepository = sessionTurnRepository;
        this.workspaceRepositoryPathValidator = workspaceRepositoryPathValidator;
        this.gitRepositoryService = gitRepositoryService;
        this.agentRunService = agentRunService;
        this.agentRunProgressService = agentRunProgressService;
        this.sessionCodexOrchestrator = sessionCodexOrchestrator;
    }

    @Transactional(readOnly = true)
    public List<SessionTurnResponse> getTurns(Long sessionId) {
        if (!workSessionRepository.existsById(sessionId)) {
            throw new WorkSessionNotFoundException(sessionId);
        }

        return sessionTurnRepository.findBySessionIdOrderByCreatedAtAsc(sessionId)
                .stream()
                // Some SessionTurn rows exist only as internal technical markers for the system.
                // They are not part of the operator-visible conversation. Slice 7 should create
                // operator/Codex turns with internal=false and expose only those through this history.
                .filter(turn -> !turn.isInternal())
                .map(this::toResponse)
                .toList();
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

        String repoPath = resolveOperationalRepoPath(session);
        Instant now = Instant.now();
        SessionTurnEntity operatorTurn = createVisibleTurn(session, SessionTurnActor.OPERATOR, message, now);
        touchSession(session, now);

        AgentRunEntity run = agentRunService.createRunningRun(session, operatorTurn);
        ExecutionProgress progress = new ExecutionProgress();

        try {
            CodexAppServerExecutionResult executionResult = sessionCodexOrchestrator.executeTurn(
                    repoPath,
                    operatorTurn.getMessageText(),
                    session.getExternalThreadId(),
                    new CodexAppServerExecutionListener() {
                        @Override
                        public void onThreadStarted(String threadId) {
                            progress.threadId = threadId;
                        }

                        @Override
                        public void onTurnStarted(String threadId, String turnId) {
                            progress.threadId = threadId;
                            progress.turnId = turnId;
                        }
                    });

            String effectiveThreadId = firstNonBlank(
                    executionResult.threadId(),
                    progress.threadId,
                    session.getExternalThreadId());
            String effectiveTurnId = firstNonBlank(executionResult.turnId(), progress.turnId);
            persistExecutionProgress(session, run, effectiveThreadId, effectiveTurnId);

            if (executionResult.status() != CodexAppServerExecutionResult.Status.COMPLETED) {
                agentRunService.markFailed(run.getId(), effectiveTurnId, executionResult.errorMessage());
                touchSession(session, Instant.now());
                throw new WorkSessionTurnExecutionFailedException(firstNonBlank(
                        executionResult.errorMessage(),
                        "Codex execution failed"));
            }

            Instant completionTime = Instant.now();
            SessionTurnEntity codexTurn = createVisibleTurn(
                    session,
                    SessionTurnActor.CODEX,
                    firstNonBlank(executionResult.finalAnswer(), ""),
                    completionTime);
            touchSession(session, completionTime);

            AgentRunEntity succeededRun = agentRunService.markSucceeded(
                    run.getId(),
                    effectiveTurnId,
                    executionResult.finalAnswer(),
                    codexTurn);

            return new CreateSessionTurnResponse(
                    toResponse(operatorTurn),
                    agentRunService.toResponse(succeededRun),
                    toResponse(codexTurn)
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
        } catch (TaskLaunchBlockedException exception) {
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

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static final class ExecutionProgress {
        private String threadId;
        private String turnId;
    }
}
