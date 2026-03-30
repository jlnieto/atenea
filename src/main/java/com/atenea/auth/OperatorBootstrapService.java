package com.atenea.auth;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class OperatorBootstrapService implements ApplicationRunner {

    private final OperatorAuthProperties properties;
    private final OperatorAuthenticationService operatorAuthenticationService;

    public OperatorBootstrapService(
            OperatorAuthProperties properties,
            OperatorAuthenticationService operatorAuthenticationService
    ) {
        this.properties = properties;
        this.operatorAuthenticationService = operatorAuthenticationService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.getBootstrap().isEnabled()) {
            return;
        }
        String email = properties.getBootstrap().getEmail();
        String password = properties.getBootstrap().getPassword();
        if (email == null || email.isBlank() || password == null || password.isBlank()) {
            throw new IllegalStateException("Bootstrap operator requires non-blank email and password");
        }
        operatorAuthenticationService.bootstrapOperator(
                email,
                properties.getBootstrap().getDisplayName(),
                password
        );
    }
}
