package com.atenea.auth;

import jakarta.validation.constraints.NotBlank;

public record MobileLogoutRequest(
        @NotBlank String refreshToken,
        String sessionProtocolVersion,
        Boolean singleFlightRefresh
) {

    public MobileLogoutRequest(String refreshToken) {
        this(refreshToken, null, null);
    }
}
