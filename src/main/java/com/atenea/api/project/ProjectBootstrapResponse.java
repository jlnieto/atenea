package com.atenea.api.project;

import java.util.List;

public record ProjectBootstrapResponse(
        List<ProjectResponse> createdProjects,
        List<ProjectResponse> existingProjects,
        List<ProjectBootstrapSkippedProjectResponse> skippedProjects
) {
}
