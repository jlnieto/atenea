package com.atenea.auth.webauthn;

import java.util.List;

public record WebAuthnCredentialInventoryResponse(
        String state,
        List<WebAuthnCredentialInventoryItem> credentials,
        List<WebAuthnProviderCategory> requiredProviderDomains,
        List<WebAuthnProviderCategory> verifiedProviderDomains,
        boolean independentDomainsReady,
        boolean signallingEnabled,
        boolean readOnly,
        String nextAction
) {
    public WebAuthnCredentialInventoryResponse {
        credentials = List.copyOf(credentials);
        requiredProviderDomains = List.copyOf(requiredProviderDomains);
        verifiedProviderDomains = List.copyOf(verifiedProviderDomains);
    }

    public static WebAuthnCredentialInventoryResponse disabled() {
        return new WebAuthnCredentialInventoryResponse(
                "DISABLED",
                List.of(),
                List.of(
                        WebAuthnProviderCategory.GOOGLE_PASSWORD_MANAGER,
                        WebAuthnProviderCategory.ONE_PASSWORD),
                List.of(),
                false,
                false,
                false,
                "El inventario correctivo permanece desactivado hasta su puerta de rollout.");
    }

    @Override
    public String toString() {
        return "WebAuthnCredentialInventoryResponse[state=" + state
                + ",credentialCount=" + credentials.size()
                + ",readOnly=" + readOnly
                + ",independentDomainsReady=" + independentDomainsReady + "]";
    }
}
