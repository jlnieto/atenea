package com.atenea.api.mobile;

public record CreateMobileVoiceRealtimeSessionRequest(
        String clientContext,
        String voice,
        Double speed
) {
}
