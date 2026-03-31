package com.atenea.service.core;

import com.atenea.persistence.core.CoreResultType;
import com.atenea.persistence.core.CoreTargetType;

public record CoreCommandExecutionResult(
        CoreResultType resultType,
        CoreTargetType targetType,
        Long targetId,
        Object payload,
        String resultSummary,
        String operatorMessage,
        String speakableMessage
) {
}
