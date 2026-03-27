package com.atenea.api.worksession;

public record WorkSessionViewResponse(
        WorkSessionResponse session,
        boolean runInProgress,
        boolean canCreateTurn,
        WorkSessionViewLatestRunResponse latestRun,
        String lastError,
        String lastAgentResponse
) {
}
