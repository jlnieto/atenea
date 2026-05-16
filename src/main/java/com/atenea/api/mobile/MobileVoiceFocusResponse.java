package com.atenea.api.mobile;

import com.atenea.persistence.voice.VoiceDomain;
import java.time.Instant;

public record MobileVoiceFocusResponse(
        Long operatorId,
        VoiceDomain domain,
        Long projectId,
        String projectName,
        Long workSessionId,
        String workSessionTitle,
        Long activeCommandId,
        Long latestCommandId,
        Boolean focusUpToDate,
        Long managedHostId,
        String managedHostName,
        String activity,
        MobileVoicePlaybackResponse playback,
        int activeNoteCount,
        Instant updatedAt
) {
}
