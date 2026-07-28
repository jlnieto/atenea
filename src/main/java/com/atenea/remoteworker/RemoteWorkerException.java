package com.atenea.remoteworker;

public class RemoteWorkerException extends RuntimeException {

    private final int statusCode;

    public RemoteWorkerException(String message, int statusCode) {
        super(message);
        this.statusCode = statusCode;
    }

    public RemoteWorkerException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = 0;
    }

    public int getStatusCode() {
        return statusCode;
    }
}
