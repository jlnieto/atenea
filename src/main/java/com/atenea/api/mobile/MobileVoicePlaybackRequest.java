package com.atenea.api.mobile;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

public record MobileVoicePlaybackRequest(
        @Size(max = 80) String sourceType,
        @Size(max = 120) String sourceId,
        @Min(0) Integer segmentIndex,
        @Min(0) Integer segmentCount
) {
}
