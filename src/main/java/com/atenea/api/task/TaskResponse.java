package com.atenea.api.task;

import com.atenea.persistence.task.TaskPriority;
import com.atenea.persistence.task.TaskStatus;
import java.time.Instant;

public record TaskResponse(
        Long id,
        Long projectId,
        String title,
        String description,
        TaskStatus status,
        TaskPriority priority,
        Instant createdAt,
        Instant updatedAt
) {
}
