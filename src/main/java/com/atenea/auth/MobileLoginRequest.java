package com.atenea.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record MobileLoginRequest(
        @NotBlank @Email String email,
        @NotBlank String password
) {
}
