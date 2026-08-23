package com.atenea.auth.webauthn;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Set;
import java.util.UUID;

public record WebAuthnRegistrationRequest(
        @NotNull UUID requestId,
        @NotBlank String credentialId,
        @NotBlank String clientDataJson,
        @NotBlank String attestationObject,
        Set<String> transports
) {
    @Override
    public String toString() {
        return "WebAuthnRegistrationRequest[REDACTED]";
    }
}
