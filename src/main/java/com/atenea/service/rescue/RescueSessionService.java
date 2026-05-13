package com.atenea.service.rescue;

import com.atenea.api.rescue.CloseRescueSessionResponse;
import com.atenea.api.rescue.CreateRescueTurnRequest;
import com.atenea.api.rescue.CreateRescueTurnResponse;
import com.atenea.api.rescue.ResolveRescueSessionRequest;
import com.atenea.api.rescue.ResolveRescueSessionResponse;
import com.atenea.api.rescue.RescueSessionConversationViewResponse;
import com.atenea.api.rescue.RescueSessionResponse;
import com.atenea.api.rescue.RescueSessionTurnResponse;
import com.atenea.codexappserver.CodexAppServerClient.CodexAppServerExecutionHandle;
import com.atenea.codexappserver.CodexAppServerExecutionListener;
import com.atenea.codexappserver.CodexAppServerExecutionResult;
import com.atenea.persistence.project.ProjectEntity;
import com.atenea.persistence.project.ProjectRepository;
import com.atenea.persistence.rescue.RescueSessionEntity;
import com.atenea.persistence.rescue.RescueSessionRepository;
import com.atenea.persistence.rescue.RescueSessionStatus;
import com.atenea.persistence.rescue.RescueSessionTurnActor;
import com.atenea.persistence.rescue.RescueSessionTurnEntity;
import com.atenea.persistence.rescue.RescueSessionTurnRepository;
import com.atenea.service.project.WorkspaceRepositoryPathValidator;
import com.atenea.service.worksession.WorkSessionProjectNotFoundException;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class RescueSessionService {

    private static final Logger log = LoggerFactory.getLogger(RescueSessionService.class);
    private static final Set<RescueSessionStatus> ACTIVE_STATUSES = Set.of(
            RescueSessionStatus.OPEN,
            RescueSessionStatus.RUNNING);

    private final ProjectRepository projectRepository;
    private final RescueSessionRepository rescueSessionRepository;
    private final RescueSessionTurnRepository rescueSessionTurnRepository;
    private final WorkspaceRepositoryPathValidator workspaceRepositoryPathValidator;
    private final RescueCodexOrchestrator rescueCodexOrchestrator;
    private final TransactionTemplate transactionTemplate;

    public RescueSessionService(
            ProjectRepository projectRepository,
            RescueSessionRepository rescueSessionRepository,
            RescueSessionTurnRepository rescueSessionTurnRepository,
            WorkspaceRepositoryPathValidator workspaceRepositoryPathValidator,
            RescueCodexOrchestrator rescueCodexOrchestrator,
            PlatformTransactionManager transactionManager
    ) {
        this.projectRepository = projectRepository;
        this.rescueSessionRepository = rescueSessionRepository;
        this.rescueSessionTurnRepository = rescueSessionTurnRepository;
        this.workspaceRepositoryPathValidator = workspaceRepositoryPathValidator;
        this.rescueCodexOrchestrator = rescueCodexOrchestrator;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Transactional
    public ResolveRescueSessionResponse resolveSession(Long projectId, ResolveRescueSessionRequest request) {
        return rescueSessionRepository
                .findFirstByProjectIdAndStatusInOrderByLastActivityAtDesc(projectId, ACTIVE_STATUSES)
                .map(session -> new ResolveRescueSessionResponse(false, toConversationView(session)))
                .orElseGet(() -> {
                    ProjectEntity project = projectRepository.findById(projectId)
                            .orElseThrow(() -> new WorkSessionProjectNotFoundException(projectId));
                    RescueSessionEntity session = createSession(project, request);
                    return new ResolveRescueSessionResponse(true, toConversationView(session));
                });
    }

    @Transactional(readOnly = true)
    public RescueSessionConversationViewResponse getConversation(Long rescueSessionId) {
        RescueSessionEntity session = findSession(rescueSessionId);
        return toConversationView(session);
    }

    @Transactional(noRollbackFor = RescueSessionExecutionFailedException.class)
    public CreateRescueTurnResponse createTurn(Long rescueSessionId, CreateRescueTurnRequest request) {
        String message = request.message() == null ? null : request.message().trim();
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("Rescue turn message must not be blank");
        }

        RescueSessionEntity session = findSession(rescueSessionId);
        if (session.getStatus() == RescueSessionStatus.RUNNING) {
            throw new RescueSessionAlreadyRunningException(rescueSessionId);
        }
        if (session.getStatus() == RescueSessionStatus.CLOSED) {
            throw new RescueSessionClosedException(rescueSessionId, session.getStatus());
        }

        String repoPath = workspaceRepositoryPathValidator.normalizeConfiguredRepoPath(session.getProject().getRepoPath());
        Instant now = Instant.now();
        createTurn(session, RescueSessionTurnActor.OPERATOR, message, null, now);
        session.setStatus(RescueSessionStatus.RUNNING);
        touch(session, now);

        ExecutionProgress progress = new ExecutionProgress();
        try {
            CodexAppServerExecutionHandle executionHandle = startTurnWithThreadRecovery(
                    session,
                    repoPath,
                    message,
                    progress);
            String effectiveThreadId = firstNonBlank(
                    executionHandle.threadId(),
                    progress.threadId,
                    session.getExternalThreadId());
            String effectiveTurnId = firstNonBlank(executionHandle.turnId(), progress.turnId);
            session.setExternalThreadId(effectiveThreadId);
            session.setExternalTurnId(effectiveTurnId);
            registerCompletionTracking(session.getId(), executionHandle.completionFuture());
            return new CreateRescueTurnResponse(toConversationView(session));
        } catch (RescueSessionExecutionFailedException exception) {
            throw exception;
        } catch (Exception exception) {
            session.setStatus(RescueSessionStatus.OPEN);
            session.setExternalThreadId(firstNonBlank(progress.threadId, session.getExternalThreadId()));
            session.setExternalTurnId(firstNonBlank(progress.turnId, session.getExternalTurnId()));
            createTurn(
                    session,
                    RescueSessionTurnActor.ATENEA,
                    "No he podido arrancar Codex en modo rescate: " + firstNonBlank(exception.getMessage(), "error desconocido"),
                    progress.turnId,
                    Instant.now());
            touch(session, Instant.now());
            throw new RescueSessionExecutionFailedException("Codex execution failed for rescue session turn", exception);
        }
    }

    @Transactional
    public CloseRescueSessionResponse closeSession(Long rescueSessionId) {
        RescueSessionEntity session = findSession(rescueSessionId);
        if (session.getStatus() == RescueSessionStatus.RUNNING) {
            throw new RescueSessionAlreadyRunningException(rescueSessionId);
        }
        if (session.getStatus() != RescueSessionStatus.CLOSED) {
            Instant now = Instant.now();
            session.setStatus(RescueSessionStatus.CLOSED);
            session.setClosedAt(now);
            touch(session, now);
        }
        return new CloseRescueSessionResponse(toConversationView(session));
    }

    private RescueSessionEntity createSession(ProjectEntity project, ResolveRescueSessionRequest request) {
        String repoPath = workspaceRepositoryPathValidator.normalizeConfiguredRepoPath(project.getRepoPath());
        Instant now = Instant.now();
        RescueSessionEntity session = new RescueSessionEntity();
        session.setProject(project);
        session.setStatus(RescueSessionStatus.OPEN);
        session.setTitle(normalizeTitle(request, project));
        session.setOpenedAt(now);
        session.setLastActivityAt(now);
        session.setCreatedAt(now);
        session.setUpdatedAt(now);
        RescueSessionEntity saved = rescueSessionRepository.save(session);
        createTurn(
                saved,
                RescueSessionTurnActor.ATENEA,
                "Modo rescate abierto sobre `" + repoPath + "`. Este canal puede operar directamente sobre el repositorio si lo autorizas.",
                null,
                now);
        return saved;
    }

    private CodexAppServerExecutionHandle startTurnWithThreadRecovery(
            RescueSessionEntity session,
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
                    "retrying rescue turn with a fresh Codex thread after stale externalThreadId rescueSessionId={} threadId={}",
                    session.getId(),
                    session.getExternalThreadId());
            session.setExternalThreadId(null);
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
        return rescueCodexOrchestrator.startTurn(
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

    private void registerCompletionTracking(
            Long rescueSessionId,
            CompletableFuture<CodexAppServerExecutionResult> completionFuture
    ) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            trackCompletion(rescueSessionId, completionFuture);
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                trackCompletion(rescueSessionId, completionFuture);
            }
        });
    }

    private void trackCompletion(
            Long rescueSessionId,
            CompletableFuture<CodexAppServerExecutionResult> completionFuture
    ) {
        completionFuture.whenComplete((result, exception) -> transactionTemplate.executeWithoutResult(status ->
                completeTurn(rescueSessionId, result, exception)));
    }

    private void completeTurn(
            Long rescueSessionId,
            CodexAppServerExecutionResult result,
            Throwable exception
    ) {
        RescueSessionEntity session = rescueSessionRepository.findWithProjectById(rescueSessionId).orElse(null);
        if (session == null || session.getStatus() == RescueSessionStatus.CLOSED) {
            return;
        }

        Instant now = Instant.now();
        if (exception != null) {
            createTurn(
                    session,
                    RescueSessionTurnActor.ATENEA,
                    "Codex no ha podido completar el turno de rescate: " + firstNonBlank(exception.getMessage(), "error desconocido"),
                    session.getExternalTurnId(),
                    now);
        } else if (result == null || result.status() != CodexAppServerExecutionResult.Status.COMPLETED) {
            createTurn(
                    session,
                    RescueSessionTurnActor.ATENEA,
                    "Codex ha terminado el turno de rescate sin una respuesta válida: "
                            + firstNonBlank(result == null ? null : result.errorMessage(), "sin detalle técnico"),
                    result == null ? session.getExternalTurnId() : firstNonBlank(result.turnId(), session.getExternalTurnId()),
                    now);
        } else {
            session.setExternalThreadId(firstNonBlank(result.threadId(), session.getExternalThreadId()));
            session.setExternalTurnId(firstNonBlank(result.turnId(), session.getExternalTurnId()));
            createTurn(
                    session,
                    RescueSessionTurnActor.CODEX,
                    firstNonBlank(result.finalAnswer(), result.outputSummary(), "Codex ha terminado sin texto de respuesta."),
                    firstNonBlank(result.turnId(), session.getExternalTurnId()),
                    now);
        }

        session.setStatus(RescueSessionStatus.OPEN);
        touch(session, now);
    }

    private RescueSessionEntity findSession(Long rescueSessionId) {
        return rescueSessionRepository.findWithProjectById(rescueSessionId)
                .orElseThrow(() -> new RescueSessionNotFoundException(rescueSessionId));
    }

    private RescueSessionConversationViewResponse toConversationView(RescueSessionEntity session) {
        return new RescueSessionConversationViewResponse(
                toResponse(session),
                rescueSessionTurnRepository.findByRescueSessionIdOrderByCreatedAtAsc(session.getId())
                        .stream()
                        .map(this::toResponse)
                        .toList());
    }

    private RescueSessionResponse toResponse(RescueSessionEntity session) {
        return new RescueSessionResponse(
                session.getId(),
                session.getProject().getId(),
                session.getProject().getName(),
                session.getProject().getRepoPath(),
                session.getStatus(),
                session.getTitle(),
                session.getStatus() == RescueSessionStatus.OPEN,
                session.getExternalThreadId(),
                session.getExternalTurnId(),
                session.getOpenedAt(),
                session.getLastActivityAt(),
                session.getClosedAt());
    }

    private RescueSessionTurnResponse toResponse(RescueSessionTurnEntity turn) {
        return new RescueSessionTurnResponse(
                turn.getId(),
                turn.getActor(),
                turn.getMessageText(),
                turn.getExternalTurnId(),
                turn.getCreatedAt());
    }

    private RescueSessionTurnEntity createTurn(
            RescueSessionEntity session,
            RescueSessionTurnActor actor,
            String messageText,
            String externalTurnId,
            Instant createdAt
    ) {
        RescueSessionTurnEntity turn = new RescueSessionTurnEntity();
        turn.setRescueSession(session);
        turn.setActor(actor);
        turn.setMessageText(messageText);
        turn.setExternalTurnId(externalTurnId);
        turn.setCreatedAt(createdAt);
        return rescueSessionTurnRepository.save(turn);
    }

    private void touch(RescueSessionEntity session, Instant timestamp) {
        session.setLastActivityAt(timestamp);
        session.setUpdatedAt(timestamp);
    }

    private String normalizeTitle(ResolveRescueSessionRequest request, ProjectEntity project) {
        String title = request == null ? null : request.title();
        String normalized = title == null ? null : title.trim();
        if (normalized == null || normalized.isBlank()) {
            return "Rescate de " + project.getName();
        }
        if (normalized.length() > 200) {
            return normalized.substring(0, 200);
        }
        return normalized;
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
