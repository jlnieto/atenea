package com.atenea.service.worksession;

public class WorkSessionTurnExecutionFailedException extends RuntimeException {

    public WorkSessionTurnExecutionFailedException(String message) {
        super(message);
    }

    public WorkSessionTurnExecutionFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
