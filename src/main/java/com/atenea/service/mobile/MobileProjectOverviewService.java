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
    private final FreshWorkSessionService freshWorkSessionService;

    public MobileProjectOverviewService(
            ProjectOverviewService projectOverviewService,
            FreshWorkSessionService freshWorkSessionService
    ) {
        this.projectOverviewService = projectOverviewService;
        this.freshWorkSessionService = freshWorkSessionService;
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
        boolean recoveryPending = session != null
                && !session.current()
                && freshWorkSessionService.hasIncompleteOperationForSource(session.sessionId());
        ProjectOverviewResponse.WorkSessionOverviewResponse visibleSession =
                session != null && (session.current() || recoveryPending) ? session : null;
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
                        recoveryPending)
        );
    }
}
