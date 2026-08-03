package com.atenea.previews;

import java.util.UUID;

public record PreviewActivationCommand(
        UUID previewId,
        Long agentRunId,
        UUID runtimeSessionId,
        String allocationFingerprint
) {
}
