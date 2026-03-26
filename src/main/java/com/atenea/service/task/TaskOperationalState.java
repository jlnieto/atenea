package com.atenea.service.task;

public record TaskOperationalState(
        boolean projectBlocked,
        boolean hasReviewableChanges,
        boolean lastExecutionFailed,
        boolean launchReady,
        String launchReadinessReason,
        String blockingReason,
        String nextAction,
        String recoveryAction
) {
}
