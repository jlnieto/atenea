package com.atenea.service.worksession;

public class WorkSessionAlreadyRunningException extends RuntimeException {

    public WorkSessionAlreadyRunningException(Long sessionId) {
        super("WorkSession with id '" + sessionId + "' is already RUNNING and does not accept a new executable turn");
    }
}
