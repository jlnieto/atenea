package com.atenea.api.developmentchange;

import com.atenea.persistence.developmentchange.DevelopmentChangeProjectionState;
import com.atenea.persistence.developmentchange.DevelopmentChangeSourceState;
import com.atenea.persistence.developmentchange.DevelopmentChangeStatus;
import com.atenea.persistence.developmentchange.DevelopmentChangeWorkspaceState;
import java.time.Instant;
import java.util.UUID;

public record DevelopmentChangeResponse(
        UUID changeKey,
        Long projectId,
        String title,
        DevelopmentChangeStatus status,
        String baseRef,
        String baseCommit,
        String workspaceBranch,
        String workspaceIdentity,
        String selectedWorkerId,
        long projectPolicyRevision,
        long sourceRevision,
        String sourceFingerprintSha256,
        DevelopmentChangeSourceState sourceState,
        String observedCanonicalCommit,
        DevelopmentChangeWorkspaceState workspaceState,
        long workspaceOperationRevision,
        String workspaceObservationSha256,
        boolean workspaceOperationsEnabled,
        DevelopmentChangeProjectionState validationState,
        DevelopmentChangeProjectionState reviewState,
        DevelopmentChangeProjectionState integrationState,
        DevelopmentChangeProjectionState releaseState,
        Long activeSessionId,
        DevelopmentChangePhase phase,
        boolean mutationsEnabled,
        DevelopmentChangeActionResponse primaryAction,
        Instant createdAt,
        Instant updatedAt,
        long version) {
}
