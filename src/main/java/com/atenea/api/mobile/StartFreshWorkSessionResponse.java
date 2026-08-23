package com.atenea.api.mobile;

import com.atenea.api.worksession.WorkSessionConversationViewResponse;
import java.util.UUID;

public record StartFreshWorkSessionResponse(
        UUID operationId,
        String state,
        Long sourceWorkSessionId,
        Long resultWorkSessionId,
        boolean created,
        WorkSessionConversationViewResponse view
) {
}
