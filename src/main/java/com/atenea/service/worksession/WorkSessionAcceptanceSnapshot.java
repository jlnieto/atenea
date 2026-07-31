package com.atenea.service.worksession;

import com.atenea.persistence.worksession.WorkSessionAcceptanceState;
import java.time.Instant;

public record WorkSessionAcceptanceSnapshot(
        Long sessionId,
        WorkSessionAcceptanceState state,
        String sourceTreeFingerprintSha256,
        Instant sourceTreeObservedAt,
        String validationProjectionSha256,
        String validationDefinitionRevision,
        String blockedCheck,
        String nextAction,
        Instant validatedAt,
        Instant integrationReadyAt
) {
}
