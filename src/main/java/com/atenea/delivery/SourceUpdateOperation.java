package com.atenea.delivery;

import com.atenea.remoteworker.DevelopmentChangeSourceUpdateCommand;
import com.atenea.remoteworker.DevelopmentChangeSourceUpdateGateway.Preparation;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public record SourceUpdateOperation(UUID id, Long sessionId, Long operatorId, String state,
        String originalSourceCommit, String originalFingerprintSha256,
        DevelopmentChangeSourceUpdateCommand command, Preparation preparation, Long preparedRevision,
        Long resolverTurnId, Long resolverRunId, Long resolverSourceRevision, Long resultRevision, String resultFingerprintSha256,
        String errorCode, Instant updatedAt) {
    static final Set<String> TERMINAL = Set.of("RESOLVER_COMPLETED", "READY_TO_FINALIZE", "FAILED", "BLOCKED", "PUBLISHED");
    public View view() {
        return new View(id, sessionId, state, command.targetMainCommit(), resultRevision == null ? resolverSourceRevision : resultRevision,
                resolverRunId, errorCode, updatedAt);
    }
    public record View(UUID id, Long sessionId, String state, String targetMainCommit,
            Long sourceRevision, Long resolverRunId, String errorCode, Instant updatedAt) { }
}
