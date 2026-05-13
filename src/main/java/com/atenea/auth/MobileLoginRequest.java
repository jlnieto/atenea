package com.atenea.auth;

import jakarta.validation.constraints.NotBlank;

public record MobileLoginRequest(
        @NotBlank String email,
        @NotBlank String password
) {
}
