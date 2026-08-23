package com.atenea.auth.webauthn;

import com.atenea.auth.OperatorAuthenticationException;

public final class WebAuthnCredentialNotAcceptedException
        extends OperatorAuthenticationException {

    public WebAuthnCredentialNotAcceptedException() {
        super("WebAuthn ceremony rejected");
    }
}
