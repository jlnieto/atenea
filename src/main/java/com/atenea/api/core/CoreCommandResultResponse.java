package com.atenea.api.core;

import com.atenea.persistence.core.CoreResultType;
import com.atenea.persistence.core.CoreTargetType;

public record CoreCommandResultResponse(
        CoreResultType type,
        CoreTargetType targetType,
        Long targetId,
        Object payload
) {
}
