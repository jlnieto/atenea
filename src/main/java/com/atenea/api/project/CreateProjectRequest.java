package com.atenea.api.project;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateProjectRequest(
        @NotBlank @Size(max = 150) String name,
        String description,
        @Size(max = 500) String repoPath
) {
}
