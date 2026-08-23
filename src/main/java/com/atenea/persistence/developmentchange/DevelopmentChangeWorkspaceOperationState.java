package com.atenea.persistence.developmentchange;

public enum DevelopmentChangeWorkspaceOperationState {
    REQUESTED,
    DISPATCHED,
    SUCCEEDED,
    UNCERTAIN,
    BLOCKED;

    public boolean terminal() {
        return this == SUCCEEDED || this == UNCERTAIN || this == BLOCKED;
    }
}
