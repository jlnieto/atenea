package com.atenea.service.worksession;

import com.atenea.persistence.worksession.WorkSessionStatus;

public class WorkSessionNotOpenException extends RuntimeException {

    public WorkSessionNotOpenException(Long sessionId, WorkSessionStatus status) {
        super("WorkSession with id '" + sessionId + "' is not OPEN (current status: " + status + ")");
    }
}
