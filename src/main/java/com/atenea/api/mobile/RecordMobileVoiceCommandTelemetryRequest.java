package com.atenea.api.mobile;

import jakarta.validation.constraints.NotBlank;

public record RecordMobileVoiceCommandTelemetryRequest(
        String clientEventId,
        String source,
        String outcome,
        @NotBlank String reason,
        @NotBlank String transcript,
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
        String voiceState
) {
}
