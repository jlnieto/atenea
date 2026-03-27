package com.atenea.service.worksession;

import com.atenea.api.worksession.SessionOperationalSnapshotResponse;
import com.atenea.persistence.worksession.AgentRunRepository;
import com.atenea.persistence.worksession.AgentRunStatus;
import com.atenea.persistence.worksession.WorkSessionEntity;
import com.atenea.service.project.WorkspaceRepositoryPathValidator;
import com.atenea.service.taskexecution.GitRepositoryService;
import org.springframework.stereotype.Service;

@Service
public class SessionOperationalSnapshotService {

    private final WorkspaceRepositoryPathValidator workspaceRepositoryPathValidator;
    private final GitRepositoryService gitRepositoryService;
    private final AgentRunRepository agentRunRepository;
    private final AgentRunReconciliationService agentRunReconciliationService;

    public SessionOperationalSnapshotService(
            WorkspaceRepositoryPathValidator workspaceRepositoryPathValidator,
            GitRepositoryService gitRepositoryService,
            AgentRunRepository agentRunRepository,
            AgentRunReconciliationService agentRunReconciliationService
    ) {
        this.workspaceRepositoryPathValidator = workspaceRepositoryPathValidator;
        this.gitRepositoryService = gitRepositoryService;
        this.agentRunRepository = agentRunRepository;
        this.agentRunReconciliationService = agentRunReconciliationService;
    }

    public SessionOperationalSnapshotResponse snapshot(WorkSessionEntity session) {
        agentRunReconciliationService.reconcileSession(session.getId());
        boolean runInProgress = agentRunRepository.existsBySessionIdAndStatus(session.getId(), AgentRunStatus.RUNNING);
        try {
            String repoPath = workspaceRepositoryPathValidator.normalizeConfiguredRepoPath(session.getProject().getRepoPath());
            String currentBranch = gitRepositoryService.getCurrentBranch(repoPath);
            boolean workingTreeClean = gitRepositoryService.isWorkingTreeClean(repoPath);
            return new SessionOperationalSnapshotResponse(true, workingTreeClean, currentBranch, runInProgress);
        } catch (RuntimeException exception) {
            return new SessionOperationalSnapshotResponse(false, false, null, runInProgress);
        }
    }
}
