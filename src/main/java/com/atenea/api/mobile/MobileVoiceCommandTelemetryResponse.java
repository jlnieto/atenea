package com.atenea.api.mobile;

import java.time.Instant;

public record MobileVoiceCommandTelemetryResponse(
        Long id,
        String clientEventId,
        String source,
        String outcome,
        String reason,
        String transcript,
        String normalizedTranscript,
        boolean wakeWordDetected,
        boolean startsWithWakeWord,
        String intentType,
        String domain,
        Long projectId,
        String projectName,
        Long workSessionId,
        String workSessionTitle,
        Long activeCommandId,
        Integer activeNoteCount,
        Long pendingSendIntentId,
        Boolean realtimeConnected,
        String voiceState,
        Instant createdAt
) {
}
