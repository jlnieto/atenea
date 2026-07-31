package com.atenea.persistence.worksession;

public enum AgentRunProcessOutcome {
    SUCCEEDED,
    FAILED,
    CANCELLED;

    public static AgentRunProcessOutcome fromStatus(AgentRunStatus status) {
        if (status == null || !status.isTerminal()) {
            return null;
        }
        return switch (status) {
            case SUCCEEDED -> SUCCEEDED;
            case FAILED -> FAILED;
            case CANCELLED -> CANCELLED;
            default -> null;
        };
    }
}
