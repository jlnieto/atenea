package com.atenea.auth.webauthn;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record WebAuthnCredentialInventoryItem(
        UUID recordId,
        String label,
        WebAuthnProviderCategory providerCategory,
        WebAuthnProviderProvenance provenance,
        boolean backupEligible,
        boolean backupState,
        List<String> transports,
        Instant createdAt,
        Instant lastUsedAt,
        Instant lastVerifiedAt,
        WebAuthnCredentialState state
) {
    public WebAuthnCredentialInventoryItem {
        Objects.requireNonNull(recordId, "recordId");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(providerCategory, "providerCategory");
        Objects.requireNonNull(provenance, "provenance");
        Objects.requireNonNull(transports, "transports");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(state, "state");
        transports = List.copyOf(transports);
    }

    @Override
    public String toString() {
        return "WebAuthnCredentialInventoryItem[recordId=" + recordId
                + ",label=" + label + ",state=" + state + "]";
    }
}
