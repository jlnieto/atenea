package com.atenea.service.core;

import org.springframework.http.HttpStatus;

public class CoreUnknownIntentException extends CoreCommandRejectedException {

    public CoreUnknownIntentException(String message) {
        super("UNKNOWN_INTENT", message, HttpStatus.BAD_REQUEST);
    }
}
