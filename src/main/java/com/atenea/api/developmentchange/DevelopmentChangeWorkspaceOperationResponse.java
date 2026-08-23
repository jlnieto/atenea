package com.atenea.api.developmentchange;

import com.atenea.persistence.developmentchange.DevelopmentChangeSourceState;
import com.atenea.persistence.developmentchange.DevelopmentChangeWorkspaceOperationKind;
import com.atenea.persistence.developmentchange.DevelopmentChangeWorkspaceOperationState;
import com.atenea.persistence.developmentchange.DevelopmentChangeWorkspaceState;
import java.util.UUID;

public record DevelopmentChangeWorkspaceOperationResponse(
        UUID operationId,
        DevelopmentChangeWorkspaceOperationKind operationKind,
        DevelopmentChangeWorkspaceOperationState state,
        long revision,
        String receiptSha256,
        boolean replayed,
        String failureCode,
        DevelopmentChangeWorkspaceState workspaceState,
        DevelopmentChangeSourceState sourceState,
        long sourceRevision,
        String sourceFingerprintSha256,
        DevelopmentChangeActionResponse nextAction) {
}
