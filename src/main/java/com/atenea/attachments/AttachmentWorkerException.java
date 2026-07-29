package com.atenea.attachments;

public class AttachmentWorkerException extends RuntimeException {

    private final int statusCode;
    private final String code;

    public AttachmentWorkerException(String message, int statusCode, String code) {
        super(message);
        this.statusCode = statusCode;
        this.code = code;
    }

    public AttachmentWorkerException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = 502;
        this.code = "attachment_worker_unavailable";
    }

    public int getStatusCode() {
        return statusCode;
    }

    public String getCode() {
        return code;
    }
}
