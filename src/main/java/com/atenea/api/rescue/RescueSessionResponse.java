package com.atenea.api.rescue;

import com.atenea.persistence.rescue.RescueSessionStatus;
import java.time.Instant;

public record RescueSessionResponse(
        Long id,
        Long projectId,
        String projectName,
        String repoPath,
        RescueSessionStatus status,
        String title,
        boolean canCreateTurn,
        String externalThreadId,
        String externalTurnId,
        Instant openedAt,
        Instant lastActivityAt,
        Instant closedAt
) {
}
