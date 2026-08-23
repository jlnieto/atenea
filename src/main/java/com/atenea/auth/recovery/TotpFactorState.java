package com.atenea.auth.recovery;

public enum TotpFactorState {
    PENDING,
    ACTIVE,
    CANCELLED,
    EXPIRED,
    REVOKED
}
