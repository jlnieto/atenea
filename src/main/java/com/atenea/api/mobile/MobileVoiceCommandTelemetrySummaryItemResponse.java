package com.atenea.api.mobile;

import java.time.Instant;

public record MobileVoiceCommandTelemetrySummaryItemResponse(
        String normalizedTranscript,
        String sampleTranscript,
        String outcome,
        String reason,
        String intentType,
        String domain,
        Long projectId,
        String projectName,
        Long workSessionId,
        String workSessionTitle,
        Integer activeNoteCount,
        long count,
        Instant latestAt
) {
}
