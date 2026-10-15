package com.atenea.delivery;

import com.atenea.auth.AuthenticatedSession;
import com.atenea.auth.OperatorAuthenticationException;
import com.atenea.auth.OperatorAuthProperties;
import com.atenea.auth.action.PrivilegedActionAuthorizationService;
import com.atenea.auth.action.PrivilegedActionBinding;
import com.atenea.auth.recovery.OperatorRecoveryService;
import com.atenea.auth.webauthn.*;
import com.atenea.persistence.auth.CodexOperationsRole;
import com.atenea.persistence.auth.OperatorRepository;
import com.atenea.persistence.auth.OperatorWebAuthnCredentialRepository;
import java.util.Base64;
import org.springframework.stereotype.Service;

@Service
public class ReleasePasskeySetupService {
    private final OperatorRepository operators;
    private final OperatorWebAuthnCredentialRepository credentials;
    private final WebAuthnService passkeys;
    private final OperatorRecoveryService factors;
    private final PrivilegedActionAuthorizationService grants;
    private final ReleasePasskeyTrustStore trust;
    private final OperatorAuthProperties properties;

    public ReleasePasskeySetupService(OperatorRepository operators, OperatorWebAuthnCredentialRepository credentials,
            WebAuthnService passkeys, OperatorRecoveryService factors,
            PrivilegedActionAuthorizationService grants, ReleasePasskeyTrustStore trust, OperatorAuthProperties properties) {
        this.operators=operators; this.credentials=credentials; this.passkeys=passkeys;
        this.factors=factors; this.grants=grants; this.trust=trust; this.properties=properties;
    }

    public Status status(AuthenticatedSession actor, String origin) {
        administrator(actor);
        var config=properties.getWebAuthn();
        boolean enabled=config.isEnabled() && properties.getPrivilegedActions().isEnabled()
                && config.getAndroidOrigins()!=null && config.getAndroidOrigins().contains(origin);
        boolean registered=credentials.countByOperatorIdAndRevokedAtIsNull(actor.operator().operatorId())>0;
        boolean configured=enabled && !trust.trustedCredentialIds(actor.operator().operatorId()).isEmpty();
        return new Status(enabled,registered,configured,enabled && registered && !configured);
    }

    public record Status(boolean enabled, boolean hasRegisteredPasskey, boolean configured, boolean setupRequired) { }

    public WebAuthnOptionsResponse options(AuthenticatedSession actor, String origin) {
        administrator(actor);
        try { return passkeys.beginStepUp(actor.operator(), actor.sessionFamilyId(),
                WebAuthnChannel.ANDROID, origin, setupBinding(actor)); }
        catch (OperatorAuthenticationException rejected) { throw new DeliveryRejectedException("PUBLICATION_PASSKEY_UNAVAILABLE"); }
    }

    public void approve(AuthenticatedSession actor, String origin, String totp, WebAuthnAuthenticationRequest assertion) {
        administrator(actor);
        try {
            // Proof of possession/UV of the exact chosen key, not a caller's claim of a fingerprint.
            passkeys.completeStepUp(actor.operator(), actor.sessionFamilyId(), WebAuthnChannel.ANDROID,
                    origin, setupBinding(actor), assertion);
            byte[] credentialId = Base64.getUrlDecoder().decode(assertion.credentialId());
            var credential = credentials.findByCredentialId(credentialId)
                    .filter(value -> value.getRevokedAt()==null && value.getOperator().getId().equals(actor.operator().operatorId()))
                    .orElseThrow(() -> new OperatorAuthenticationException("Passkey trust rejected"));
            String fingerprint = ReleasePasskeyTrustStore.fingerprint(credential);
            var binding = PrivilegedActionBinding.fromCanonical("TRUST_RELEASE_PASSKEY",
                    "credential="+credential.getId(), "release-passkey-trust-v1|"+fingerprint);
            var grant = grants.issueVerified(factors.verifyTotpStepUp(actor.operator(), actor.sessionFamilyId(), binding, totp));
            grants.consumeForAcceptance(grant.authorization(), actor, binding, () -> {
                var retained = credentials.findByCredentialIdForUpdate(credentialId)
                        .filter(value -> value.getRevokedAt()==null && value.getOperator().getId().equals(actor.operator().operatorId())
                                && fingerprint.equals(ReleasePasskeyTrustStore.fingerprint(value)))
                        .orElseThrow(() -> new OperatorAuthenticationException("Passkey trust rejected"));
                trust.approve(retained, grant.authorization(), assertion.requestId());
                return true;
            });
        } catch (OperatorAuthenticationException | IllegalArgumentException rejected) {
            throw new DeliveryRejectedException("PUBLICATION_FACTOR_REJECTED");
        }
    }

    private PrivilegedActionBinding setupBinding(AuthenticatedSession actor) {
        return PrivilegedActionBinding.fromCanonical("SETUP_RELEASE_PASSKEY",
                "operator="+actor.operator().operatorId(), "release-passkey-trust-v1");
    }

    private void administrator(AuthenticatedSession actor) {
        operators.findById(actor.operator().operatorId()).filter(value -> value.isActive()
                && value.getCodexOperationsRole()==CodexOperationsRole.PLATFORM_ADMINISTRATOR)
                .orElseThrow(() -> new DeliveryRejectedException("PLATFORM_ADMINISTRATOR_REQUIRED"));
    }
}
