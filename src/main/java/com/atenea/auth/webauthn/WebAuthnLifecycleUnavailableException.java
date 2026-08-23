package com.atenea.auth.webauthn;

public final class WebAuthnLifecycleUnavailableException extends RuntimeException {

    public WebAuthnLifecycleUnavailableException(String message) {
        super(message);
    }
}
