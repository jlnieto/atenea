package com.atenea.service.operations;

public class OperationsRemoteExecutionException extends RuntimeException {

    public OperationsRemoteExecutionException(String message, Throwable cause) {
        super(message, cause);
    }

    public OperationsRemoteExecutionException(String message) {
        super(message);
    }
}
