package com.atenea.api.mobile;

public record MobileVoicePlaybackResponse(
        String sourceType,
        String sourceId,
        Integer segmentIndex,
        Integer segmentCount
) {
}
