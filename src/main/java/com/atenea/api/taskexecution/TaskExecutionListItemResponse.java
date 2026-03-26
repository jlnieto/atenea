package com.atenea.api.taskexecution;

import com.atenea.persistence.task.TaskBranchStatus;
import com.atenea.persistence.task.TaskPullRequestStatus;
import com.atenea.persistence.task.TaskReviewOutcome;
import com.atenea.persistence.taskexecution.TaskExecutionRunnerType;
import com.atenea.persistence.taskexecution.TaskExecutionStatus;
import java.time.Instant;

public record TaskExecutionListItemResponse(
        Long id,
        Long taskId,
        String taskTitle,
        Long projectId,
        String projectName,
        TaskBranchStatus branchStatus,
        TaskPullRequestStatus pullRequestStatus,
        TaskReviewOutcome reviewOutcome,
        boolean projectBlocked,
        boolean hasReviewableChanges,
        boolean lastExecutionFailed,
        boolean launchReady,
        String launchReadinessReason,
        String blockingReason,
        String nextAction,
        String recoveryAction,
        TaskExecutionStatus status,
        TaskExecutionRunnerType runnerType,
        String targetRepoPath,
        String outputSummary,
        String errorSummary,
        String externalThreadId,
        String externalTurnId,
        Instant startedAt,
        Instant finishedAt,
        Instant createdAt
) {
}
