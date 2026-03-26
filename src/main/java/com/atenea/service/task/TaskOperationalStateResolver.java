package com.atenea.service.task;

import com.atenea.persistence.task.TaskBranchStatus;
import com.atenea.persistence.task.TaskEntity;
import com.atenea.persistence.task.TaskPullRequestStatus;
import com.atenea.persistence.task.TaskReviewOutcome;
import com.atenea.persistence.task.TaskStatus;
import java.util.List;

public final class TaskOperationalStateResolver {

    private static final List<TaskBranchStatus> PROJECT_BLOCKING_BRANCH_STATUSES = List.of(
            TaskBranchStatus.ACTIVE,
            TaskBranchStatus.REVIEW_PENDING
    );

    private TaskOperationalStateResolver() {
    }

    public static boolean isProjectBlocked(TaskEntity task) {
        return PROJECT_BLOCKING_BRANCH_STATUSES.contains(task.getBranchStatus());
    }

    public static String nextAction(
            TaskEntity task,
            boolean hasReviewableChanges,
            boolean lastExecutionFailed,
            boolean launchReady
    ) {
        if (task.getBranchStatus() == TaskBranchStatus.CLOSED) {
            return "none";
        }

        if (task.getStatus() == TaskStatus.CANCELLED) {
            if (task.getBranchStatus() == TaskBranchStatus.CLOSED) {
                return "none";
            }
            return task.getReviewOutcome() == TaskReviewOutcome.CLOSED_WITHOUT_REVIEW
                    ? "close_branch"
                    : "record_review_outcome";
        }

        return switch (task.getBranchStatus()) {
            case PLANNED -> launchReady ? "launch" : "clarify_task";
            case ACTIVE -> resolveActiveAction(hasReviewableChanges, lastExecutionFailed);
            case REVIEW_PENDING -> resolveReviewPendingAction(task, hasReviewableChanges, lastExecutionFailed);
            case CLOSED -> "none";
        };
    }

    public static String recoveryAction(
            TaskEntity task,
            boolean hasReviewableChanges,
            boolean lastExecutionFailed,
            boolean launchReady
    ) {
        if (task.getBranchStatus() == TaskBranchStatus.CLOSED) {
            return "none";
        }

        if (task.getBranchStatus() == TaskBranchStatus.PLANNED && !launchReady) {
            return "clarify_task";
        }

        if ((task.getBranchStatus() == TaskBranchStatus.ACTIVE || task.getBranchStatus() == TaskBranchStatus.REVIEW_PENDING)
                && !hasReviewableChanges) {
            return lastExecutionFailed ? "retry" : "abandon";
        }

        return "none";
    }

    public static String blockingReason(
            TaskEntity task,
            boolean projectBlocked,
            boolean hasReviewableChanges,
            boolean lastExecutionFailed
    ) {
        if (!projectBlocked) {
            return "none";
        }

        return switch (task.getBranchStatus()) {
            case PLANNED, CLOSED -> "none";
            case ACTIVE -> {
                if (!hasReviewableChanges) {
                    yield lastExecutionFailed ? "empty_branch_after_failure" : "empty_branch_without_changes";
                }
                yield "active_branch";
            }
            case REVIEW_PENDING -> {
                if (!hasReviewableChanges) {
                    yield lastExecutionFailed
                            ? "review_pending_without_changes_after_failure"
                            : "review_pending_without_changes";
                }
                if (task.getPullRequestStatus() == TaskPullRequestStatus.NOT_CREATED) {
                    yield "review_pending_without_pull_request";
                }
                yield "review_pending";
            }
        };
    }

    private static String resolveActiveAction(boolean hasReviewableChanges, boolean lastExecutionFailed) {
        if (hasReviewableChanges) {
            return "review";
        }
        return lastExecutionFailed ? "retry" : "abandon";
    }

    private static String resolveReviewPendingAction(
            TaskEntity task,
            boolean hasReviewableChanges,
            boolean lastExecutionFailed
    ) {
        if (!hasReviewableChanges) {
            return lastExecutionFailed ? "retry" : "abandon";
        }

        if (task.getPullRequestStatus() == TaskPullRequestStatus.NOT_CREATED) {
            return "create_pull_request";
        }

        return switch (task.getReviewOutcome()) {
            case PENDING -> "complete_review";
            case CHANGES_REQUESTED -> "relaunch";
            case REJECTED -> "resolve_rejection";
            case APPROVED_FOR_CLOSURE -> task.getPullRequestStatus() == TaskPullRequestStatus.MERGED
                    ? "close_branch"
                    : "merge_pull_request";
            case CLOSED_WITHOUT_REVIEW -> "close_branch";
        };
    }
}
