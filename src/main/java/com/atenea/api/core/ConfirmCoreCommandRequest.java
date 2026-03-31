package com.atenea.api.core;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ConfirmCoreCommandRequest(
        @NotBlank @Size(max = 120) String confirmationToken
) {
}
