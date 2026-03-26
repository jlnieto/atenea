package com.atenea.api.worksession;

public record CreateSessionTurnResponse(
        SessionTurnResponse operatorTurn,
        AgentRunResponse run,
        SessionTurnResponse codexTurn
) {
}
