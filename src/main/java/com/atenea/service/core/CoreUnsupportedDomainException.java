package com.atenea.service.core;

import org.springframework.http.HttpStatus;

public class CoreUnsupportedDomainException extends CoreCommandRejectedException {

    public CoreUnsupportedDomainException(String message) {
        super("UNSUPPORTED_DOMAIN", message, HttpStatus.BAD_REQUEST);
    }
}
