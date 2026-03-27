package com.atenea.api.project;

import com.atenea.api.task.TaskResponse;
import com.atenea.api.taskexecution.TaskExecutionResponse;
import com.atenea.persistence.worksession.WorkSessionStatus;
import java.time.Instant;

public record ProjectOverviewResponse(
        ProjectResponse project,
        WorkSessionOverviewResponse workSession,
        LegacyProjectOverviewResponse legacy
) {

    public record WorkSessionOverviewResponse(
            Long sessionId,
            boolean current,
            WorkSessionStatus status,
            String title,
            String baseBranch,
            String externalThreadId,
            boolean repoValid,
            boolean workingTreeClean,
            String currentBranch,
            boolean runInProgress,
            Instant openedAt,
            Instant lastActivityAt,
            Instant closedAt
    ) {
    }

    public record LegacyProjectOverviewResponse(
            TaskResponse latestTask,
            TaskExecutionResponse latestExecution
    ) {
    }
}
