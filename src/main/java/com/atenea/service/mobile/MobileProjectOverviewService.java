package com.atenea.service.mobile;

import com.atenea.api.mobile.MobileProjectOverviewResponse;
import com.atenea.api.project.ProjectOverviewResponse;
import com.atenea.service.project.ProjectOverviewService;
import com.atenea.persistence.worksession.WorkSessionRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MobileProjectOverviewService {

    private final ProjectOverviewService projectOverviewService;
    private final WorkSessionRepository sessions;

    public MobileProjectOverviewService(
            ProjectOverviewService projectOverviewService,
            WorkSessionRepository sessions
    ) {
        this.projectOverviewService = projectOverviewService;
        this.sessions = sessions;
    }

    @Transactional(readOnly = true)
    public List<MobileProjectOverviewResponse> getOverview() {
        return projectOverviewService.getOverview()
                .stream()
                .map(this::toMobileOverview)
                .toList();
    }

    private MobileProjectOverviewResponse toMobileOverview(ProjectOverviewResponse response) {
        ProjectOverviewResponse.WorkSessionOverviewResponse session = response.workSession();
        ProjectOverviewResponse.WorkSessionOverviewResponse visibleSession =
                session != null && session.current()
                && sessions.findWithProjectAndDevelopmentChangeById(session.sessionId())
                    .filter(owned -> owned.getDevelopmentChange() != null
                        && owned.getDevelopmentChange().getProject() != null
                        && response.project().id().equals(owned.getDevelopmentChange().getProject().getId()))
                    .isPresent() ? session : null;
        return new MobileProjectOverviewResponse(
                response.project().id(),
                response.project().name(),
                response.project().description(),
                response.project().defaultBaseBranch(),
                visibleSession == null ? null : new MobileProjectOverviewResponse.MobileProjectSessionSummaryResponse(
                        visibleSession.sessionId(),
                        visibleSession.status(),
                        visibleSession.title(),
                        visibleSession.runInProgress(),
                        visibleSession.closeBlockedState(),
                        visibleSession.pullRequestStatus(),
                        visibleSession.lastActivityAt(),
                        false)
        );
    }
}
