package com.atenea.api.worksession;

import java.util.UUID;

public record PreviewActivationRequest(
        UUID previewId,
        Long agentRunId,
        UUID runtimeSessionId,
        String allocationFingerprint
) {
}
