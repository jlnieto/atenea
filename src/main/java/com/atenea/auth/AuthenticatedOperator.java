package com.atenea.auth;

public record AuthenticatedOperator(
        Long operatorId,
        String email,
        String displayName
) {
}
