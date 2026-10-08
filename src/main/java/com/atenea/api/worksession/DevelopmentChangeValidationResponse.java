package com.atenea.api.worksession;

import com.atenea.persistence.developmentchange.DevelopmentChangeProjectionState;
import java.util.UUID;

public record DevelopmentChangeValidationResponse(
        UUID changeKey,
        long sourceRevision,
        String sourceFingerprintSha256,
        DevelopmentChangeProjectionState validationState,
        String state,
        String currentOperation,
        int passedOperations,
        int requiredOperations,
        String summary
) {
}
