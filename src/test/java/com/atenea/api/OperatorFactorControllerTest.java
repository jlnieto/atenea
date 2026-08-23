package com.atenea.api;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atenea.auth.AuthenticatedOperator;
import com.atenea.auth.recovery.AccountRecoveryRequest;
import com.atenea.auth.recovery.OperatorRecoveryService;
import com.atenea.auth.recovery.TotpEnrollmentActivationRequest;
import com.atenea.auth.recovery.TotpEnrollmentActivationResponse;
import com.atenea.auth.recovery.TotpEnrollmentStartResponse;
import com.atenea.auth.recovery.TotpFactorRemovalRequest;
import com.atenea.auth.webauthn.WebAuthnCredentialLifecycleService;
import com.atenea.auth.webauthn.WebAuthnCredentialInventoryItem;
import com.atenea.auth.webauthn.WebAuthnCredentialInventoryResponse;
import com.atenea.auth.webauthn.WebAuthnCredentialState;
import com.atenea.auth.webauthn.WebAuthnProviderCategory;
import com.atenea.auth.webauthn.WebAuthnProviderProvenance;
import com.atenea.auth.webauthn.WebAuthnCredentialNotAcceptedException;
import com.atenea.auth.webauthn.WebAuthnControlledResetResult;
import com.atenea.auth.webauthn.WebAuthnControlledResetService;
import com.atenea.auth.webauthn.WebAuthnControlledResetState;
import com.atenea.auth.webauthn.WebAuthnControlledResetStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class OperatorFactorControllerTest {
    @Mock private OperatorRecoveryService recoveryService;
    @Mock private WebAuthnCredentialLifecycleService credentialLifecycleService;
    @Mock private WebAuthnControlledResetService controlledResetService;
    private MockMvc mockMvc;
    private AuthenticatedOperator actor;

    @BeforeEach
    void setUp() {
        actor = new AuthenticatedOperator(42L, "synthetic@atenea.test", "Synthetic");
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new OperatorFactorController(
                                recoveryService,
                                credentialLifecycleService,
                                controlledResetService))
                .setControllerAdvice(new ApiExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(
                        Jackson2ObjectMapperBuilder.json().build()))
                .build();
    }

    @Test
    void authenticatedTotpEndpointsDelegateTwoPhaseLifecycle() throws Exception {
        UUID enrollmentId = UUID.randomUUID();
        when(recoveryService.beginTotpEnrollment(any(AuthenticatedOperator.class))).thenReturn(
                new TotpEnrollmentStartResponse(
                        enrollmentId, "SYNTHETICONETIMESECRET", "SHA1", 6, 30,
                        Instant.parse("2026-08-12T12:10:00Z")));
        when(recoveryService.activateTotpEnrollment(
                any(AuthenticatedOperator.class), any(TotpEnrollmentActivationRequest.class)))
                .thenReturn(new TotpEnrollmentActivationResponse(
                        List.of("abcdefghijklmnopqrstuv")));

        mockMvc.perform(post("/api/auth/totp/enrollments").with(authentication()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enrollmentId").value(enrollmentId.toString()))
                .andExpect(jsonPath("$.digits").value(6))
                .andExpect(jsonPath("$.periodSeconds").value(30));

        mockMvc.perform(post("/api/auth/totp/enrollments/activate")
                        .with(authentication())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"enrollmentId":"%s","code":"123456"}
                                """.formatted(enrollmentId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recoveryCodes.length()").value(1));

        mockMvc.perform(delete("/api/auth/totp/enrollments/{id}", enrollmentId)
                        .with(authentication()))
                .andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/auth/totp")
                        .with(authentication())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"654321\"}"))
                .andExpect(status().isNoContent());

        verify(recoveryService).cancelTotpEnrollment(
                any(AuthenticatedOperator.class), eq(enrollmentId));
        verify(recoveryService).removeTotpFactor(
                any(AuthenticatedOperator.class), eq(new TotpFactorRemovalRequest("654321")));
    }

    @Test
    void recoveryContractRequiresPasswordAndCodeAndReturnsNoMaterial() throws Exception {
        mockMvc.perform(post("/api/auth/recovery")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email":"synthetic@atenea.test",
                                  "password":"synthetic-password",
                                  "recoveryCode":"abcdefghijklmnopqrstuv"
                                }
                                """))
                .andExpect(status().isNoContent());
        verify(recoveryService).recover(new AccountRecoveryRequest(
                "synthetic@atenea.test",
                "synthetic-password",
                "abcdefghijklmnopqrstuv"));

        mockMvc.perform(post("/api/auth/recovery")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"synthetic@atenea.test\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void passkeyInventoryExposesOnlySanitizedRecordsAndRevokesByPublicId()
            throws Exception {
        UUID recordId = UUID.randomUUID();
        when(credentialLifecycleService.inventory(any(AuthenticatedOperator.class)))
                .thenReturn(new WebAuthnCredentialInventoryResponse(
                        "ACTION_REQUIRED",
                        List.of(new WebAuthnCredentialInventoryItem(
                                recordId,
                                "1Password · 2",
                                WebAuthnProviderCategory.ONE_PASSWORD,
                                WebAuthnProviderProvenance.OPERATOR_DECLARED,
                                true,
                                true,
                                List.of("internal"),
                                Instant.parse("2026-08-15T10:20:00Z"),
                                null,
                                Instant.parse("2026-08-15T10:21:00Z"),
                                WebAuthnCredentialState.ACTIVE)),
                        List.of(
                                WebAuthnProviderCategory.GOOGLE_PASSWORD_MANAGER,
                                WebAuthnProviderCategory.ONE_PASSWORD),
                        List.of(WebAuthnProviderCategory.ONE_PASSWORD),
                        false,
                        true,
                        false,
                        "Verifica el dominio restante."));

        MvcResult result = mockMvc.perform(get("/api/auth/webauthn/credentials")
                        .with(authentication()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.credentials[0].recordId")
                        .value(recordId.toString()))
                .andExpect(jsonPath("$.credentials[0].label")
                        .value("1Password · 2"))
                .andExpect(jsonPath("$.credentials[0].credentialId").doesNotExist())
                .andExpect(jsonPath("$.credentials[0].aaguid").doesNotExist())
                .andExpect(jsonPath("$.readOnly").value(false))
                .andReturn();
        String json = result.getResponse().getContentAsString();
        assertFalse(json.contains("userAgent"));
        assertFalse(json.contains("ipAddress"));

        mockMvc.perform(delete("/api/auth/webauthn/credentials/{recordId}", recordId)
                        .with(authentication()))
                .andExpect(status().isNoContent());
        verify(credentialLifecycleService).revoke(
                any(AuthenticatedOperator.class), eq(recordId));
    }

    @Test
    void onlyCryptographicallyClassifiedRevokedCredentialReceivesSignalAction() {
        ApiErrorResponse classified = new ApiExceptionHandler()
                .handleWebAuthnCredentialNotAccepted(
                        new WebAuthnCredentialNotAcceptedException())
                .getBody();

        assertEquals("SIGNAL_UNKNOWN_CREDENTIAL", classified.action());
        assertFalse(classified.retryable());
        assertFalse(classified.toString().contains("credentialId"));
    }

    @Test
    void controlledResetEndpointsExposeOnlySanitizedStateAndDelegateExactCandidate()
            throws Exception {
        UUID candidateRecordId = UUID.randomUUID();
        when(controlledResetService.status(any(AuthenticatedOperator.class)))
                .thenReturn(new WebAuthnControlledResetStatus(
                        WebAuthnControlledResetState.COMMIT_READY,
                        "1Password",
                        4,
                        4,
                        candidateRecordId,
                        "1Password · 5",
                        1,
                        10,
                        "Confirma la revocación de las cuatro passkeys históricas."));
        when(controlledResetService.commit(
                any(AuthenticatedOperator.class), eq(candidateRecordId)))
                .thenReturn(new WebAuthnControlledResetResult(
                        "COMMITTED", 1, 4, 1, 10, 8));

        MvcResult statusResult = mockMvc.perform(get("/api/auth/webauthn/passkey-reset")
                        .with(authentication()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("COMMIT_READY"))
                .andExpect(jsonPath("$.targetProvider").value("1Password"))
                .andExpect(jsonPath("$.candidateRecordId")
                        .value(candidateRecordId.toString()))
                .andExpect(jsonPath("$.activeTotpCount").value(1))
                .andExpect(jsonPath("$.activeRecoveryCodeCount").value(10))
                .andExpect(jsonPath("$.credentialId").doesNotExist())
                .andReturn();
        assertFalse(statusResult.getResponse().getContentAsString().contains("secret"));

        mockMvc.perform(post(
                        "/api/auth/webauthn/passkey-reset/{candidateRecordId}/commit",
                        candidateRecordId)
                        .with(authentication()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("COMMITTED"))
                .andExpect(jsonPath("$.activePasskeyCount").value(1))
                .andExpect(jsonPath("$.revokedHistoricalCount").value(4));
        verify(controlledResetService).commit(
                any(AuthenticatedOperator.class), eq(candidateRecordId));
    }

    private RequestPostProcessor authentication() {
        return SecurityMockMvcRequestPostProcessors.authentication(
                new UsernamePasswordAuthenticationToken(actor, null));
    }
}
