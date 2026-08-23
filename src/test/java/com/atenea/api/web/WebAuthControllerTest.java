package com.atenea.api.web;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atenea.api.ApiExceptionHandler;
import com.atenea.auth.AuthenticatedOperator;
import com.atenea.auth.MobileAuthSessionResponse;
import com.atenea.auth.MobileLoginRequest;
import com.atenea.auth.MobileLogoutRequest;
import com.atenea.auth.MobileRefreshTokenRequest;
import com.atenea.auth.OperatorAuthProperties;
import com.atenea.auth.OperatorAuthenticationService;
import com.atenea.auth.OperatorProfileResponse;
import com.atenea.auth.webauthn.WebAuthnAuthenticationRequest;
import com.atenea.auth.webauthn.WebAuthnChannel;
import com.atenea.auth.webauthn.WebAuthnCredentialVerificationStartRequest;
import com.atenea.auth.webauthn.WebAuthnOptionsResponse;
import com.atenea.auth.webauthn.WebAuthnService;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class WebAuthControllerTest {

    private static final String ORIGIN = "https://atenea.example.test";
    private static final String PROTOCOL = "FAMILY_V1";

    @Mock
    private OperatorAuthenticationService authenticationService;

    @Mock
    private WebAuthnService webAuthnService;

    private MockMvc mockMvc;
    private WebAuthController controller;

    @BeforeEach
    void setUp() {
        OperatorAuthProperties properties = new OperatorAuthProperties();
        properties.getSessions().setWebOrigin(ORIGIN);
        controller = new WebAuthController(
                authenticationService, properties, webAuthnService);
        mockMvc = MockMvcBuilders.standaloneSetup(
                        controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void loginNegotiatesFamilyAndKeepsRefreshTokenCookieBound() throws Exception {
        when(authenticationService.login(any(MobileLoginRequest.class))).thenReturn(session());

        MvcResult result = mockMvc.perform(post("/api/web/auth/login")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .header(WebAuthController.PROTOCOL_HEADER, PROTOCOL)
                        .header(WebAuthController.SINGLE_FLIGHT_HEADER, "true")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email":"operator@atenea.local",
                                  "password":"secret-pass",
                                  "deviceLabel":"Work browser"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("access-token"))
                .andExpect(jsonPath("$.refreshToken").doesNotExist())
                .andExpect(jsonPath("$.refreshTokenExpiresAt").doesNotExist())
                .andReturn();

        ArgumentCaptor<MobileLoginRequest> request =
                ArgumentCaptor.forClass(MobileLoginRequest.class);
        verify(authenticationService).login(request.capture());
        assertTrue(PROTOCOL.equals(request.getValue().sessionProtocolVersion()));
        assertTrue(Boolean.TRUE.equals(request.getValue().singleFlightRefresh()));
        assertTrue("WEB".equals(request.getValue().clientType()));
        assertSecureHostOnlyCookies(result.getResponse().getHeaders(HttpHeaders.SET_COOKIE));
    }

    @Test
    void webCookieFlowRejectsMissingOrContradictoryNegotiation() throws Exception {
        mockMvc.perform(post("/api/web/auth/login")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"operator@atenea.local\",\"password\":\"secret-pass\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/web/auth/login")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .header(WebAuthController.PROTOCOL_HEADER, PROTOCOL)
                        .header(WebAuthController.SINGLE_FLIGHT_HEADER, "false")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"operator@atenea.local\",\"password\":\"secret-pass\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void refreshRequiresExactOriginAndDoubleSubmitCsrfAndRotatesCookies() throws Exception {
        when(authenticationService.refresh(any(MobileRefreshTokenRequest.class)))
                .thenReturn(session());

        mockMvc.perform(post("/api/web/auth/refresh")
                        .header(HttpHeaders.ORIGIN, "https://wrong.example.test")
                        .header(WebAuthController.CSRF_HEADER, "csrf-value")
                        .cookie(cookie(WebAuthController.REFRESH_COOKIE, "old-refresh"))
                        .cookie(cookie(WebAuthController.CSRF_COOKIE, "csrf-value")))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/web/auth/refresh")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .header(WebAuthController.CSRF_HEADER, "different")
                        .cookie(cookie(WebAuthController.REFRESH_COOKIE, "old-refresh"))
                        .cookie(cookie(WebAuthController.CSRF_COOKIE, "csrf-value")))
                .andExpect(status().isUnauthorized());

        MvcResult result = mockMvc.perform(post("/api/web/auth/refresh")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .header(WebAuthController.CSRF_HEADER, "csrf-value")
                        .header(WebAuthController.PROTOCOL_HEADER, PROTOCOL)
                        .header(WebAuthController.SINGLE_FLIGHT_HEADER, "true")
                        .cookie(cookie(WebAuthController.REFRESH_COOKIE, "old-refresh"))
                        .cookie(cookie(WebAuthController.CSRF_COOKIE, "csrf-value")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refreshToken").doesNotExist())
                .andReturn();

        ArgumentCaptor<MobileRefreshTokenRequest> request =
                ArgumentCaptor.forClass(MobileRefreshTokenRequest.class);
        verify(authenticationService).refresh(request.capture());
        assertTrue("old-refresh".equals(request.getValue().refreshToken()));
        assertTrue(PROTOCOL.equals(request.getValue().sessionProtocolVersion()));
        assertTrue(Boolean.TRUE.equals(request.getValue().singleFlightRefresh()));
        assertSecureHostOnlyCookies(result.getResponse().getHeaders(HttpHeaders.SET_COOKIE));
    }

    @Test
    void logoutUsesCookieBoundRemoteRevocationAndClearsBothCookies() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/web/auth/logout")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .header(WebAuthController.CSRF_HEADER, "csrf-value")
                        .header(WebAuthController.PROTOCOL_HEADER, PROTOCOL)
                        .header(WebAuthController.SINGLE_FLIGHT_HEADER, "true")
                        .cookie(cookie(WebAuthController.REFRESH_COOKIE, "refresh-token"))
                        .cookie(cookie(WebAuthController.CSRF_COOKIE, "csrf-value")))
                .andExpect(status().isNoContent())
                .andReturn();

        ArgumentCaptor<MobileLogoutRequest> request =
                ArgumentCaptor.forClass(MobileLogoutRequest.class);
        verify(authenticationService).logout(request.capture());
        assertTrue("refresh-token".equals(request.getValue().refreshToken()));
        List<String> cookies = result.getResponse().getHeaders(HttpHeaders.SET_COOKIE);
        assertTrue(cookies.size() == 2);
        assertTrue(cookies.stream().allMatch(value -> value.contains("Max-Age=0")));
        assertSecureHostOnlyCookies(cookies);
    }

    @Test
    void webAuthnUsesExactOriginNegotiatedFamilyAndCookieBoundResult() throws Exception {
        UUID requestId = UUID.randomUUID();
        when(webAuthnService.beginAuthentication(WebAuthnChannel.WEB, ORIGIN))
                .thenReturn(options(requestId));

        mockMvc.perform(post("/api/web/auth/webauthn/authentication/options")
                        .header(HttpHeaders.ORIGIN, ORIGIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value(requestId.toString()))
                .andExpect(jsonPath("$.relyingPartyId").value("atenea.example.test"));
        mockMvc.perform(post("/api/web/auth/webauthn/authentication/options")
                        .header(HttpHeaders.ORIGIN, "https://preview.example.test"))
                .andExpect(status().isUnauthorized());

        AuthenticatedOperator operator = new AuthenticatedOperator(
                1L, "operator@atenea.local", "Operator");
        when(webAuthnService.completeAuthentication(
                eq(WebAuthnChannel.WEB), eq(ORIGIN),
                any(WebAuthnAuthenticationRequest.class)))
                .thenReturn(operator);
        when(authenticationService.loginWithWebAuthn(
                eq(operator), eq(PROTOCOL), eq(true), eq("WEB"), eq("Passkey browser")))
                .thenReturn(session());

        MvcResult result = mockMvc.perform(post("/api/web/auth/webauthn/authentication")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .header(WebAuthController.PROTOCOL_HEADER, PROTOCOL)
                        .header(WebAuthController.SINGLE_FLIGHT_HEADER, "true")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(authenticationJson(requestId, "Passkey browser")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("access-token"))
                .andExpect(jsonPath("$.refreshToken").doesNotExist())
                .andReturn();
        assertSecureHostOnlyCookies(result.getResponse().getHeaders(HttpHeaders.SET_COOKIE));

        mockMvc.perform(post("/api/web/auth/webauthn/authentication")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .header(WebAuthController.PROTOCOL_HEADER, PROTOCOL)
                        .header(WebAuthController.SINGLE_FLIGHT_HEADER, "false")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(authenticationJson(requestId, "Passkey browser")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void webAuthnRegistrationBindsActorFamilyAndExactOrigin() throws Exception {
        UUID familyId = UUID.randomUUID();
        AuthenticatedOperator operator = new AuthenticatedOperator(
                1L, "operator@atenea.local", "Operator");
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(operator, null);
        authentication.setDetails(familyId);
        UUID requestId = UUID.randomUUID();
        when(webAuthnService.beginRegistration(
                operator, familyId, WebAuthnChannel.WEB, ORIGIN))
                .thenReturn(options(requestId));

        WebAuthnOptionsResponse response = controller.webAuthnRegistrationOptions(
                operator, authentication, ORIGIN);
        assertTrue(requestId.equals(response.requestId()));
        verify(webAuthnService).beginRegistration(
                operator, familyId, WebAuthnChannel.WEB, ORIGIN);
    }

    @Test
    void ownershipOptionsRequireAndForwardOneSanitizedRecordTarget() {
        UUID familyId = UUID.randomUUID();
        UUID recordId = UUID.randomUUID();
        AuthenticatedOperator operator = new AuthenticatedOperator(
                1L, "operator@atenea.local", "Operator");
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(operator, null);
        authentication.setDetails(familyId);
        UUID requestId = UUID.randomUUID();
        when(webAuthnService.beginOwnershipVerification(
                operator, familyId, WebAuthnChannel.WEB, ORIGIN, recordId))
                .thenReturn(options(requestId));

        WebAuthnCredentialVerificationStartRequest request =
                new WebAuthnCredentialVerificationStartRequest(recordId);
        WebAuthnOptionsResponse response = controller.webAuthnOwnershipOptions(
                operator, authentication, ORIGIN, request);

        assertTrue(requestId.equals(response.requestId()));
        assertFalse(request.toString().contains(recordId.toString()));
        verify(webAuthnService).beginOwnershipVerification(
                operator, familyId, WebAuthnChannel.WEB, ORIGIN, recordId);
    }

    private MobileAuthSessionResponse session() {
        return new MobileAuthSessionResponse(
                "access-token",
                Instant.now().plusSeconds(900),
                "refresh-token",
                Instant.now().plusSeconds(3600),
                new OperatorProfileResponse(
                        1L,
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

    private String authenticationJson(UUID requestId, String deviceLabel) {
        return """
                {
                  "requestId":"%s",
                  "credentialId":"synthetic-credential",
                  "userHandle":"synthetic-user",
                  "clientDataJson":"synthetic-client-data",
                  "authenticatorData":"synthetic-authenticator-data",
                  "signature":"synthetic-signature",
                  "deviceLabel":"%s"
                }
                """.formatted(requestId, deviceLabel);
    }

    private Cookie cookie(String name, String value) {
        return new Cookie(name, value);
    }

    private void assertSecureHostOnlyCookies(List<String> cookies) {
        assertTrue(cookies.size() == 2);
        String refresh = cookies.stream()
                .filter(value -> value.startsWith(WebAuthController.REFRESH_COOKIE + "="))
                .findFirst()
                .orElseThrow();
        assertTrue(refresh.contains("HttpOnly"));
        assertTrue(refresh.contains("Secure"));
        assertTrue(refresh.contains("SameSite=Strict"));
        assertTrue(refresh.contains("Path=/api/web/auth"));
        assertFalse(refresh.toLowerCase().contains("domain="));
        String csrf = cookies.stream()
                .filter(value -> value.startsWith(WebAuthController.CSRF_COOKIE + "="))
                .findFirst()
                .orElseThrow();
        assertFalse(csrf.contains("HttpOnly"));
        assertTrue(csrf.contains("Secure"));
        assertTrue(csrf.contains("SameSite=Strict"));
        assertTrue(csrf.contains("Path=/"));
        assertFalse(csrf.toLowerCase().contains("domain="));
    }
}
