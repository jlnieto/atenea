package com.atenea.service.worksession;

import com.atenea.persistence.worksession.AgentRunProgressEventEntity;

public record AgentRunProgressAppendResult(
        AgentRunProgressEventEntity event,
        boolean inserted,
        long retainedFloor) {
}
