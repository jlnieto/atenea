package com.atenea.persistence.v2control;

public enum V2OutboxState {
    PENDING,
    PUBLISHING,
    RETRY_WAIT,
    PUBLISHED,
    FAILED
}
