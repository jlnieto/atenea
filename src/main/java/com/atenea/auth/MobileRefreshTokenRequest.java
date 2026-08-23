package com.atenea.auth;

import jakarta.validation.constraints.NotBlank;

public record MobileRefreshTokenRequest(
        @NotBlank String refreshToken,
        String clientType,
        String deviceLabel,
        String sessionProtocolVersion,
        Boolean singleFlightRefresh
) {

    public MobileRefreshTokenRequest(String refreshToken) {
        this(refreshToken, null, null, null, null);
    }
}
