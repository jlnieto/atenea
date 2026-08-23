package com.atenea.auth.webauthn;

public enum WebAuthnProviderCategory {
    GOOGLE_PASSWORD_MANAGER("Google Password Manager", true),
    ONE_PASSWORD("1Password", true),
    HARDWARE_SECURITY_KEY("Llave física", true),
    OTHER("Otro proveedor", false),
    UNKNOWN("Proveedor desconocido", false);

    private final String sanitizedLabel;
    private final boolean establishedFailureDomain;

    WebAuthnProviderCategory(String sanitizedLabel, boolean establishedFailureDomain) {
        this.sanitizedLabel = sanitizedLabel;
        this.establishedFailureDomain = establishedFailureDomain;
    }

    public String sanitizedLabel() {
        return sanitizedLabel;
    }

    public boolean isEstablishedFailureDomain() {
        return establishedFailureDomain;
    }
}
