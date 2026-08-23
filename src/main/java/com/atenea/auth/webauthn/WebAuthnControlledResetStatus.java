package com.atenea.auth.webauthn;

import java.util.UUID;

public record WebAuthnControlledResetStatus(
        WebAuthnControlledResetState state,
        String targetProvider,
        int expectedHistoricalCredentialCount,
        Integer observedHistoricalCredentialCount,
        UUID candidateRecordId,
        String candidateLabel,
        Integer activeTotpCount,
        Integer activeRecoveryCodeCount,
        String nextAction
) {
    public static WebAuthnControlledResetStatus disabled() {
        return new WebAuthnControlledResetStatus(
                WebAuthnControlledResetState.DISABLED,
                "1Password",
                4,
                null,
                null,
                null,
                null,
                null,
                "El reinicio controlado de passkeys permanece desactivado.");
    }

    @Override
    public String toString() {
        return "WebAuthnControlledResetStatus[state=" + state
                + ",historicalCount=" + observedHistoricalCredentialCount
                + ",activeTotpCount=" + activeTotpCount
                + ",activeRecoveryCodeCount=" + activeRecoveryCodeCount + "]";
    }
}
