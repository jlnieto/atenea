package com.atenea.api.core;

public record CoreConfirmationResponse(
        String confirmationToken,
        String message
) {
}
