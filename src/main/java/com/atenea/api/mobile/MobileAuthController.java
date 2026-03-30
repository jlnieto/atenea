package com.atenea.api.mobile;

import com.atenea.auth.AuthenticatedOperator;
import com.atenea.auth.MobileAuthSessionResponse;
import com.atenea.auth.MobileLoginRequest;
import com.atenea.auth.MobileLogoutRequest;
import com.atenea.auth.MobileRefreshTokenRequest;
import com.atenea.auth.OperatorAuthenticationService;
import com.atenea.auth.OperatorProfileResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/mobile/auth")
public class MobileAuthController {

    private final OperatorAuthenticationService operatorAuthenticationService;

    public MobileAuthController(OperatorAuthenticationService operatorAuthenticationService) {
        this.operatorAuthenticationService = operatorAuthenticationService;
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
}
