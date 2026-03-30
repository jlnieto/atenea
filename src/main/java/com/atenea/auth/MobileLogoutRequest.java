package com.atenea.auth;

import jakarta.validation.constraints.NotBlank;

public record MobileLogoutRequest(
        @NotBlank String refreshToken
) {
}
