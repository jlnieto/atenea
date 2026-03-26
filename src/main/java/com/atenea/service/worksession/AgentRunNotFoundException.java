package com.atenea.service.worksession;

public class AgentRunNotFoundException extends RuntimeException {

    public AgentRunNotFoundException(Long runId) {
        super("AgentRun with id '" + runId + "' was not found");
    }
}
