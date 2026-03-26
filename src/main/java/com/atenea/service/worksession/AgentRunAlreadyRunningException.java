package com.atenea.service.worksession;

public class AgentRunAlreadyRunningException extends RuntimeException {

    public AgentRunAlreadyRunningException(Long sessionId) {
        super("WorkSession with id '" + sessionId + "' already has a running AgentRun");
    }
}
