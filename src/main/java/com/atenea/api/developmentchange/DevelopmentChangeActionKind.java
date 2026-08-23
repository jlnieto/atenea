package com.atenea.api.developmentchange;

public enum DevelopmentChangeActionKind {
    WAIT_FOR_ENABLEMENT,
    WAIT_FOR_WORKSPACE,
    PROVISION_WORKSPACE,
    INSPECT_WORKSPACE,
    RECONCILE_WORKSPACE,
    REVIEW_STALE_SOURCE,
    RESOLVE_OWNERSHIP,
    BIND_SESSION,
    CONTINUE_SESSION,
    NONE
}
