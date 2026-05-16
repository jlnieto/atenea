package com.atenea.api.mobile;

public record MobileVoiceRealtimeSessionResponse(
        String provider,
        String sessionType,
        String model,
        String voice,
        String clientSecret,
        Long expiresAt,
        String status
) {
}
