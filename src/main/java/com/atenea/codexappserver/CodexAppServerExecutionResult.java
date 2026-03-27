package com.atenea.codexappserver;

public record CodexAppServerExecutionResult(
        String threadId,
        String turnId,
        Status status,
        String finalAnswer,
        String outputSummary,
        String commentaryPreview,
        String errorMessage
) {

    public enum Status {
        COMPLETED,
        FAILED,
        INTERRUPTED
    }
}
