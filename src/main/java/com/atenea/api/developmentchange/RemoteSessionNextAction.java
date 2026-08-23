package com.atenea.api.developmentchange;

public enum RemoteSessionNextAction {
    CONTINUE_SESSION,
    READ_PAUSED_SESSION,
    WAIT_FOR_CLOSE,
    RESOLVE_OWNERSHIP,
    WAIT_FOR_ENABLEMENT,
    REFRESH_CHANGE,
    NONE
}
