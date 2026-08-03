package com.atenea.api.worksession;

import com.atenea.persistence.worksession.WorkSessionStatus;
import com.atenea.persistence.worksession.WorkSessionPullRequestStatus;
import com.atenea.persistence.worksession.ExecutionTarget;
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
        ExecutionTarget executionTarget,
        String selectedWorkerId,
        String workspaceIdentity,
        SessionOperationalSnapshotResponse repoState
) {
    public WorkSessionResponse(
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
        this(
                id, projectId, status, operationalState, title, baseBranch,
                workspaceBranch, externalThreadId, pullRequestUrl,
                pullRequestStatus, finalCommitSha, openedAt, lastActivityAt,
                publishedAt, closedAt, closeBlockedState, closeBlockedReason,
                closeBlockedAction, closeRetryable, ExecutionTarget.LOCAL, null,
                id == null ? null : "local:work-session:" + id, repoState);
    }
}
