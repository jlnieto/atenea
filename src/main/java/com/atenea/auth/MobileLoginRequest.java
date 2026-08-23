package com.atenea.auth;

import jakarta.validation.constraints.NotBlank;

public record MobileLoginRequest(
        @NotBlank String email,
        @NotBlank String password,
        String clientType,
        String deviceLabel,
        String sessionProtocolVersion,
        Boolean singleFlightRefresh
) {

    public MobileLoginRequest(String email, String password) {
        this(email, password, null, null, null, null);
    }
}
