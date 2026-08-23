package com.atenea.auth.webauthn;

import java.time.Instant;
import java.util.UUID;

public record WebAuthnCredentialVerificationResponse(
        UUID recordId,
        String label,
        WebAuthnProviderCategory providerCategory,
        Instant verifiedAt
) {
}
