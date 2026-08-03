package com.atenea.persistence.worksession;

public enum AgentRunRecoveryNextAction {
    NONE,
    WAIT,
    RETRY,
    REQUEST_RECONCILIATION,
    CONTACT_PRIVILEGED_OPERATOR,
    CONTACT_PLATFORM_ADMINISTRATOR
}
