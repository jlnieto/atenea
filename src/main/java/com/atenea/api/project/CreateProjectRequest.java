package com.atenea.api.project;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateProjectRequest(
        @NotBlank String name,
        String description,
        @NotBlank String repoPath,
        @Size(max = 120) String defaultBaseBranch
) {
}
