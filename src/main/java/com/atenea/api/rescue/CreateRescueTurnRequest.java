package com.atenea.api.rescue;

import jakarta.validation.constraints.NotBlank;

public record CreateRescueTurnRequest(
        @NotBlank String message
) {
}
