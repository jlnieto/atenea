package com.atenea.persistence.worksession;

public enum AgentRunRecoveryNextAction {
    NONE,
    WAIT,
    RETRY,
    REQUEST_RECONCILIATION,
    RECONCILE_REMOTE_CLOSE,
    CONTACT_PRIVILEGED_OPERATOR,
    CONTACT_PLATFORM_ADMINISTRATOR
}
