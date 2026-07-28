package com.atenea.persistence.worksession;

import java.util.List;

public enum AgentRunStatus {
    QUEUED,
    STARTING,
    RUNNING,
    CANCELLING,
    RECONCILING,
    SUCCEEDED,
    FAILED,
    CANCELLED;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED;
    }

    public boolean isNonTerminal() {
        return !isTerminal();
    }

    public static List<AgentRunStatus> nonTerminalStatuses() {
        return List.of(QUEUED, STARTING, RUNNING, CANCELLING, RECONCILING);
    }
}
