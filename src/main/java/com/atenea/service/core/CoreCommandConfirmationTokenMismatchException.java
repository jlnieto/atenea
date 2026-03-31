package com.atenea.service.core;

import org.springframework.http.HttpStatus;

public class CoreCommandConfirmationTokenMismatchException extends CoreCommandRejectedException {

    public CoreCommandConfirmationTokenMismatchException(Long commandId) {
        super(
                "CONFIRMATION_TOKEN_INVALID",
                "Core command " + commandId + " received an invalid confirmation token",
                HttpStatus.CONFLICT);
    }
}
