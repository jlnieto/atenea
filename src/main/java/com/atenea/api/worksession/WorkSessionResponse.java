package com.atenea.api.worksession;

import com.atenea.persistence.worksession.WorkSessionStatus;
import java.time.Instant;

public record WorkSessionResponse(
        Long id,
        Long projectId,
        WorkSessionStatus status,
        String title,
        String baseBranch,
        String workspaceBranch,
        String externalThreadId,
        Instant openedAt,
        Instant lastActivityAt,
        Instant closedAt,
        SessionOperationalSnapshotResponse repoState
) {
}
