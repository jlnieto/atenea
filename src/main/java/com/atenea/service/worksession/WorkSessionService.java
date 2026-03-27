package com.atenea.service.worksession;

import com.atenea.api.worksession.CreateWorkSessionRequest;
import com.atenea.api.worksession.ResolveWorkSessionConversationViewResponse;
import com.atenea.api.worksession.ResolveWorkSessionRequest;
import com.atenea.api.worksession.ResolveWorkSessionResponse;
import com.atenea.api.worksession.ResolveWorkSessionViewResponse;
import com.atenea.api.worksession.SessionTurnResponse;
import com.atenea.api.worksession.SessionOperationalSnapshotResponse;
import com.atenea.api.worksession.WorkSessionConversationViewResponse;
import com.atenea.api.worksession.WorkSessionResponse;
import com.atenea.api.worksession.WorkSessionOperationalState;
import com.atenea.api.worksession.WorkSessionViewLatestRunResponse;
import com.atenea.api.worksession.WorkSessionViewResponse;
import com.atenea.persistence.worksession.AgentRunEntity;
import com.atenea.persistence.project.ProjectEntity;
import com.atenea.persistence.project.ProjectRepository;
import com.atenea.persistence.worksession.AgentRunRepository;
import com.atenea.persistence.worksession.AgentRunStatus;
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
public class WorkSessionService {

    private static final int RECENT_TURN_LIMIT = 20;

    private final ProjectRepository projectRepository;
    private final WorkSessionRepository workSessionRepository;
    private final WorkspaceRepositoryPathValidator workspaceRepositoryPathValidator;
    private final GitRepositoryService gitRepositoryService;
    private final SessionOperationalSnapshotService sessionOperationalSnapshotService;
    private final AgentRunRepository agentRunRepository;
    private final SessionTurnService sessionTurnService;
    private final AgentRunReconciliationService agentRunReconciliationService;

    public WorkSessionService(
            ProjectRepository projectRepository,
            WorkSessionRepository workSessionRepository,
            WorkspaceRepositoryPathValidator workspaceRepositoryPathValidator,
            GitRepositoryService gitRepositoryService,
            SessionOperationalSnapshotService sessionOperationalSnapshotService,
            AgentRunRepository agentRunRepository,
            SessionTurnService sessionTurnService,
            AgentRunReconciliationService agentRunReconciliationService
    ) {
        this.projectRepository = projectRepository;
        this.workSessionRepository = workSessionRepository;
        this.workspaceRepositoryPathValidator = workspaceRepositoryPathValidator;
        this.gitRepositoryService = gitRepositoryService;
        this.sessionOperationalSnapshotService = sessionOperationalSnapshotService;
        this.agentRunRepository = agentRunRepository;
        this.sessionTurnService = sessionTurnService;
        this.agentRunReconciliationService = agentRunReconciliationService;
    }

    @Transactional
    public WorkSessionResponse openSession(Long projectId, CreateWorkSessionRequest request) {
        ProjectEntity project = projectRepository.findById(projectId)
                .orElseThrow(() -> new WorkSessionProjectNotFoundException(projectId));

        if (workSessionRepository.existsByProjectIdAndStatus(projectId, WorkSessionStatus.OPEN)) {
            throw new OpenWorkSessionAlreadyExistsException(projectId);
        }

        String normalizedRepoPath = workspaceRepositoryPathValidator.normalizeConfiguredRepoPath(project.getRepoPath());
        String currentBranch = resolveCurrentBranch(normalizedRepoPath);
        String normalizedBaseBranch = normalizeNullableText(request.baseBranch());
        String baseBranch = normalizedBaseBranch == null ? currentBranch : normalizedBaseBranch;

        Instant now = Instant.now();

        WorkSessionEntity session = new WorkSessionEntity();
        session.setProject(project);
        session.setStatus(WorkSessionStatus.OPEN);
        session.setTitle(request.title().trim());
        session.setBaseBranch(baseBranch);
        session.setWorkspaceBranch(null);
        session.setExternalThreadId(null);
        session.setOpenedAt(now);
        session.setLastActivityAt(now);
        session.setClosedAt(null);
        session.setCreatedAt(now);
        session.setUpdatedAt(now);

        return toResponse(workSessionRepository.save(session));
    }

    @Transactional
    public ResolveWorkSessionResponse resolveSession(Long projectId, ResolveWorkSessionRequest request) {
        projectRepository.findById(projectId)
                .orElseThrow(() -> new WorkSessionProjectNotFoundException(projectId));

        WorkSessionEntity openSession = workSessionRepository.findByProjectIdAndStatus(projectId, WorkSessionStatus.OPEN)
                .orElse(null);
        if (openSession != null) {
            return new ResolveWorkSessionResponse(false, toResponse(openSession));
        }

        if (request == null || normalizeNullableText(request.title()) == null) {
            throw new IllegalArgumentException("Session title is required when no open WorkSession exists");
        }

        WorkSessionResponse createdSession = openSession(
                projectId,
                new CreateWorkSessionRequest(request.title(), request.baseBranch()));
        return new ResolveWorkSessionResponse(true, createdSession);
    }

    @Transactional
    public ResolveWorkSessionViewResponse resolveSessionView(Long projectId, ResolveWorkSessionRequest request) {
        ResolveWorkSessionResponse resolved = resolveSession(projectId, request);
        WorkSessionViewResponse view = getSessionView(resolved.session().id());
        return new ResolveWorkSessionViewResponse(resolved.created(), view);
    }

