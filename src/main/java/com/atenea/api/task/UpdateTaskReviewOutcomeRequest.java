package com.atenea.api.task;

import com.atenea.persistence.task.TaskReviewOutcome;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record UpdateTaskReviewOutcomeRequest(
        @NotNull TaskReviewOutcome reviewOutcome,
        @Size(max = 1000) String reviewNotes
) {
}
