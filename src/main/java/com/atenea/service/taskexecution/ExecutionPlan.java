package com.atenea.service.taskexecution;

import com.atenea.persistence.task.TaskEntity;
import com.atenea.persistence.taskexecution.TaskExecutionRunnerType;

public record ExecutionPlan(
        TaskEntity task,
        TaskExecutionRunnerType runnerType,
        String targetRepoPath,
        String prompt,
        String planningError,
        ExecutionTargetType targetType
) {
}
