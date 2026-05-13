package com.atenea.service.core;

import org.springframework.http.HttpStatus;

public class CoreCloseWorkSessionRejectedException extends CoreCommandRejectedException {

    public CoreCloseWorkSessionRejectedException(String message) {
        super("CLOSE_WORK_SESSION_REJECTED", message, HttpStatus.CONFLICT);
    }
}
