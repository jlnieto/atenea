package com.atenea.service.core;

public record SessionSpeechBriefingResult(
        String text,
        String provider,
        String model
) {
}
