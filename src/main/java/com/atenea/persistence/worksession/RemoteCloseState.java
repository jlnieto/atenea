package com.atenea.persistence.worksession;

public enum RemoteCloseState {
    NOT_REQUIRED,
    NOT_STARTED,
    REQUESTED,
    RECONCILING,
    BLOCKED,
    RELEASED,
    UNVERIFIED_LEGACY
}
