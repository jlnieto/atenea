package com.atenea.api;

import com.atenea.auth.AuthenticatedOperator;
import com.atenea.auth.recovery.AccountRecoveryRequest;
import com.atenea.auth.recovery.OperatorRecoveryService;
import com.atenea.auth.recovery.TotpEnrollmentActivationRequest;
import com.atenea.auth.recovery.TotpEnrollmentActivationResponse;
import com.atenea.auth.recovery.TotpEnrollmentStartResponse;
import com.atenea.auth.recovery.TotpFactorRemovalRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
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

    public OperatorFactorController(OperatorRecoveryService service) {
        this.service = service;
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
}
