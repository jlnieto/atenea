package com.atenea.v2.control;

public enum V2FailureCategory {
    TRANSPORT,
    CAPACITY,
    VALIDATION,
    POLICY,
    OWNERSHIP;

    public boolean isTransportRetryable() {
        return this == TRANSPORT;
    }
}
