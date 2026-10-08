package com.atenea.service.mobile;

import org.springframework.http.HttpStatus;

public class MobileDiagnosticException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    public MobileDiagnosticException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }
    public HttpStatus status() { return status; }
    public String code() { return code; }
}
