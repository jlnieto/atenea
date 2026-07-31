package com.atenea.persistence.worksession;

public enum AgentRunRecoveryState {
    REQUESTED,
    IN_PROGRESS,
    SUCCEEDED,
    REJECTED,
    FAILED;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == REJECTED || this == FAILED;
    }
}
