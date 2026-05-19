package com.atenea.service.core;

import com.atenea.api.mobile.MobileSessionActionsResponse;
import com.atenea.api.mobile.MobileSessionInsightsResponse;
import com.atenea.api.worksession.WorkSessionViewLatestRunResponse;
import com.atenea.api.worksession.WorkSessionViewResponse;

public record SessionSpeechBriefingRequest(
        Long sessionId,
        SessionSpeechMode mode,
        int maxOutputCharacters,
        WorkSessionViewResponse view,
        MobileSessionInsightsResponse insights,
        MobileSessionActionsResponse actions,
        WorkSessionViewLatestRunResponse latestRun,
        String sourceText
) {
}
