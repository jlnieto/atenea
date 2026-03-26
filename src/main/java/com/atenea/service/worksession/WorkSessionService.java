package com.atenea.service.worksession;

import com.atenea.api.worksession.CreateWorkSessionRequest;
import com.atenea.api.worksession.WorkSessionResponse;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkSessionService {

    private final ProjectRepository projectRepository;
    private final WorkSessionRepository workSessionRepository;
    private final WorkspaceRepositoryPathValidator workspaceRepositoryPathValidator;
    private final GitRepositoryService gitRepositoryService;
    private final SessionOperationalSnapshotService sessionOperationalSnapshotService;
    private final AgentRunRepository agentRunRepository;

    public WorkSessionService(
            ProjectRepository projectRepository,
            WorkSessionRepository workSessionRepository,
            WorkspaceRepositoryPathValidator workspaceRepositoryPathValidator,
            GitRepositoryService gitRepositoryService,
            SessionOperationalSnapshotService sessionOperationalSnapshotService,
            AgentRunRepository agentRunRepository
    ) {
        this.projectRepository = projectRepository;
        this.workSessionRepository = workSessionRepository;
        this.workspaceRepositoryPathValidator = workspaceRepositoryPathValidator;
        this.gitRepositoryService = gitRepositoryService;
        this.sessionOperationalSnapshotService = sessionOperationalSnapshotService;
        this.agentRunRepository = agentRunRepository;
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

    @Transactional(readOnly = true)
    public WorkSessionResponse getSession(Long sessionId) {
        WorkSessionEntity session = workSessionRepository.findWithProjectById(sessionId)
                .orElseThrow(() -> new WorkSessionNotFoundException(sessionId));
        return toResponse(session);
    }

    @Transactional
    public WorkSessionResponse closeSession(Long sessionId) {
        WorkSessionEntity session = workSessionRepository.findWithProjectById(sessionId)
                .orElseThrow(() -> new WorkSessionNotFoundException(sessionId));

        if (session.getStatus() != WorkSessionStatus.OPEN) {
            throw new WorkSessionNotOpenException(sessionId, session.getStatus());
        }

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
        return new WorkSessionResponse(
                session.getId(),
                session.getProject().getId(),
                session.getStatus(),
                session.getTitle(),
                session.getBaseBranch(),
                session.getWorkspaceBranch(),
                session.getExternalThreadId(),
                session.getOpenedAt(),
                session.getLastActivityAt(),
                session.getClosedAt(),
                sessionOperationalSnapshotService.snapshot(session)
        );
    }

    private String normalizeNullableText(String value) {
        return workspaceRepositoryPathValidator.normalizeNullableText(value);
    }
}
