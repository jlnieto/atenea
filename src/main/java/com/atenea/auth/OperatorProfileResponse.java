package com.atenea.auth;

public record OperatorProfileResponse(
        Long id,
        String email,
        String displayName
) {
}
