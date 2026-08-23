package com.atenea.auth.webauthn;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record WebAuthnCredentialVerificationStartRequest(
        @NotNull UUID recordId
) {
    @Override
    public String toString() {
        return "WebAuthnCredentialVerificationStartRequest[REDACTED]";
    }
}
