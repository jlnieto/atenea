package com.atenea.api.task;

import com.atenea.persistence.task.TaskPriority;
import com.atenea.persistence.task.TaskBranchStatus;
import com.atenea.persistence.task.TaskPullRequestStatus;
import com.atenea.persistence.task.TaskReviewOutcome;
import com.atenea.persistence.task.TaskStatus;
import java.time.Instant;

public record TaskResponse(
        Long id,
        Long projectId,
        String title,
        String description,
        String baseBranch,
        String branchName,
        TaskBranchStatus branchStatus,
        String pullRequestUrl,
        TaskPullRequestStatus pullRequestStatus,
        TaskReviewOutcome reviewOutcome,
        String reviewNotes,
        boolean projectBlocked,
        boolean hasReviewableChanges,
        boolean lastExecutionFailed,
        boolean launchReady,
        String launchReadinessReason,
        String blockingReason,
        String nextAction,
        String recoveryAction,
        TaskStatus status,
        TaskPriority priority,
        Instant createdAt,
        Instant updatedAt
) {
}
