package com.atenea.api.mobile;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Explanation and navigation only. Never a command or an execution grant. */
public record ChangeHelpResponse(
        String protocolVersion, Long workSessionId, UUID changeKey, Long sourceRevision,
        Instant observedAt, String stage, String answer, String simpleAnswer,
        List<String> facts, Navigation nextAction, List<String> suggestedQuestions,
        String explanationSource
) {
    public record Navigation(String destination, String label, String reason) { }
}
