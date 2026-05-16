package com.atenea.api.mobile;

import java.util.List;

public record MobileVoiceNoteSendConfirmResponse(
        MobileVoiceNoteSendIntentResponse intent,
        List<MobileVoiceNoteResponse> consumedNotes,
        Long operatorTurnId,
        Long agentRunId,
        String message
) {
}
