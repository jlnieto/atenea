package com.atenea.api.core;

import jakarta.validation.constraints.Size;

public record CoreConfirmationRequest(
        boolean confirmed,
        @Size(max = 120) String confirmationToken
) {
}
