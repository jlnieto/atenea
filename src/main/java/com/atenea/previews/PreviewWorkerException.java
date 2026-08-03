package com.atenea.previews;

public class PreviewWorkerException extends RuntimeException {

    private final int statusCode;
    private final String code;

    public PreviewWorkerException(String message, Throwable cause) {
        super(message, cause);
        statusCode = 503;
        code = "preview_worker_unavailable";
    }

    public PreviewWorkerException(String message, int statusCode, String code) {
        super(message);
        this.statusCode = statusCode;
        this.code = code;
    }

    public int getStatusCode() { return statusCode; }
    public String getCode() { return code; }
}
