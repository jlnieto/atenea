package com.atenea.auth.webauthn;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import java.util.UUID;

public record WebAuthnAuthenticationRequest(
        @NotNull UUID requestId,
        @NotBlank String credentialId,
        String userHandle,
        @NotBlank String clientDataJson,
        @NotBlank String authenticatorData,
        @NotBlank String signature,
        String sessionProtocolVersion,
        Boolean singleFlightRefresh,
        String clientType,
        String deviceLabel
) {
    @Override
    public String toString() {
        return "WebAuthnAuthenticationRequest[REDACTED]";
    }
}
