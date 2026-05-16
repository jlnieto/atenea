package com.atenea.api.mobile;

import com.atenea.persistence.voice.VoiceNoteStatus;
import java.time.Instant;

public record MobileVoiceNoteResponse(
        Long id,
        String text,
        VoiceNoteStatus status,
        String focusSnapshotJson,
        Long consumedByCommandId,
        Instant capturedAt,
        Instant consumedAt,
        Instant updatedAt
) {
}
