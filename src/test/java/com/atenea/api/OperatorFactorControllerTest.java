package com.atenea.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class OperatorFactorControllerTest {
    @Mock private OperatorRecoveryService recoveryService;
    private MockMvc mockMvc;
    private AuthenticatedOperator actor;

    @BeforeEach
    void setUp() {
        actor = new AuthenticatedOperator(42L, "synthetic@atenea.test", "Synthetic");
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new OperatorFactorController(recoveryService))
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

    private RequestPostProcessor authentication() {
        return SecurityMockMvcRequestPostProcessors.authentication(
                new UsernamePasswordAuthenticationToken(actor, null));
    }
}
