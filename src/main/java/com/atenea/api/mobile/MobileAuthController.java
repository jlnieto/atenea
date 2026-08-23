package com.atenea.api.mobile;

import com.atenea.auth.AuthenticatedOperator;
import com.atenea.auth.MobileAuthSessionResponse;
import com.atenea.auth.MobileLoginRequest;
import com.atenea.auth.MobileLogoutRequest;
import com.atenea.auth.MobileRefreshTokenRequest;
import com.atenea.auth.OperatorAuthenticationService;
import com.atenea.auth.OperatorProfileResponse;
import com.atenea.auth.session.SessionInventoryProjection;
import com.atenea.auth.webauthn.WebAuthnAuthenticationRequest;
import com.atenea.auth.webauthn.WebAuthnChannel;
import com.atenea.auth.webauthn.WebAuthnOptionsResponse;
import com.atenea.auth.webauthn.WebAuthnRegistrationRequest;
import com.atenea.auth.webauthn.WebAuthnCredentialVerificationRequest;
import com.atenea.auth.webauthn.WebAuthnCredentialVerificationResponse;
import com.atenea.auth.webauthn.WebAuthnCredentialVerificationStartRequest;
import com.atenea.auth.webauthn.WebAuthnService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/mobile/auth")
public class MobileAuthController {

    private final OperatorAuthenticationService operatorAuthenticationService;
    private final WebAuthnService webAuthnService;

    public MobileAuthController(
            OperatorAuthenticationService operatorAuthenticationService,
            WebAuthnService webAuthnService
    ) {
        this.operatorAuthenticationService = operatorAuthenticationService;
        this.webAuthnService = webAuthnService;
    }

    @PostMapping("/login")
    public MobileAuthSessionResponse login(@Valid @RequestBody MobileLoginRequest request) {
        return operatorAuthenticationService.login(request);
    }

    @PostMapping("/refresh")
    public MobileAuthSessionResponse refresh(@Valid @RequestBody MobileRefreshTokenRequest request) {
        return operatorAuthenticationService.refresh(request);
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@Valid @RequestBody MobileLogoutRequest request) {
        operatorAuthenticationService.logout(request);
    }

    @GetMapping("/me")
    public OperatorProfileResponse me(@AuthenticationPrincipal AuthenticatedOperator operator) {
        return operatorAuthenticationService.getCurrentOperator(operator);
    }

    @GetMapping("/sessions")
    public List<SessionInventoryProjection> sessions(
            @AuthenticationPrincipal AuthenticatedOperator operator,
            Authentication authentication
    ) {
        return operatorAuthenticationService.listSessions(
                operator, currentFamilyId(authentication));
    }

    @DeleteMapping("/sessions/current")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeCurrentSession(
            @AuthenticationPrincipal AuthenticatedOperator operator,
            Authentication authentication
    ) {
        operatorAuthenticationService.revokeCurrentSession(
                operator, currentFamilyId(authentication));
    }

    @DeleteMapping("/sessions/others")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeAllOtherSessions(
            @AuthenticationPrincipal AuthenticatedOperator operator,
            Authentication authentication
    ) {
        operatorAuthenticationService.revokeAllOtherSessions(
                operator, currentFamilyId(authentication));
    }

    @DeleteMapping("/sessions")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeAllSessions(
            @AuthenticationPrincipal AuthenticatedOperator operator
    ) {
        operatorAuthenticationService.revokeAllSessions(operator);
    }

    @DeleteMapping("/sessions/{familyId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeSession(
            @AuthenticationPrincipal AuthenticatedOperator operator,
            @PathVariable UUID familyId
    ) {
        operatorAuthenticationService.revokeSession(operator, familyId);
    }

    @PostMapping("/webauthn/registration/options")
    public WebAuthnOptionsResponse webAuthnRegistrationOptions(
            @AuthenticationPrincipal AuthenticatedOperator operator,
            Authentication authentication,
            @RequestHeader("X-Atenea-Android-Origin") String androidOrigin
    ) {
        return webAuthnService.beginRegistration(
                operator,
                currentFamilyId(authentication),
                WebAuthnChannel.ANDROID,
                androidOrigin);
    }

    @PostMapping("/webauthn/registration")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void webAuthnRegister(
            @AuthenticationPrincipal AuthenticatedOperator operator,
            Authentication authentication,
            @RequestHeader("X-Atenea-Android-Origin") String androidOrigin,
            @Valid @RequestBody WebAuthnRegistrationRequest request
    ) {
        webAuthnService.completeRegistration(
                operator,
                currentFamilyId(authentication),
                WebAuthnChannel.ANDROID,
                androidOrigin,
                request);
    }

    @PostMapping("/webauthn/ownership/options")
    public WebAuthnOptionsResponse webAuthnOwnershipOptions(
            @AuthenticationPrincipal AuthenticatedOperator operator,
            Authentication authentication,
            @RequestHeader("X-Atenea-Android-Origin") String androidOrigin,
            @Valid @RequestBody WebAuthnCredentialVerificationStartRequest request
    ) {
        return webAuthnService.beginOwnershipVerification(
                operator,
                currentFamilyId(authentication),
                WebAuthnChannel.ANDROID,
                androidOrigin,
                request.recordId());
    }

    @PostMapping("/webauthn/ownership")
    public WebAuthnCredentialVerificationResponse verifyWebAuthnOwnership(
            @AuthenticationPrincipal AuthenticatedOperator operator,
            Authentication authentication,
            @RequestHeader("X-Atenea-Android-Origin") String androidOrigin,
            @Valid @RequestBody WebAuthnCredentialVerificationRequest request
    ) {
        return webAuthnService.completeOwnershipVerification(
                operator,
                currentFamilyId(authentication),
                WebAuthnChannel.ANDROID,
                androidOrigin,
                request);
    }

    @PostMapping("/webauthn/authentication/options")
    public WebAuthnOptionsResponse webAuthnAuthenticationOptions(
            @RequestHeader("X-Atenea-Android-Origin") String androidOrigin
    ) {
        return webAuthnService.beginAuthentication(
                WebAuthnChannel.ANDROID, androidOrigin);
    }

    @PostMapping("/webauthn/authentication")
    public MobileAuthSessionResponse webAuthnAuthenticate(
            @RequestHeader("X-Atenea-Android-Origin") String androidOrigin,
            @Valid @RequestBody WebAuthnAuthenticationRequest request
    ) {
        AuthenticatedOperator operator = webAuthnService.completeAuthentication(
                WebAuthnChannel.ANDROID, androidOrigin, request);
        return operatorAuthenticationService.loginWithWebAuthn(
                operator,
                request.sessionProtocolVersion(),
                request.singleFlightRefresh(),
                request.clientType(),
                request.deviceLabel());
    }

    private UUID currentFamilyId(Authentication authentication) {
        return authentication.getDetails() instanceof UUID value ? value : null;
    }
}
