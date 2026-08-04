package com.atenea.api.mobile;

import com.atenea.persistence.auth.CodexOperationsRole;

public record MobileSessionOperatorStateResponse(
        boolean surfaceEnabled,
        MobileSessionOperatorState state,
        String title,
        String blocker,
        MobileSessionPrimaryAction primaryAction,
        String primaryActionLabel,
        boolean primaryActionAvailable,
        CodexOperationsRole requiredRole,
        Long targetWorkSessionId,
        Long targetAgentRunId
) {
}
