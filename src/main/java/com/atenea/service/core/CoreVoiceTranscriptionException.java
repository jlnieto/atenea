package com.atenea.service.core;

public class CoreVoiceTranscriptionException extends RuntimeException {

    public CoreVoiceTranscriptionException(String message) {
        super(message);
    }

    public CoreVoiceTranscriptionException(String message, Throwable cause) {
        super(message, cause);
    }
}
