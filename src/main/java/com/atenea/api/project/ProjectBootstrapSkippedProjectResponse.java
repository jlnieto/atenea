package com.atenea.api.project;

public record ProjectBootstrapSkippedProjectResponse(
        String name,
        String description,
        String repoPath,
        String reason
) {
}
