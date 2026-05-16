package com.atenea.api.mobile;

import com.atenea.api.core.CoreCommandResponse;
import java.util.List;

public record MobileVoiceNotesSendResponse(
        CoreCommandResponse command,
        List<MobileVoiceNoteResponse> consumedNotes
) {
}
