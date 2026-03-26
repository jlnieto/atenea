package com.atenea.api.worksession;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateWorkSessionRequest(
        @NotBlank @Size(max = 200) String title,
        @Size(max = 120) String baseBranch
) {
}
