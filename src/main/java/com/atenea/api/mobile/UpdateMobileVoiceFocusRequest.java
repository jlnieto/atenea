package com.atenea.api.mobile;

import com.atenea.persistence.voice.VoiceDomain;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

public record UpdateMobileVoiceFocusRequest(
        VoiceDomain domain,
        Long projectId,
        Long workSessionId,
        Long activeCommandId,
        Long managedHostId,
        @Size(max = 80) String activity,
        @Valid MobileVoicePlaybackRequest playback
) {
}
