package com.atenea.service.worksession;

public class WorkSessionPublishConflictException extends RuntimeException {

    public WorkSessionPublishConflictException(Long sessionId, String reason) {
        super("WorkSession '%s' cannot be published: %s".formatted(sessionId, reason));
    }
}
