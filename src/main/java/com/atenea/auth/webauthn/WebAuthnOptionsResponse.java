package com.atenea.auth.webauthn;

import java.util.List;
import java.util.UUID;

public record WebAuthnOptionsResponse(
        UUID requestId,
        String challenge,
        long timeoutMillis,
        String relyingPartyId,
        String relyingPartyName,
        String userHandle,
        String opaqueUserName,
        List<CredentialParameter> credentialParameters,
        List<CredentialDescriptor> credentials,
        String userVerification,
        String residentKey,
        String attestation
) {
    @Override
    public String toString() {
        return "WebAuthnOptionsResponse[REDACTED]";
    }

    public record CredentialParameter(String type, int algorithm) {
    }

    public record CredentialDescriptor(String type, String id, List<String> transports) {
        @Override
        public String toString() {
            return "CredentialDescriptor[REDACTED]";
        }
    }
}
