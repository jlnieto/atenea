package com.atenea.api.mobile;

import com.atenea.persistence.worksession.AgentRunStatus;
import java.time.Instant;

public record MobileVoiceCodexStatusResponse(
        Long projectId,
        String projectName,
        Long workSessionId,
        String workSessionTitle,
        Long agentRunId,
        AgentRunStatus runStatus,
        boolean responseReady,
        boolean failed,
        String message,
        Instant updatedAt
) {
}
