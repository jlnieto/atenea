package com.atenea.auth;

import org.springframework.security.core.AuthenticationException;

public class OperatorAuthenticationException extends AuthenticationException {

    public OperatorAuthenticationException(String message) {
        super(message);
    }
}
