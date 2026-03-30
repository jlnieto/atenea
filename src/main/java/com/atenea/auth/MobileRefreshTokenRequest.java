package com.atenea.auth;

import jakarta.validation.constraints.NotBlank;

public record MobileRefreshTokenRequest(
        @NotBlank String refreshToken
) {
}
