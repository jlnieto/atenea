package com.atenea.api.project;

import com.atenea.api.task.TaskResponse;
import com.atenea.api.taskexecution.TaskExecutionResponse;

public record ProjectOverviewResponse(
        ProjectResponse project,
        TaskResponse latestTask,
        TaskExecutionResponse latestExecution
) {
}
