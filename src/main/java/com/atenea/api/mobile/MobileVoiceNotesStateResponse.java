package com.atenea.api.mobile;

import java.util.List;

public record MobileVoiceNotesStateResponse(
        MobileVoiceFocusResponse focus,
        List<MobileVoiceNoteResponse> notes,
        MobileVoiceNoteSendIntentResponse pendingSendIntent
) {
}
