package com.atenea.auth.webauthn;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record WebAuthnCredentialVerificationRequest(
        @NotNull UUID requestId,
        @NotBlank String credentialId,
        String userHandle,
        @NotBlank String clientDataJson,
        @NotBlank String authenticatorData,
        @NotBlank String signature,
        @NotNull WebAuthnProviderCategory providerCategory
) {
    @Override
    public String toString() {
        return "WebAuthnCredentialVerificationRequest[REDACTED]";
    }
}
