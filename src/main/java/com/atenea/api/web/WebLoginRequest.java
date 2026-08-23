package com.atenea.api.web;

import jakarta.validation.constraints.NotBlank;

public record WebLoginRequest(
        @NotBlank String email,
        @NotBlank String password,
        String deviceLabel
) {
}
