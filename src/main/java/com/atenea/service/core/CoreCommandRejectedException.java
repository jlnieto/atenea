package com.atenea.service.core;

import org.springframework.http.HttpStatus;

public abstract class CoreCommandRejectedException extends RuntimeException {

    private final String code;
    private final HttpStatus httpStatus;

    protected CoreCommandRejectedException(String code, String message, HttpStatus httpStatus) {
        super(message);
        this.code = code;
        this.httpStatus = httpStatus;
    }

    public String getCode() {
        return code;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }
}
