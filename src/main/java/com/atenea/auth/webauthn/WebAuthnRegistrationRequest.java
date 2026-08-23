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
        Set<String> transports,
        WebAuthnProviderCategory providerCategory
) {
    public WebAuthnRegistrationRequest(
            UUID requestId,
            String credentialId,
            String clientDataJson,
            String attestationObject,
            Set<String> transports
    ) {
        this(requestId, credentialId, clientDataJson, attestationObject, transports, null);
    }

    @Override
    public String toString() {
        return "WebAuthnRegistrationRequest[REDACTED]";
    }
}
