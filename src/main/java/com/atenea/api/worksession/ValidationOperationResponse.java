package com.atenea.api.worksession;

import com.atenea.persistence.worksession.ValidationOperationKind;
import com.atenea.persistence.worksession.ValidationOperationStatus;
import java.time.Instant;
import java.util.UUID;

public record ValidationOperationResponse(
        UUID id,
        Long workSessionId,
        ValidationOperationKind operation,
        ValidationOperationStatus status,
        String sourceTreeFingerprintSha256,
        String definitionRevision,
        Integer exitCode,
        Long durationMillis,
        String artifactManifestSha256,
        String summary,
        Instant startedAt,
        Instant finishedAt
) {
}
