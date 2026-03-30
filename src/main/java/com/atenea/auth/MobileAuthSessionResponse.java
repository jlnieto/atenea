package com.atenea.auth;

import java.time.Instant;

public record MobileAuthSessionResponse(
        String accessToken,
        Instant accessTokenExpiresAt,
        String refreshToken,
        Instant refreshTokenExpiresAt,
        OperatorProfileResponse operator
) {
}
