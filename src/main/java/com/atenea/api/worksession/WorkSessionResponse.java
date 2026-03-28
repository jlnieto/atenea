package com.atenea.api.worksession;

import com.atenea.persistence.worksession.WorkSessionStatus;
import com.atenea.persistence.worksession.WorkSessionPullRequestStatus;
import java.time.Instant;

public record WorkSessionResponse(
        Long id,
        Long projectId,
        WorkSessionStatus status,
        WorkSessionOperationalState operationalState,
        String title,
        String baseBranch,
        String workspaceBranch,
        String externalThreadId,
        String pullRequestUrl,
        WorkSessionPullRequestStatus pullRequestStatus,
        String finalCommitSha,
        Instant openedAt,
        Instant lastActivityAt,
        Instant publishedAt,
        Instant closedAt,
        String closeBlockedState,
        String closeBlockedReason,
        String closeBlockedAction,
        boolean closeRetryable,
        SessionOperationalSnapshotResponse repoState
) {
}
