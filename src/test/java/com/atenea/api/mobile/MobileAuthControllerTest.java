package com.atenea.api.mobile;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atenea.api.ApiExceptionHandler;
import com.atenea.auth.AuthenticatedOperator;
import com.atenea.auth.MobileAuthSessionResponse;
import com.atenea.auth.MobileLoginRequest;
import com.atenea.auth.MobileRefreshTokenRequest;
import com.atenea.auth.OperatorAuthenticationService;
import com.atenea.auth.OperatorProfileResponse;
import com.atenea.auth.webauthn.WebAuthnAuthenticationRequest;
import com.atenea.auth.webauthn.WebAuthnChannel;
import com.atenea.auth.webauthn.WebAuthnCredentialVerificationStartRequest;
import com.atenea.auth.webauthn.WebAuthnOptionsResponse;
import com.atenea.auth.webauthn.WebAuthnService;
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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class MobileAuthControllerTest {

    private static final String ANDROID_ORIGIN =
            "android:apk-key-hash:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

    @Mock
    private OperatorAuthenticationService operatorAuthenticationService;

    @Mock
    private WebAuthnService webAuthnService;

    private MockMvc mockMvc;
    private MobileAuthController controller;

    @BeforeEach
    void setUp() {
        controller = new MobileAuthController(operatorAuthenticationService, webAuthnService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(
                        Jackson2ObjectMapperBuilder.json().build()))
                .build();
    }

    @Test
    void loginRefreshAndMeDelegateToAuthService() throws Exception {
        MobileAuthSessionResponse session = new MobileAuthSessionResponse(
                "access-token",
                Instant.parse("2026-03-29T10:15:00Z"),
                "refresh-token",
                Instant.parse("2026-04-28T10:00:00Z"),
                new OperatorProfileResponse(4L, "operator@atenea.local", "Operator", "ROUTINE_OPERATOR"));
        when(operatorAuthenticationService.login(new MobileLoginRequest("operator@atenea.local", "secret")))
                .thenReturn(session);
        when(operatorAuthenticationService.refresh(new MobileRefreshTokenRequest("refresh-token")))
                .thenReturn(session);
        when(operatorAuthenticationService.getCurrentOperator(any(AuthenticatedOperator.class)))
                .thenReturn(new OperatorProfileResponse(4L, "operator@atenea.local", "Operator", "ROUTINE_OPERATOR"));

        mockMvc.perform(post("/api/mobile/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "operator@atenea.local",
                                  "password": "secret"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("access-token"))
                .andExpect(jsonPath("$.operator.email").value("operator@atenea.local"))
                .andExpect(jsonPath("$.operator.codexOperationsRole").value("ROUTINE_OPERATOR"));

        mockMvc.perform(post("/api/mobile/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "refreshToken": "refresh-token"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refreshToken").value("refresh-token"));

        mockMvc.perform(get("/api/mobile/auth/me")
                        .with(SecurityMockMvcRequestPostProcessors.authentication(
                                new UsernamePasswordAuthenticationToken(
                                        new AuthenticatedOperator(4L, "operator@atenea.local", "Operator"),
                                        null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(4))
                .andExpect(jsonPath("$.displayName").value("Operator"));
    }

    @Test
    void androidWebAuthnUsesExplicitOriginAndNegotiatedFamilyWithoutClientChanges()
            throws Exception {
        UUID requestId = UUID.randomUUID();
        when(webAuthnService.beginAuthentication(WebAuthnChannel.ANDROID, ANDROID_ORIGIN))
                .thenReturn(options(requestId));

        mockMvc.perform(post("/api/mobile/auth/webauthn/authentication/options")
                        .header("X-Atenea-Android-Origin", ANDROID_ORIGIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value(requestId.toString()));

        AuthenticatedOperator operator = new AuthenticatedOperator(
                4L, "operator@atenea.local", "Operator");
        when(webAuthnService.completeAuthentication(
                eq(WebAuthnChannel.ANDROID), eq(ANDROID_ORIGIN),
                any(WebAuthnAuthenticationRequest.class)))
                .thenReturn(operator);
        when(operatorAuthenticationService.loginWithWebAuthn(
                operator, "FAMILY_V1", true, "ANDROID", "Synthetic phone"))
                .thenReturn(session());

        mockMvc.perform(post("/api/mobile/auth/webauthn/authentication")
                        .header("X-Atenea-Android-Origin", ANDROID_ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "requestId":"%s",
                                  "credentialId":"synthetic-credential",
                                  "userHandle":"synthetic-user",
                                  "clientDataJson":"synthetic-client-data",
                                  "authenticatorData":"synthetic-authenticator-data",
                                  "signature":"synthetic-signature",
                                  "sessionProtocolVersion":"FAMILY_V1",
                                  "singleFlightRefresh":true,
                                  "clientType":"ANDROID",
                                  "deviceLabel":"Synthetic phone"
                                }
                                """.formatted(requestId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("access-token"));
        verify(webAuthnService).completeAuthentication(
                eq(WebAuthnChannel.ANDROID), eq(ANDROID_ORIGIN),
                any(WebAuthnAuthenticationRequest.class));
    }

    @Test
    void androidOwnershipOptionsRequireOneSanitizedRecordTarget() throws Exception {
        UUID familyId = UUID.randomUUID();
        UUID recordId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        AuthenticatedOperator operator = new AuthenticatedOperator(
                4L, "operator@atenea.local", "Operator");
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(operator, null);
        authentication.setDetails(familyId);
        when(webAuthnService.beginOwnershipVerification(
                operator,
                familyId,
                WebAuthnChannel.ANDROID,
                ANDROID_ORIGIN,
                recordId)).thenReturn(options(requestId));

        WebAuthnCredentialVerificationStartRequest request =
                new WebAuthnCredentialVerificationStartRequest(recordId);
        WebAuthnOptionsResponse response = controller.webAuthnOwnershipOptions(
                operator, authentication, ANDROID_ORIGIN, request);

        org.junit.jupiter.api.Assertions.assertEquals(requestId, response.requestId());
        org.junit.jupiter.api.Assertions.assertFalse(
                request.toString().contains(recordId.toString()));
        verify(webAuthnService).beginOwnershipVerification(
                operator,
                familyId,
                WebAuthnChannel.ANDROID,
                ANDROID_ORIGIN,
                recordId);
    }

    private MobileAuthSessionResponse session() {
        return new MobileAuthSessionResponse(
                "access-token",
                Instant.parse("2026-03-29T10:15:00Z"),
                "refresh-token",
                Instant.parse("2026-04-28T10:00:00Z"),
                new OperatorProfileResponse(
                        4L,
                        "operator@atenea.local",
                        "Operator",
                        "ROUTINE_OPERATOR"));
    }

    private WebAuthnOptionsResponse options(UUID requestId) {
        return new WebAuthnOptionsResponse(
                requestId,
                "redacted-test-challenge",
                300_000,
                "atenea.example.test",
                "Atenea test",
                null,
                null,
                List.of(),
                List.of(),
                "required",
                null,
                null);
    }
}
