package com.atenea.api.core;

import com.atenea.persistence.core.CoreChannel;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateCoreCommandRequest(
        @NotBlank String input,
        @NotNull CoreChannel channel,
        @Valid CoreRequestContext context,
        @Valid CoreConfirmationRequest confirmation
) {
}
