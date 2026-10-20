package com.atenea.api.worksession;

import com.atenea.persistence.developmentchange.DevelopmentChangeProjectionState;
import java.util.List;
import java.util.UUID;

/** Persisted evidence only: reading this response never starts or reconciles a validation. */
public record DevelopmentChangeValidationEvidenceResponse(
        UUID changeKey,
        Long workSessionId,
        long sourceRevision,
        String sourceFingerprintSha256,
        DevelopmentChangeProjectionState validationState,
        int passedOperations,
        int requiredOperations,
        List<ValidationOperationResponse> operations,
        ValidationOperationResponse lastAttempt,
        ChangeValidationWorkflowResponse workflow
) {
    public DevelopmentChangeValidationEvidenceResponse(UUID changeKey, Long sessionId, long revision, String fingerprint,
            DevelopmentChangeProjectionState state, int passed, int required, List<ValidationOperationResponse> operations,
            ValidationOperationResponse lastAttempt) {
        this(changeKey,sessionId,revision,fingerprint,state,passed,required,operations,lastAttempt,null);
    }
}
