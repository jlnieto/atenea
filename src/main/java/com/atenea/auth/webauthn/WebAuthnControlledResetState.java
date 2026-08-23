package com.atenea.auth.webauthn;

public enum WebAuthnControlledResetState {
    DISABLED,
    BLOCKED,
    REGISTER_NEW,
    PROVE_NEW,
    COMMIT_READY,
    COMPLETE
}
