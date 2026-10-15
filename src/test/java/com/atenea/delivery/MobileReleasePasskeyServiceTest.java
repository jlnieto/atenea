package com.atenea.delivery;

import com.atenea.auth.AuthenticatedOperator;
import com.atenea.auth.AuthenticatedSession;
import com.atenea.auth.OperatorAuthenticationException;
import com.atenea.auth.action.*;
import com.atenea.auth.webauthn.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class MobileReleasePasskeyServiceTest {
    private final MobileDeliveryService delivery = mock(MobileDeliveryService.class);
    private final WebAuthnService passkeys = mock(WebAuthnService.class);
    private final PrivilegedActionAuthorizationService grants = mock(PrivilegedActionAuthorizationService.class);
    private final ReleasePasskeyTrustStore trust = mock(ReleasePasskeyTrustStore.class);
    private final MobileReleasePasskeyService service = new MobileReleasePasskeyService(delivery, passkeys, grants, trust);
    private final UUID id = UUID.randomUUID();
    private final AuthenticatedSession actor = new AuthenticatedSession(new AuthenticatedOperator(7L, "test@atenea.test", "Test"),
            UUID.randomUUID(), Instant.now(), List.of("access_token"));
    private final String origin = "android:apk-key-hash:" + "A".repeat(43);

    private DeliveryOperation ready() {
        var plan = new DeliveryOperation(id, 21L, 7L, "RELEASE", DeliveryTarget.APP_PROD, "1".repeat(40),
            UUID.randomUUID(), "READY", "2".repeat(64), new ObjectMapper().createObjectNode(), null, Instant.now(), Instant.now());
        when(delivery.authorizationPlan(id, actor)).thenReturn(plan);
        when(delivery.binding(plan)).thenReturn(PrivilegedActionBinding.fromCanonical("PUBLISH_ATENEA_RELEASE",
            "APP_PROD|" + plan.sourceCommit(), id + "|" + plan.planSha256()));
        when(trust.trustedCredentialIds(7L)).thenReturn(java.util.Set.of("credential"));
        when(trust.isTrusted(7L,"credential")).thenReturn(true);
        when(passkeys.beginStepUp(any(),any(),any(),any(),any())).thenReturn(new WebAuthnOptionsResponse(
            UUID.randomUUID(),"challenge",300_000,"atenea.yudri.es",null,null,null,List.of(),
            List.of(new WebAuthnOptionsResponse.CredentialDescriptor("public-key","credential",List.of("internal"))),"required",null,null));
        return plan;
    }

    @Test void optionsUseServerPlanAndroidChannelAndAuthenticatedFamilyWithoutPublishing() {
        var plan = ready();
        service.options(id, actor, origin);
        verify(delivery).authorizationPlan(id, actor);
        verify(passkeys).beginStepUp(actor.operator(), actor.sessionFamilyId(), WebAuthnChannel.ANDROID,
                origin, delivery.binding(plan));
        verifyNoInteractions(grants);
        verify(delivery, never()).confirm(any(), any(), any());
    }

    @Test void verifiedAssertionIssuesTheExistingOneUseGrantWithoutConfirming() {
        var plan = ready();
        var assertion = new WebAuthnAuthenticationRequest(UUID.randomUUID(), "credential", "user", "client", "data", "signature", null, null, null, null);
        var proof = new VerifiedStepUp(actor.operator().operatorId(), actor.sessionFamilyId(), delivery.binding(plan), PrivilegedActionFactor.WEBAUTHN, Instant.now());
        var grant = new PrivilegedActionAuthorizationGrant(UUID.randomUUID(), Instant.now().plusSeconds(60));
        when(passkeys.completeStepUp(actor.operator(), actor.sessionFamilyId(), WebAuthnChannel.ANDROID,
                origin, delivery.binding(plan), assertion)).thenReturn(proof);
        when(grants.issueVerified(proof)).thenReturn(grant);
        assertEquals(grant, service.authorize(id, actor, origin, assertion));
        verify(delivery, never()).confirm(any(), any(), any());
    }

    @Test void everyReadinessOwnerRoleAndExpiryRejectionStopsBeforeUsingAnyFactor() {
        for (String code : List.of("PLAN_NOT_READY_OR_EXPIRED", "PLATFORM_ADMINISTRATOR_REQUIRED",
                "RELEASE_CONTROL_DISABLED", "CANONICAL_MAIN_MOVED")) {
            doThrow(new DeliveryRejectedException(code)).when(delivery).authorizationPlan(id, actor);
            assertEquals(code, assertThrows(DeliveryRejectedException.class, () -> service.options(id, actor, origin)).code());
            assertEquals(code, assertThrows(DeliveryRejectedException.class, () -> service.authorize(id, actor, origin, null)).code());
        }
        verifyNoInteractions(passkeys, grants);
    }

    @Test void missingPasskeyOrInvalidAssertionCannotIssueAuthorityAndDoesNotLeakProofs() {
        var plan = ready();
        when(passkeys.beginStepUp(any(), any(), any(), any(), any())).thenThrow(new OperatorAuthenticationException("private detail"));
        assertEquals("PUBLICATION_PASSKEY_UNAVAILABLE", assertThrows(DeliveryRejectedException.class,
                () -> service.options(id, actor, origin)).code());
        when(passkeys.completeStepUp(any(), any(), any(), any(), any(), any())).thenThrow(new OperatorAuthenticationException("private detail"));
        var assertion = new WebAuthnAuthenticationRequest(UUID.randomUUID(),"credential","user","client","data","signature",null,null,null,null);
        var rejected = assertThrows(DeliveryRejectedException.class, () -> service.authorize(id, actor, origin, assertion));
        assertEquals("PUBLICATION_FACTOR_REJECTED", rejected.code());
        assertFalse(rejected.getMessage().contains("private detail"));
        verifyNoInteractions(grants);
    }

    @Test void loginOrRegistrationWithoutExplicitTotpTrustCannotAuthorizeProduction() {
        ready();
        when(trust.trustedCredentialIds(7L)).thenReturn(java.util.Set.of());
        when(trust.isTrusted(7L,"credential")).thenReturn(false);
        assertEquals("PUBLICATION_PASSKEY_SETUP_REQUIRED",assertThrows(DeliveryRejectedException.class,
                ()->service.options(id,actor,origin)).code());
        var assertion=new WebAuthnAuthenticationRequest(UUID.randomUUID(),"credential","user","client","data","signature",null,null,null,null);
        assertEquals("PUBLICATION_PASSKEY_SETUP_REQUIRED",assertThrows(DeliveryRejectedException.class,
                ()->service.authorize(id,actor,origin,assertion)).code());
        verify(passkeys,never()).completeStepUp(any(),any(),any(),any(),any(),any());
        verifyNoInteractions(grants);
    }
}
