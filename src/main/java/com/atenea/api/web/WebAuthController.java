package com.atenea.api.web;

import com.atenea.auth.MobileAuthSessionResponse;
import com.atenea.auth.MobileLoginRequest;
import com.atenea.auth.MobileLogoutRequest;
import com.atenea.auth.MobileRefreshTokenRequest;
import com.atenea.auth.OperatorAuthProperties;
import com.atenea.auth.OperatorAuthenticationException;
import com.atenea.auth.OperatorAuthenticationService;
import com.atenea.auth.AuthenticatedOperator;
import com.atenea.auth.webauthn.WebAuthnAuthenticationRequest;
import com.atenea.auth.webauthn.WebAuthnChannel;
import com.atenea.auth.webauthn.WebAuthnOptionsResponse;
import com.atenea.auth.webauthn.WebAuthnRegistrationRequest;
import com.atenea.auth.webauthn.WebAuthnCredentialVerificationRequest;
import com.atenea.auth.webauthn.WebAuthnCredentialVerificationResponse;
import com.atenea.auth.webauthn.WebAuthnCredentialVerificationStartRequest;
import com.atenea.auth.webauthn.WebAuthnService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/web/auth")
public class WebAuthController {

    public static final String PROTOCOL_HEADER = "X-Atenea-Session-Protocol";
    public static final String SINGLE_FLIGHT_HEADER = "X-Atenea-Single-Flight";
    public static final String CSRF_HEADER = "X-Atenea-CSRF";
    public static final String REFRESH_COOKIE = "ATENEA_REFRESH";
    public static final String CSRF_COOKIE = "ATENEA_CSRF";

    private static final String REFRESH_COOKIE_PATH = "/api/web/auth";
    private static final String CSRF_COOKIE_PATH = "/";
    private static final String DEFAULT_WEB_DEVICE_LABEL = "Atenea web";

    private final OperatorAuthenticationService operatorAuthenticationService;
    private final OperatorAuthProperties properties;
    private final WebAuthnService webAuthnService;

    public WebAuthController(
            OperatorAuthenticationService operatorAuthenticationService,
            OperatorAuthProperties properties,
            WebAuthnService webAuthnService
    ) {
        this.operatorAuthenticationService = operatorAuthenticationService;
        this.properties = properties;
        this.webAuthnService = webAuthnService;
    }

    @PostMapping("/login")
    public WebAuthSessionResponse login(
            @Valid @RequestBody WebLoginRequest request,
            @RequestHeader(name = HttpHeaders.ORIGIN, required = false) String origin,
            @RequestHeader(name = PROTOCOL_HEADER, required = false) String protocolVersion,
            @RequestHeader(name = SINGLE_FLIGHT_HEADER, required = false) String singleFlight,
            HttpServletResponse response
    ) {
        requireExactOrigin(origin);
        requireFamilyNegotiation(protocolVersion, singleFlight);
        MobileAuthSessionResponse session = operatorAuthenticationService.login(
                new MobileLoginRequest(
                        request.email(),
                        request.password(),
                        "WEB",
                        request.deviceLabel() == null
                                ? DEFAULT_WEB_DEVICE_LABEL
                                : request.deviceLabel(),
                        protocolVersion,
                        exactTrue(singleFlight)));
        issueSessionCookies(response, session);
        return WebAuthSessionResponse.from(session);
    }

