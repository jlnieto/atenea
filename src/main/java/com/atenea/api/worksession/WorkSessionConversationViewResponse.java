package com.atenea.api.worksession;

import java.util.List;

public record WorkSessionConversationViewResponse(
        WorkSessionViewResponse view,
        List<SessionTurnResponse> recentTurns,
        int recentTurnLimit,
        boolean historyTruncated
) {
}
