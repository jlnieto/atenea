package com.atenea.api.core;

import com.atenea.persistence.core.CoreChannel;
import com.atenea.persistence.core.CoreCommandStatus;
import com.atenea.persistence.core.CoreDomain;
import com.atenea.persistence.core.CoreResultType;
import com.atenea.persistence.core.CoreRiskLevel;
import com.atenea.persistence.core.CoreTargetType;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Instant;

public record CoreCommandDetailResponse(
        Long commandId,
        String rawInput,
        CoreChannel channel,
        CoreCommandStatus status,
        CoreInterpretationResponse interpretation,
        CoreDomain domain,
        String intent,
        String capability,
        CoreRiskLevel riskLevel,
        boolean requiresConfirmation,
        boolean confirmed,
        String confirmationToken,
        BigDecimal confidence,
        JsonNode requestContext,
        JsonNode parameters,
        JsonNode interpretedIntent,
        JsonNode clarification,
        CoreResultType resultType,
        CoreTargetType targetType,
        Long targetId,
        String resultSummary,
        String errorCode,
        String errorMessage,
        String operatorMessage,
        String speakableMessage,
        Instant createdAt,
        Instant finishedAt
) {
}
