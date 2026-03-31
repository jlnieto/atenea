package com.atenea.service.core;

public class CoreClarificationRequiredException extends RuntimeException {

    private final CoreClarification clarification;

    public CoreClarificationRequiredException(CoreClarification clarification) {
        super(clarification.message());
        this.clarification = clarification;
    }

    public CoreClarification clarification() {
        return clarification;
    }
}
