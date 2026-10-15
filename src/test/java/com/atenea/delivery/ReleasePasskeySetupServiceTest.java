package com.atenea.delivery;

import com.atenea.auth.*;
import com.atenea.auth.action.*;
import com.atenea.auth.recovery.OperatorRecoveryService;
import com.atenea.auth.webauthn.*;
import com.atenea.persistence.auth.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

class ReleasePasskeySetupServiceTest {
    private final OperatorRepository operators=mock(OperatorRepository.class);
    private final OperatorWebAuthnCredentialRepository credentials=mock(OperatorWebAuthnCredentialRepository.class);
    private final WebAuthnService passkeys=mock(WebAuthnService.class);
    private final OperatorRecoveryService factors=mock(OperatorRecoveryService.class);
    private final PrivilegedActionAuthorizationService grants=mock(PrivilegedActionAuthorizationService.class);
    private final ReleasePasskeyTrustStore trust=mock(ReleasePasskeyTrustStore.class);
    private final OperatorAuthProperties properties=new OperatorAuthProperties();
    private final ReleasePasskeySetupService setup=new ReleasePasskeySetupService(operators,credentials,passkeys,factors,grants,trust,properties);
    private final OperatorEntity operator=new OperatorEntity();
    private final OperatorWebAuthnCredentialEntity key=new OperatorWebAuthnCredentialEntity();
    private final AuthenticatedSession actor=new AuthenticatedSession(new AuthenticatedOperator(7L,"test@atenea.test","Test"),
            UUID.randomUUID(),Instant.now(),List.of("access_token"));
    private final String origin="android:apk-key-hash:"+"A".repeat(43);
    private final WebAuthnAuthenticationRequest assertion=new WebAuthnAuthenticationRequest(UUID.randomUUID(),"Y3JlZA","dXNlcg","client","data","signature",null,null,null,null);
    private final UUID grant=UUID.randomUUID();

    @BeforeEach void fixture() {
        operator.setId(7L);operator.setActive(true);operator.setCodexOperationsRole(CodexOperationsRole.PLATFORM_ADMINISTRATOR);
        when(operators.findById(7L)).thenReturn(Optional.of(operator));
        key.setId(UUID.randomUUID());key.setOperator(operator);key.setCredentialId("cred".getBytes());key.setPublicKeyCose("public-key".getBytes());
        when(credentials.findByCredentialId(any())).thenReturn(Optional.of(key));
        when(credentials.findByCredentialIdForUpdate(any())).thenReturn(Optional.of(key));
        when(grants.issueVerified(any())).thenReturn(new PrivilegedActionAuthorizationGrant(grant,Instant.now().plusSeconds(60)));
        when(grants.consumeForAcceptance(any(),any(),any(),any())).thenAnswer(call -> ((PrivilegedActionAcceptance<?>)call.getArgument(3)).accept());
    }

    @Test void onlyExplicitTotpAndActualKeyProofCanActivateTheChosenCredentialWithoutPublishing() {
        setup.approve(actor,origin,"123456",assertion);
        verify(passkeys).completeStepUp(eq(actor.operator()),eq(actor.sessionFamilyId()),eq(WebAuthnChannel.ANDROID),eq(origin),
                argThat(value->value.actionKind().equals("SETUP_RELEASE_PASSKEY")),eq(assertion));
        verify(factors).verifyTotpStepUp(eq(actor.operator()),eq(actor.sessionFamilyId()),
                argThat(value->value.actionKind().equals("TRUST_RELEASE_PASSKEY")),eq("123456"));
        verify(trust).approve(key,grant,assertion.requestId());
    }

    @Test void stolenSessionWithoutAuthenticatorCannotTrustANewKey() {
        when(factors.verifyTotpStepUp(any(),any(),any(),any())).thenThrow(new OperatorAuthenticationException("factor rejected"));
        assertEquals("PUBLICATION_FACTOR_REJECTED",assertThrows(DeliveryRejectedException.class,
                ()->setup.approve(actor,origin,"123456",assertion)).code());
        verifyNoInteractions(trust);
        verify(grants,never()).issueVerified(any());
    }

    @Test void totpAloneWithoutKeyPossessionOrUserVerificationCannotActivateAKey() {
        when(passkeys.completeStepUp(any(),any(),any(),any(),any(),any())).thenThrow(new OperatorAuthenticationException("proof rejected"));
        assertThrows(DeliveryRejectedException.class,()->setup.approve(actor,origin,"123456",assertion));
        verifyNoInteractions(factors,trust);
        verify(grants,never()).issueVerified(any());
    }

    @Test void revokedForeignOrChangedCredentialCannotBeTrusted() {
        key.setRevokedAt(Instant.now());
        assertThrows(DeliveryRejectedException.class,()->setup.approve(actor,origin,"123456",assertion));
        key.setRevokedAt(null);
        var foreign=new OperatorEntity();foreign.setId(8L);key.setOperator(foreign);
        assertThrows(DeliveryRejectedException.class,()->setup.approve(actor,origin,"123456",assertion));
        key.setOperator(operator);
        when(credentials.findByCredentialIdForUpdate(any())).thenReturn(Optional.empty());
        assertThrows(DeliveryRejectedException.class,()->setup.approve(actor,origin,"123456",assertion));
        verifyNoInteractions(trust);
    }

    @Test void effectiveRoleIsRequiredBeforeEnrollmentProofOrAuthenticator() {
        operator.setCodexOperationsRole(CodexOperationsRole.ROUTINE_OPERATOR);
        assertEquals("PLATFORM_ADMINISTRATOR_REQUIRED",assertThrows(DeliveryRejectedException.class,()->setup.options(actor,origin)).code());
        assertEquals("PLATFORM_ADMINISTRATOR_REQUIRED",assertThrows(DeliveryRejectedException.class,
                ()->setup.approve(actor,origin,"123456",assertion)).code());
        verifyNoInteractions(passkeys,factors,trust);
    }

    @Test void readOnlyStatusDistinguishesInitialSetupFromConfiguredFactorWithoutUsingProofOrTotp() {
        properties.getWebAuthn().setEnabled(true);properties.getWebAuthn().setAndroidOrigins(List.of(origin));
        properties.getPrivilegedActions().setEnabled(true);
        when(credentials.countByOperatorIdAndRevokedAtIsNull(7L)).thenReturn(1L);
        assertTrue(setup.status(actor,origin).setupRequired());
        when(trust.trustedCredentialIds(7L)).thenReturn(Set.of("Y3JlZA"));
        assertTrue(setup.status(actor,origin).configured());
        assertFalse(setup.status(actor,"foreign").enabled());
        verifyNoInteractions(passkeys,factors);
        verify(grants,never()).issueVerified(any());
    }
}
