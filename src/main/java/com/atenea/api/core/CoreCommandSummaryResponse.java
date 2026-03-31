package com.atenea.api.core;

import com.atenea.persistence.core.CoreCommandStatus;
import com.atenea.persistence.core.CoreDomain;
import java.time.Instant;

public record CoreCommandSummaryResponse(
        Long commandId,
        CoreCommandStatus status,
        CoreInterpretationResponse interpretation,
        CoreIntentResponse intent,
        String rawInput,
        String resultSummary,
        String errorCode,
        String errorMessage,
        String operatorMessage,
        String speakableMessage,
        Instant createdAt,
        Instant finishedAt
) {
}
