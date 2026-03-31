package com.atenea.service.core;

import org.springframework.http.HttpStatus;

public class CoreCapabilityDisabledException extends CoreCommandRejectedException {

    public CoreCapabilityDisabledException(String message) {
        super("CAPABILITY_DISABLED", message, HttpStatus.CONFLICT);
    }
}
