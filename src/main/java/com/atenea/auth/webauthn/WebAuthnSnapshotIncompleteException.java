package com.atenea.auth.webauthn;

public final class WebAuthnSnapshotIncompleteException extends RuntimeException {

    public WebAuthnSnapshotIncompleteException() {
        super("El inventario de passkeys no es completo; no se enviará ninguna señal.");
    }
}
