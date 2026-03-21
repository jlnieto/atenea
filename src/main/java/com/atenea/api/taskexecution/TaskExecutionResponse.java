package com.atenea.api.taskexecution;

import com.atenea.persistence.taskexecution.TaskExecutionRunnerType;
import com.atenea.persistence.taskexecution.TaskExecutionStatus;
import java.time.Instant;

public record TaskExecutionResponse(
        Long id,
        Long taskId,
        TaskExecutionStatus status,
        TaskExecutionRunnerType runnerType,
        Instant startedAt,
        Instant finishedAt,
        String outputSummary,
        String errorSummary,
        Instant createdAt
) {
}