    @Transactional
    public ResolveWorkSessionConversationViewResponse resolveSessionConversationView(
            Long projectId,
            ResolveWorkSessionRequest request
    ) {
        ResolveWorkSessionResponse resolved = resolveSession(projectId, request);
        WorkSessionConversationViewResponse view = getSessionConversationView(resolved.session().id());
        return new ResolveWorkSessionConversationViewResponse(resolved.created(), view);
    }

    @Transactional(readOnly = true)
    public WorkSessionResponse getSession(Long sessionId) {
        WorkSessionEntity session = workSessionRepository.findWithProjectById(sessionId)
                .orElseThrow(() -> new WorkSessionNotFoundException(sessionId));
        return toResponse(session);
    }

    @Transactional(readOnly = true)
    public WorkSessionViewResponse getSessionView(Long sessionId) {
        WorkSessionEntity session = workSessionRepository.findWithProjectById(sessionId)
                .orElseThrow(() -> new WorkSessionNotFoundException(sessionId));

        WorkSessionResponse sessionResponse = toResponse(session);
        AgentRunEntity latestRun = agentRunRepository.findFirstBySessionIdOrderByCreatedAtDesc(sessionId).orElse(null);
        AgentRunEntity latestFailedRun = agentRunRepository.findFirstBySessionIdAndStatusOrderByCreatedAtDesc(
                sessionId,
                AgentRunStatus.FAILED).orElse(null);
        AgentRunEntity latestSucceededRun = agentRunRepository.findFirstBySessionIdAndStatusOrderByCreatedAtDesc(
                sessionId,
                AgentRunStatus.SUCCEEDED).orElse(null);

        return new WorkSessionViewResponse(
                sessionResponse,
                sessionResponse.repoState().runInProgress(),
                canCreateTurn(sessionResponse),
                latestRun == null ? null : toLatestRunResponse(latestRun),
                latestFailedRun == null ? null : latestFailedRun.getErrorSummary(),
                latestSucceededRun == null ? null : latestSucceededRun.getOutputSummary()
        );
    }

    @Transactional(readOnly = true)
    public WorkSessionConversationViewResponse getSessionConversationView(Long sessionId) {
        WorkSessionViewResponse view = getSessionView(sessionId);
        List<SessionTurnResponse> turns = sessionTurnService.getTurns(sessionId, null, RECENT_TURN_LIMIT);
        int totalVisibleTurns = sessionTurnService.getTurns(sessionId).size();
        return new WorkSessionConversationViewResponse(
                view,
                turns,
                RECENT_TURN_LIMIT,
                totalVisibleTurns > RECENT_TURN_LIMIT
        );
    }

    @Transactional
    public WorkSessionResponse closeSession(Long sessionId) {
        WorkSessionEntity session = workSessionRepository.findWithProjectById(sessionId)
                .orElseThrow(() -> new WorkSessionNotFoundException(sessionId));

        if (session.getStatus() != WorkSessionStatus.OPEN) {
            throw new WorkSessionNotOpenException(sessionId, session.getStatus());
        }

        agentRunReconciliationService.reconcileSession(sessionId);
        if (agentRunRepository.existsBySessionIdAndStatus(sessionId, AgentRunStatus.RUNNING)) {
            throw new AgentRunAlreadyRunningException(sessionId);
        }

        Instant now = Instant.now();
        session.setStatus(WorkSessionStatus.CLOSED);
        session.setClosedAt(now);
        session.setUpdatedAt(now);
        return toResponse(session);
    }

    private String resolveCurrentBranch(String repoPath) {
        try {
            return gitRepositoryService.getCurrentBranch(repoPath);
        } catch (TaskLaunchBlockedException exception) {
            throw new WorkSessionOperationBlockedException(
                    "Project repository is not operational for WorkSession opening: " + exception.getMessage());
        }
    }

    private WorkSessionResponse toResponse(WorkSessionEntity session) {
        SessionOperationalSnapshotResponse snapshot = sessionOperationalSnapshotService.snapshot(session);
        return new WorkSessionResponse(
                session.getId(),
                session.getProject().getId(),
                session.getStatus(),
                resolveOperationalState(session, snapshot),
                session.getTitle(),
                session.getBaseBranch(),
                session.getWorkspaceBranch(),
                session.getExternalThreadId(),
                session.getOpenedAt(),
                session.getLastActivityAt(),
                session.getClosedAt(),
                snapshot
        );
    }

    private WorkSessionViewLatestRunResponse toLatestRunResponse(AgentRunEntity run) {
        return new WorkSessionViewLatestRunResponse(
                run.getId(),
                run.getStatus(),
                run.getOriginTurn() == null ? null : run.getOriginTurn().getId(),
                run.getResultTurn() == null ? null : run.getResultTurn().getId(),
                run.getExternalTurnId(),
                run.getStartedAt(),
                run.getFinishedAt(),
                run.getOutputSummary(),
                run.getErrorSummary()
        );
    }

    private WorkSessionOperationalState resolveOperationalState(
            WorkSessionEntity session,
            SessionOperationalSnapshotResponse snapshot
    ) {
        if (session.getStatus() == WorkSessionStatus.CLOSED) {
            return WorkSessionOperationalState.CLOSED;
        }
        if (snapshot.runInProgress()) {
            return WorkSessionOperationalState.RUNNING;
        }
        return WorkSessionOperationalState.IDLE;
    }

    private boolean canCreateTurn(WorkSessionResponse session) {
        return session.operationalState() == WorkSessionOperationalState.IDLE;
    }

    private String normalizeNullableText(String value) {
        return workspaceRepositoryPathValidator.normalizeNullableText(value);
    }
}
