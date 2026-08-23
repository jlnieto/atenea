package com.atenea.auth.webauthn;

public record WebAuthnControlledResetResult(
        String state,
        int activePasskeyCount,
        int revokedHistoricalCount,
        int activeTotpCount,
        int activeRecoveryCodeCount,
        long credentialVersion
) {
    @Override
    public String toString() {
        return "WebAuthnControlledResetResult[state=" + state
                + ",activePasskeyCount=" + activePasskeyCount
                + ",revokedHistoricalCount=" + revokedHistoricalCount
                + ",activeTotpCount=" + activeTotpCount
                + ",activeRecoveryCodeCount=" + activeRecoveryCodeCount
                + ",credentialVersion=" + credentialVersion + "]";
    }
}
