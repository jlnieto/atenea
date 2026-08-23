package com.atenea.api.web;

import com.atenea.auth.MobileAuthSessionResponse;
import com.atenea.auth.OperatorProfileResponse;
import java.time.Instant;

public record WebAuthSessionResponse(
        String accessToken,
        Instant accessTokenExpiresAt,
        OperatorProfileResponse operator
) {

    public static WebAuthSessionResponse from(MobileAuthSessionResponse session) {
        return new WebAuthSessionResponse(
                session.accessToken(),
                session.accessTokenExpiresAt(),
                session.operator());
    }
}
