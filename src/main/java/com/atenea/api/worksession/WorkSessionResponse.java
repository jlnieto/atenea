package com.atenea.api.worksession;

import com.atenea.persistence.worksession.WorkSessionStatus;
import com.atenea.persistence.worksession.WorkSessionPullRequestStatus;
import com.atenea.persistence.worksession.ExecutionTarget;
import com.atenea.persistence.worksession.AgentRunRecoveryNextAction;
import com.atenea.persistence.worksession.RemoteCloseState;
import com.atenea.persistence.developmentchange.DevelopmentChangeProjectionState;
import com.atenea.persistence.developmentchange.DevelopmentChangeSourceState;
import java.time.Instant;
import java.util.UUID;

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
        SessionOperationalSnapshotResponse repoState,
        RemoteCloseState remoteCloseState,
        String remoteCloseErrorCode,
        AgentRunRecoveryNextAction remoteCloseNextAction,
        UUID developmentChangeKey,
        DevelopmentChangeProjectionState developmentChangeValidationState,
        DevelopmentChangeSourceState developmentChangeSourceState
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
            ExecutionTarget executionTarget,
            String selectedWorkerId,
            String workspaceIdentity,
            SessionOperationalSnapshotResponse repoState,
            RemoteCloseState remoteCloseState,
            String remoteCloseErrorCode,
            AgentRunRecoveryNextAction remoteCloseNextAction
    ) {
        this(
                id, projectId, status, operationalState, title, baseBranch,
                workspaceBranch, externalThreadId, pullRequestUrl,
                pullRequestStatus, finalCommitSha, openedAt, lastActivityAt,
                publishedAt, closedAt, closeBlockedState, closeBlockedReason,
                closeBlockedAction, closeRetryable, executionTarget,
                selectedWorkerId, workspaceIdentity, repoState, remoteCloseState,
                remoteCloseErrorCode, remoteCloseNextAction, null, null, null);
    }

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
            ExecutionTarget executionTarget,
            String selectedWorkerId,
            String workspaceIdentity,
            SessionOperationalSnapshotResponse repoState
    ) {
        this(
                id, projectId, status, operationalState, title, baseBranch,
                workspaceBranch, externalThreadId, pullRequestUrl,
                pullRequestStatus, finalCommitSha, openedAt, lastActivityAt,
                publishedAt, closedAt, closeBlockedState, closeBlockedReason,
                closeBlockedAction, closeRetryable, executionTarget,
                selectedWorkerId, workspaceIdentity, repoState,
                legacyState(executionTarget, status), null,
                AgentRunRecoveryNextAction.NONE, null, null, null);
    }

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
                id == null ? null : "local:work-session:" + id, repoState,
                RemoteCloseState.NOT_REQUIRED, null, AgentRunRecoveryNextAction.NONE,
                null, null, null);
    }

    private static RemoteCloseState legacyState(
            ExecutionTarget executionTarget,
            WorkSessionStatus status
    ) {
        if (executionTarget != ExecutionTarget.REMOTE) {
            return RemoteCloseState.NOT_REQUIRED;
        }
        return status == WorkSessionStatus.CLOSED
                ? RemoteCloseState.UNVERIFIED_LEGACY
                : RemoteCloseState.NOT_STARTED;
    }
}
