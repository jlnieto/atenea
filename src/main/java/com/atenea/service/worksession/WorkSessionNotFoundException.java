package com.atenea.service.worksession;

public class WorkSessionNotFoundException extends RuntimeException {

    public WorkSessionNotFoundException(Long sessionId) {
        super("WorkSession with id '" + sessionId + "' was not found");
    }
}
