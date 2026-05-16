package com.atenea.api.mobile;

import com.atenea.persistence.voice.VoiceNoteSendDestinationType;
import com.atenea.persistence.voice.VoiceNoteSendIntentStatus;
import java.time.Instant;
import java.util.List;

public record MobileVoiceNoteSendIntentResponse(
        Long id,
        VoiceNoteSendIntentStatus status,
        VoiceNoteSendDestinationType destinationType,
        Long projectId,
        String projectName,
        Long workSessionId,
        String workSessionTitle,
        List<Long> noteIds,
        int noteCount,
        String instruction,
        String confirmationPrompt,
        String errorMessage,
        Long agentRunId,
        Instant createdAt,
        Instant expiresAt,
        Instant updatedAt
) {
}
