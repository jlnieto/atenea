package com.atenea.api.mobile;

import java.util.List;

public record MobileVoiceNotesResponse(
        List<MobileVoiceNoteResponse> notes
) {
}
