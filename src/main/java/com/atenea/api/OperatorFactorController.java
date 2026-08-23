package com.atenea.api;

import com.atenea.auth.AuthenticatedOperator;
import com.atenea.auth.recovery.AccountRecoveryRequest;
import com.atenea.auth.recovery.OperatorRecoveryService;
import com.atenea.auth.recovery.TotpEnrollmentActivationRequest;
import com.atenea.auth.recovery.TotpEnrollmentActivationResponse;
import com.atenea.auth.recovery.TotpEnrollmentStartResponse;
import com.atenea.auth.recovery.TotpFactorRemovalRequest;
import com.atenea.auth.webauthn.WebAuthnCredentialInventoryResponse;
import com.atenea.auth.webauthn.WebAuthnCredentialLifecycleService;
import com.atenea.auth.webauthn.WebAuthnCredentialSignalSnapshot;
import com.atenea.auth.webauthn.WebAuthnControlledResetResult;
import com.atenea.auth.webauthn.WebAuthnControlledResetService;
import com.atenea.auth.webauthn.WebAuthnControlledResetStatus;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class OperatorFactorController {
    private final OperatorRecoveryService service;
    private final WebAuthnCredentialLifecycleService credentialLifecycleService;
    private final WebAuthnControlledResetService controlledResetService;

    public OperatorFactorController(
            OperatorRecoveryService service,
            WebAuthnCredentialLifecycleService credentialLifecycleService,
            WebAuthnControlledResetService controlledResetService
    ) {
        this.service = service;
        this.credentialLifecycleService = credentialLifecycleService;
        this.controlledResetService = controlledResetService;
    }

    @PostMapping("/totp/enrollments")
    public TotpEnrollmentStartResponse beginTotpEnrollment(
            @AuthenticationPrincipal AuthenticatedOperator operator) {
        return service.beginTotpEnrollment(operator);
    }

    @PostMapping("/totp/enrollments/activate")
    public TotpEnrollmentActivationResponse activateTotpEnrollment(
            @AuthenticationPrincipal AuthenticatedOperator operator,
            @Valid @RequestBody TotpEnrollmentActivationRequest request) {
        return service.activateTotpEnrollment(operator, request);
    }

    @DeleteMapping("/totp/enrollments/{enrollmentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancelTotpEnrollment(
            @AuthenticationPrincipal AuthenticatedOperator operator,
            @PathVariable UUID enrollmentId) {
        service.cancelTotpEnrollment(operator, enrollmentId);
    }

    @DeleteMapping("/totp")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeTotpFactor(
            @AuthenticationPrincipal AuthenticatedOperator operator,
            @Valid @RequestBody TotpFactorRemovalRequest request) {
        service.removeTotpFactor(operator, request);
    }

    @PostMapping("/recovery")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void recover(@Valid @RequestBody AccountRecoveryRequest request) {
        service.recover(request);
    }

    @GetMapping("/webauthn/credentials")
    public WebAuthnCredentialInventoryResponse webAuthnCredentials(
            @AuthenticationPrincipal AuthenticatedOperator operator) {
        return credentialLifecycleService.inventory(operator);
    }

    @DeleteMapping("/webauthn/credentials/{recordId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeWebAuthnCredential(
            @AuthenticationPrincipal AuthenticatedOperator operator,
            @PathVariable UUID recordId) {
        credentialLifecycleService.revoke(operator, recordId);
    }

    @GetMapping("/webauthn/credentials/signal-snapshot")
    public WebAuthnCredentialSignalSnapshot webAuthnSignalSnapshot(
            @AuthenticationPrincipal AuthenticatedOperator operator) {
        return credentialLifecycleService.signalSnapshot(operator);
    }

    @GetMapping("/webauthn/passkey-reset")
    public WebAuthnControlledResetStatus passkeyResetStatus(
            @AuthenticationPrincipal AuthenticatedOperator operator) {
        return controlledResetService.status(operator);
    }

    @PostMapping("/webauthn/passkey-reset/{candidateRecordId}/commit")
    public WebAuthnControlledResetResult commitPasskeyReset(
            @AuthenticationPrincipal AuthenticatedOperator operator,
            @PathVariable UUID candidateRecordId) {
        return controlledResetService.commit(operator, candidateRecordId);
    }
}
