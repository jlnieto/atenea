package com.atenea.service.core;

import org.springframework.http.HttpStatus;

public class CoreCommandConfirmationNotPendingException extends CoreCommandRejectedException {

    public CoreCommandConfirmationNotPendingException(Long commandId) {
        super(
                "CONFIRMATION_NOT_PENDING",
                "Core command " + commandId + " is not waiting for confirmation",
                HttpStatus.CONFLICT);
    }
}
