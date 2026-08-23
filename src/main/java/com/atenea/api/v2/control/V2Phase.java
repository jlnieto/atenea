package com.atenea.api.v2.control;

public enum V2Phase {
    READY,
    IN_PROGRESS,
    RECONCILIATION_REQUIRED,
    WAITING_FOR_CAPACITY,
    ACTION_REQUIRED,
    BLOCKED,
    COMPLETED
}
