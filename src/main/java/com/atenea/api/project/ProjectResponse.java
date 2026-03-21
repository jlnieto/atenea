package com.atenea.api.project;

import java.time.Instant;

public record ProjectResponse(
        Long id,
        String name,
        String description,
        String repoPath,
        Instant createdAt,
        Instant updatedAt
) {
}