    @PostMapping("/refresh")
    public WebAuthSessionResponse refresh(
            @CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken,
            @CookieValue(name = CSRF_COOKIE, required = false) String csrfCookie,
            @RequestHeader(name = HttpHeaders.ORIGIN, required = false) String origin,
            @RequestHeader(name = CSRF_HEADER, required = false) String csrfHeader,
            @RequestHeader(name = PROTOCOL_HEADER, required = false) String protocolVersion,
            @RequestHeader(name = SINGLE_FLIGHT_HEADER, required = false) String singleFlight,
            HttpServletResponse response
    ) {
        requireCookieBoundProof(origin, csrfCookie, csrfHeader);
        requireFamilyNegotiation(protocolVersion, singleFlight);
        requireNonBlankRefreshCookie(refreshToken);
        MobileAuthSessionResponse session = operatorAuthenticationService.refresh(
                new MobileRefreshTokenRequest(
                        refreshToken,
                        null,
                        null,
                        protocolVersion,
                        exactTrue(singleFlight)));
        issueSessionCookies(response, session);
        return WebAuthSessionResponse.from(session);
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(
            @CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken,
            @CookieValue(name = CSRF_COOKIE, required = false) String csrfCookie,
            @RequestHeader(name = HttpHeaders.ORIGIN, required = false) String origin,
            @RequestHeader(name = CSRF_HEADER, required = false) String csrfHeader,
            @RequestHeader(name = PROTOCOL_HEADER, required = false) String protocolVersion,
            @RequestHeader(name = SINGLE_FLIGHT_HEADER, required = false) String singleFlight,
            HttpServletResponse response
    ) {
        requireCookieBoundProof(origin, csrfCookie, csrfHeader);
        requireFamilyNegotiation(protocolVersion, singleFlight);
        requireNonBlankRefreshCookie(refreshToken);
        operatorAuthenticationService.logout(new MobileLogoutRequest(
                refreshToken,
                protocolVersion,
                exactTrue(singleFlight)));
        clearSessionCookies(response);
    }

    @PostMapping("/webauthn/registration/options")
    public WebAuthnOptionsResponse webAuthnRegistrationOptions(
            @AuthenticationPrincipal AuthenticatedOperator operator,
            Authentication authentication,
            @RequestHeader(name = HttpHeaders.ORIGIN, required = false) String origin
    ) {
        requireExactOrigin(origin);
        return webAuthnService.beginRegistration(
                operator,
                currentFamilyId(authentication),
                WebAuthnChannel.WEB,
                origin);
    }

    @PostMapping("/webauthn/registration")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void webAuthnRegister(
            @AuthenticationPrincipal AuthenticatedOperator operator,
            Authentication authentication,
            @RequestHeader(name = HttpHeaders.ORIGIN, required = false) String origin,
            @Valid @RequestBody WebAuthnRegistrationRequest request
    ) {
        requireExactOrigin(origin);
        webAuthnService.completeRegistration(
                operator,
                currentFamilyId(authentication),
                WebAuthnChannel.WEB,
                origin,
                request);
    }

    @PostMapping("/webauthn/ownership/options")
    public WebAuthnOptionsResponse webAuthnOwnershipOptions(
            @AuthenticationPrincipal AuthenticatedOperator operator,
            Authentication authentication,
            @RequestHeader(name = HttpHeaders.ORIGIN, required = false) String origin,
            @Valid @RequestBody WebAuthnCredentialVerificationStartRequest request
    ) {
        requireExactOrigin(origin);
        return webAuthnService.beginOwnershipVerification(
                operator,
                currentFamilyId(authentication),
                WebAuthnChannel.WEB,
                origin,
                request.recordId());
    }

    @PostMapping("/webauthn/ownership")
    public WebAuthnCredentialVerificationResponse verifyWebAuthnOwnership(
            @AuthenticationPrincipal AuthenticatedOperator operator,
            Authentication authentication,
            @RequestHeader(name = HttpHeaders.ORIGIN, required = false) String origin,
            @Valid @RequestBody WebAuthnCredentialVerificationRequest request
    ) {
        requireExactOrigin(origin);
        return webAuthnService.completeOwnershipVerification(
                operator,
                currentFamilyId(authentication),
                WebAuthnChannel.WEB,
                origin,
                request);
    }

    @PostMapping("/webauthn/authentication/options")
    public WebAuthnOptionsResponse webAuthnAuthenticationOptions(
            @RequestHeader(name = HttpHeaders.ORIGIN, required = false) String origin
    ) {
        requireExactOrigin(origin);
        return webAuthnService.beginAuthentication(WebAuthnChannel.WEB, origin);
    }

    @PostMapping("/webauthn/authentication")
    public WebAuthSessionResponse webAuthnAuthenticate(
            @RequestHeader(name = HttpHeaders.ORIGIN, required = false) String origin,
            @RequestHeader(name = PROTOCOL_HEADER, required = false) String protocolVersion,
            @RequestHeader(name = SINGLE_FLIGHT_HEADER, required = false) String singleFlight,
            @Valid @RequestBody WebAuthnAuthenticationRequest request,
            HttpServletResponse response
    ) {
        requireExactOrigin(origin);
        requireFamilyNegotiation(protocolVersion, singleFlight);
        AuthenticatedOperator operator = webAuthnService.completeAuthentication(
                WebAuthnChannel.WEB, origin, request);
        MobileAuthSessionResponse session = operatorAuthenticationService.loginWithWebAuthn(
                operator,
                protocolVersion,
                exactTrue(singleFlight),
                "WEB",
                request.deviceLabel() == null
                        ? DEFAULT_WEB_DEVICE_LABEL
                        : request.deviceLabel());
        issueSessionCookies(response, session);
        return WebAuthSessionResponse.from(session);
    }

    private void issueSessionCookies(
            HttpServletResponse response,
            MobileAuthSessionResponse session
    ) {
        Duration maxAge = Duration.between(Instant.now(), session.refreshTokenExpiresAt());
        if (maxAge.isNegative()) {
            maxAge = Duration.ZERO;
        }
        addCookie(response, ResponseCookie.from(REFRESH_COOKIE, session.refreshToken())
                .httpOnly(true)
                .secure(true)
                .sameSite("Strict")
                .path(REFRESH_COOKIE_PATH)
                .maxAge(maxAge)
                .build());
        addCookie(response, csrfCookie(UUID.randomUUID().toString(), maxAge));
    }

    private UUID currentFamilyId(Authentication authentication) {
        return authentication.getDetails() instanceof UUID value ? value : null;
    }

    private void clearSessionCookies(HttpServletResponse response) {
        addCookie(response, ResponseCookie.from(REFRESH_COOKIE, "")
                .httpOnly(true)
                .secure(true)
                .sameSite("Strict")
                .path(REFRESH_COOKIE_PATH)
                .maxAge(Duration.ZERO)
                .build());
        addCookie(response, csrfCookie("", Duration.ZERO));
    }

    private ResponseCookie csrfCookie(String value, Duration maxAge) {
        return ResponseCookie.from(CSRF_COOKIE, value)
                .httpOnly(false)
                .secure(true)
                .sameSite("Strict")
                .path(CSRF_COOKIE_PATH)
                .maxAge(maxAge)
                .build();
    }

    private void addCookie(HttpServletResponse response, ResponseCookie cookie) {
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private void requireCookieBoundProof(
            String origin,
            String csrfCookie,
            String csrfHeader
    ) {
        requireExactOrigin(origin);
        if (csrfCookie == null
                || csrfCookie.isBlank()
                || csrfHeader == null
                || !MessageDigest.isEqual(
                        csrfCookie.getBytes(StandardCharsets.UTF_8),
                        csrfHeader.getBytes(StandardCharsets.UTF_8))) {
            throw new OperatorAuthenticationException("Invalid web session proof");
        }
    }

    private void requireExactOrigin(String origin) {
        String expected = properties.getSessions().getWebOrigin();
        if (expected == null
                || expected.isBlank()
                || origin == null
                || !MessageDigest.isEqual(
                        expected.getBytes(StandardCharsets.UTF_8),
                        origin.getBytes(StandardCharsets.UTF_8))) {
            throw new OperatorAuthenticationException("Invalid web session origin");
        }
    }

    private void requireNonBlankRefreshCookie(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new OperatorAuthenticationException("Web refresh cookie is required");
        }
    }

    private void requireFamilyNegotiation(String protocolVersion, String singleFlight) {
        String supportedVersion = properties.getSessions().getSupportedProtocolVersion();
        if (supportedVersion == null
                || supportedVersion.isBlank()
                || !supportedVersion.equals(supportedVersion.trim())
                || !supportedVersion.equals(protocolVersion)
                || !"true".equals(singleFlight)) {
            throw new OperatorAuthenticationException("Invalid web session protocol");
        }
    }

    private Boolean exactTrue(String value) {
        if (value == null) {
            return null;
        }
        return "true".equals(value) ? Boolean.TRUE : Boolean.FALSE;
    }
}
