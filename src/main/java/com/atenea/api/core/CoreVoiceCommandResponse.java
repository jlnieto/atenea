package com.atenea.api.core;

public record CoreVoiceCommandResponse(
        String transcript,
        CoreCommandResponse command
) {
}
