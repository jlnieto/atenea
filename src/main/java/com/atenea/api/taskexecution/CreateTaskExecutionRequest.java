package com.atenea.api.taskexecution;

import com.atenea.persistence.taskexecution.TaskExecutionRunnerType;
import com.atenea.persistence.taskexecution.TaskExecutionStatus;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

public record CreateTaskExecutionRequest(
        @NotNull TaskExecutionStatus status,
        @NotNull TaskExecutionRunnerType runnerType,
        Instant startedAt,
        Instant finishedAt,
        String outputSummary,
        String errorSummary
) {
}
