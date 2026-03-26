package com.atenea.api.task;

import com.atenea.persistence.task.TaskPullRequestStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record UpdateTaskPullRequestRequest(
        @Size(max = 500) String pullRequestUrl,
        @NotNull TaskPullRequestStatus pullRequestStatus
) {
}
