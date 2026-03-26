package com.atenea.api.worksession;

import jakarta.validation.constraints.NotBlank;

public record CreateSessionTurnRequest(
        @NotBlank String message
) {
}
