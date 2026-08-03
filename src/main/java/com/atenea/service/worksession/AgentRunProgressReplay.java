package com.atenea.service.worksession;

import com.atenea.persistence.worksession.AgentRunProgressCategory;
import com.atenea.persistence.worksession.AgentRunProgressEventEntity;
import com.atenea.persistence.worksession.AgentRunProgressNextAction;
import java.time.Instant;
import java.util.List;

public record AgentRunProgressReplay(
        long requestedAfterSequence,
        long retainedFloor,
        boolean cursorWasBelowRetainedFloor,
        AgentRunProgressCategory currentState,
        AgentRunProgressEventProjection latestEvent,
        AgentRunProgressCategory terminalOutcome,
        long elapsedMillis,
        AgentRunProgressNextAction requiredNextAction,
        List<AgentRunProgressEventEntity> events) {

    public record AgentRunProgressEventProjection(
            long sequence,
            AgentRunProgressCategory category,
            String operatorMessage,
            Instant occurredAt) {
    }
}
