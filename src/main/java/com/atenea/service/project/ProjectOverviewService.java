package com.atenea.service.project;

import com.atenea.api.project.ProjectOverviewResponse;
import com.atenea.api.project.ProjectOverviewResponse.WorkSessionOverviewResponse;
import com.atenea.api.project.ProjectResponse;
import com.atenea.api.worksession.SessionOperationalSnapshotResponse;
import com.atenea.persistence.project.ProjectEntity;
import com.atenea.persistence.project.ProjectRepository;
import com.atenea.persistence.worksession.WorkSessionEntity;
import com.atenea.persistence.worksession.WorkSessionRepository;
import com.atenea.persistence.worksession.WorkSessionStatus;
import com.atenea.service.worksession.SessionOperationalSnapshotService;
import java.util.List;
import java.util.Set;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProjectOverviewService {

    private static final Set<WorkSessionStatus> ACTIVE_SESSION_STATUSES =
            Set.of(WorkSessionStatus.OPEN, WorkSessionStatus.CLOSING);

    private final ProjectRepository projectRepository;
    private final WorkSessionRepository workSessionRepository;
    private final SessionOperationalSnapshotService sessionOperationalSnapshotService;

    public ProjectOverviewService(
            ProjectRepository projectRepository,
            WorkSessionRepository workSessionRepository,
            SessionOperationalSnapshotService sessionOperationalSnapshotService
    ) {
        this.projectRepository = projectRepository;
        this.workSessionRepository = workSessionRepository;
        this.sessionOperationalSnapshotService = sessionOperationalSnapshotService;
    }

    @Transactional(readOnly = true)
    public List<ProjectOverviewResponse> getOverview() {
        return projectRepository.findAll(Sort.by(Sort.Direction.ASC, "createdAt"))
                .stream()
                .map(this::toOverview)
                .toList();
    }

    private ProjectOverviewResponse toOverview(ProjectEntity project) {
        WorkSessionEntity canonicalSession = workSessionRepository.findFirstByProjectIdAndStatusInOrderByCreatedAtAsc(
                        project.getId(),
                        ACTIVE_SESSION_STATUSES)
                .or(() -> workSessionRepository.findFirstByProjectIdOrderByLastActivityAtDesc(project.getId()))
                .orElse(null);

        return new ProjectOverviewResponse(
                toProjectResponse(project),
                canonicalSession == null ? null : toWorkSessionOverview(canonicalSession)
        );
    }

    private ProjectResponse toProjectResponse(ProjectEntity project) {
        return new ProjectResponse(
                project.getId(),
                project.getName(),
                project.getDescription(),
                project.getRepoPath(),
                project.getDefaultBaseBranch(),
                project.getCreatedAt(),
                project.getUpdatedAt()
        );
    }

    private WorkSessionOverviewResponse toWorkSessionOverview(WorkSessionEntity session) {
        SessionOperationalSnapshotResponse snapshot = sessionOperationalSnapshotService.snapshot(session);
        return new WorkSessionOverviewResponse(
                session.getId(),
                session.getStatus() == WorkSessionStatus.OPEN || session.getStatus() == WorkSessionStatus.CLOSING,
                session.getStatus(),
                session.getTitle(),
                session.getBaseBranch(),
                session.getExternalThreadId(),
                session.getPullRequestUrl(),
                session.getPullRequestStatus(),
                session.getCloseBlockedState(),
                session.getCloseBlockedReason(),
                session.getCloseBlockedAction(),
                session.isCloseRetryable(),
                snapshot.repoValid(),
                snapshot.workingTreeClean(),
                snapshot.currentBranch(),
                snapshot.runInProgress(),
                session.getPublishedAt(),
                session.getOpenedAt(),
                session.getLastActivityAt(),
                session.getClosedAt()
        );
    }
}
