package com.atenea.persistence.core;

public enum CoreCommandEventPhase {
    RECEIVED,
    INTERPRETING,
    RESOLVING_CONTEXT,
    NEEDS_CLARIFICATION,
    NEEDS_CONFIRMATION,
    EXECUTING,
    SUCCEEDED,
    REJECTED,
    FAILED
}
