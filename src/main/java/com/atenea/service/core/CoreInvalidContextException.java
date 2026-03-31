package com.atenea.service.core;

import org.springframework.http.HttpStatus;

public class CoreInvalidContextException extends CoreCommandRejectedException {

    public CoreInvalidContextException(String message) {
        super("EXECUTION_REJECTED", message, HttpStatus.BAD_REQUEST);
    }
}
