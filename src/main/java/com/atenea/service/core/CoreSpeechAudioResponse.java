package com.atenea.service.core;

import org.springframework.http.MediaType;

public record CoreSpeechAudioResponse(
        byte[] audio,
        MediaType mediaType
) {
}
