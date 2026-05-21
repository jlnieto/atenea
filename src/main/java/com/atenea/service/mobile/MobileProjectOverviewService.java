package com.atenea.service.mobile;

import com.atenea.api.mobile.MobileProjectOverviewResponse;
import com.atenea.api.project.ProjectOverviewResponse;
import com.atenea.service.project.ProjectOverviewService;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MobileProjectOverviewService {

    private final ProjectOverviewService projectOverviewService;

    public MobileProjectOverviewService(ProjectOverviewService projectOverviewService) {
        this.projectOverviewService = projectOverviewService;
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
        ProjectOverviewResponse.WorkSessionOverviewResponse activeSession =
                session != null && session.current() ? session : null;
        return new MobileProjectOverviewResponse(
                response.project().id(),
                response.project().name(),
                response.project().description(),
                response.project().defaultBaseBranch(),
                activeSession == null ? null : new MobileProjectOverviewResponse.MobileProjectSessionSummaryResponse(
                        activeSession.sessionId(),
                        activeSession.status(),
                        activeSession.title(),
                        activeSession.runInProgress(),
                        activeSession.closeBlockedState(),
                        activeSession.pullRequestStatus(),
                        activeSession.lastActivityAt())
        );
    }
}
