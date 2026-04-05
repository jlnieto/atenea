package com.atenea.service.core;

public class CoreSpeechSynthesisException extends RuntimeException {

    public CoreSpeechSynthesisException(String message) {
        super(message);
    }

    public CoreSpeechSynthesisException(String message, Throwable cause) {
        super(message, cause);
    }
}
