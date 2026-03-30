package com.atenea.api.mobile;

import com.atenea.persistence.worksession.WorkSessionPullRequestStatus;
import com.atenea.persistence.worksession.WorkSessionStatus;
import java.time.Instant;

public record MobileProjectOverviewResponse(
        Long projectId,
        String projectName,
        String description,
        String defaultBaseBranch,
        MobileProjectSessionSummaryResponse session
) {

    public record MobileProjectSessionSummaryResponse(
            Long sessionId,
            WorkSessionStatus status,
            String title,
            boolean runInProgress,
            String closeBlockedState,
            WorkSessionPullRequestStatus pullRequestStatus,
            Instant lastActivityAt
    ) {
    }
}
