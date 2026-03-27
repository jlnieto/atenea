package com.atenea.api.worksession;

import com.atenea.persistence.worksession.AgentRunStatus;
import java.time.Instant;

public record WorkSessionViewLatestRunResponse(
        Long id,
        AgentRunStatus status,
        Long originTurnId,
        Long resultTurnId,
        String externalTurnId,
        Instant startedAt,
        Instant finishedAt,
        String outputSummary,
        String errorSummary
) {
}
