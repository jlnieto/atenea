package com.atenea.service.worksession;

import com.atenea.persistence.worksession.AgentRunStatus;

public class AgentRunTransitionNotAllowedException extends RuntimeException {

    public AgentRunTransitionNotAllowedException(Long runId, AgentRunStatus currentStatus, AgentRunStatus targetStatus) {
        super("AgentRun with id '" + runId + "' cannot transition from " + currentStatus + " to " + targetStatus);
    }
}
