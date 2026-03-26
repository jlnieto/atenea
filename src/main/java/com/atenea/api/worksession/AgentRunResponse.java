package com.atenea.api.worksession;

import com.atenea.persistence.worksession.AgentRunStatus;
import java.time.Instant;

public record AgentRunResponse(
        Long id,
        Long sessionId,
        Long originTurnId,
        Long resultTurnId,
        AgentRunStatus status,
        String targetRepoPath,
        String externalTurnId,
        Instant startedAt,
        Instant finishedAt,
        String outputSummary,
        String errorSummary,
        Instant createdAt
) {
}
