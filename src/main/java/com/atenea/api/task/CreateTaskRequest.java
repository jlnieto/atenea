package com.atenea.api.task;

import com.atenea.persistence.task.TaskPriority;
import com.atenea.persistence.task.TaskStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateTaskRequest(
        @NotBlank @Size(max = 150) String title,
        String description,
        @Size(max = 120) String baseBranch,
        @NotNull TaskStatus status,
        @NotNull TaskPriority priority
) {
}
