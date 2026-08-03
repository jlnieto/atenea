package com.atenea.mobilepush;

public class FcmDeliveryException extends RuntimeException {

    private final FailureKind failureKind;
    private final String diagnosticCode;

    public FcmDeliveryException(
            FailureKind failureKind,
            String diagnosticCode,
            Throwable cause) {
        super(diagnosticCode, cause);
        this.failureKind = failureKind;
        this.diagnosticCode = diagnosticCode;
    }

    public FailureKind failureKind() {
        return failureKind;
    }

    public String diagnosticCode() {
        return diagnosticCode;
    }

    public enum FailureKind {
        RETRYABLE,
        INVALID_TOKEN,
        PERMANENT
    }
}
