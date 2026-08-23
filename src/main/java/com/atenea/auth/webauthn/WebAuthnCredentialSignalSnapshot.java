package com.atenea.auth.webauthn;

import java.util.List;

public record WebAuthnCredentialSignalSnapshot(
        String relyingPartyId,
        String userId,
        List<String> allAcceptedCredentialIds,
        int activeCredentialCount,
        long credentialVersion
) {
    public WebAuthnCredentialSignalSnapshot {
        allAcceptedCredentialIds = List.copyOf(allAcceptedCredentialIds);
        if (activeCredentialCount < 1
                || activeCredentialCount != allAcceptedCredentialIds.size()
                || allAcceptedCredentialIds.stream().distinct().count()
                        != activeCredentialCount) {
            throw new IllegalArgumentException("Incomplete credential signal snapshot");
        }
    }

    @Override
    public String toString() {
        return "WebAuthnCredentialSignalSnapshot[REDACTED,count="
                + activeCredentialCount + ",version=" + credentialVersion + "]";
    }
}
