package com.atenea.delivery;

import com.atenea.auth.AuthenticatedSession;
import com.atenea.auth.OperatorAuthenticationException;
import com.atenea.auth.action.PrivilegedActionAuthorizationGrant;
import com.atenea.auth.action.PrivilegedActionAuthorizationService;
import com.atenea.auth.webauthn.WebAuthnAuthenticationRequest;
import com.atenea.auth.webauthn.WebAuthnChannel;
import com.atenea.auth.webauthn.WebAuthnOptionsResponse;
import com.atenea.auth.webauthn.WebAuthnService;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Verification issues a one-use grant; it never confirms or executes a publication. */
@Service
public class MobileReleasePasskeyService {
    private final MobileDeliveryService delivery;
    private final WebAuthnService passkeys;
    private final PrivilegedActionAuthorizationService grants;
    private final ReleasePasskeyTrustStore trust;

    public MobileReleasePasskeyService(MobileDeliveryService delivery, WebAuthnService passkeys,
            PrivilegedActionAuthorizationService grants, ReleasePasskeyTrustStore trust) {
        this.delivery = delivery;
        this.passkeys = passkeys;
        this.grants = grants;
        this.trust = trust;
    }

    public WebAuthnOptionsResponse options(UUID id, AuthenticatedSession actor, String origin) {
        DeliveryOperation plan = delivery.authorizationPlan(id, actor);
        var trusted = trust.trustedCredentialIds(actor.operator().operatorId());
        if (trusted.isEmpty()) throw new DeliveryRejectedException("PUBLICATION_PASSKEY_SETUP_REQUIRED");
        try {
            var options = passkeys.beginStepUp(actor.operator(), actor.sessionFamilyId(),
                    WebAuthnChannel.ANDROID, origin, delivery.binding(plan));
            var allowed = options.credentials().stream().filter(value -> trusted.contains(value.id())).toList();
            if (allowed.isEmpty()) throw new DeliveryRejectedException("PUBLICATION_PASSKEY_SETUP_REQUIRED");
            return new WebAuthnOptionsResponse(options.requestId(), options.challenge(), options.timeoutMillis(),
                    options.relyingPartyId(), null, null, null, java.util.List.of(), allowed, "required", null, null);
        } catch (OperatorAuthenticationException rejected) {
            throw new DeliveryRejectedException("PUBLICATION_PASSKEY_UNAVAILABLE");
        }
    }

    public PrivilegedActionAuthorizationGrant authorize(UUID id, AuthenticatedSession actor,
            String origin, WebAuthnAuthenticationRequest assertion) {
        DeliveryOperation plan = delivery.authorizationPlan(id, actor);
        if (assertion == null || !trust.isTrusted(actor.operator().operatorId(), assertion.credentialId())) {
            throw new DeliveryRejectedException("PUBLICATION_PASSKEY_SETUP_REQUIRED");
        }
        try {
            return grants.issueVerified(passkeys.completeStepUp(actor.operator(), actor.sessionFamilyId(),
                    WebAuthnChannel.ANDROID, origin, delivery.binding(plan), assertion));
        } catch (OperatorAuthenticationException rejected) {
            throw new DeliveryRejectedException("PUBLICATION_FACTOR_REJECTED");
        }
    }
}
