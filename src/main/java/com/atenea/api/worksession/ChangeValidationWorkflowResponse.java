package com.atenea.api.worksession;

import java.time.Instant;
import java.util.UUID;

/** Persisted progress only. No worker command, path or credential is exposed. */
public record ChangeValidationWorkflowResponse(UUID id, Long workSessionId, long sourceRevision,
        String sourceFingerprintSha256, String state, String phase, UUID activeOperationId,
        String errorCode, Instant startedAt, Instant updatedAt, Instant finishedAt) { }
