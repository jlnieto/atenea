package com.atenea.persistence.worksession;

import com.atenea.persistence.auth.CodexOperationsRole;

public enum AgentRunRecoveryAction {
    CANCEL(CodexOperationsRole.ROUTINE_OPERATOR),
    RETRY(CodexOperationsRole.ROUTINE_OPERATOR),
    RECONCILE(CodexOperationsRole.ROUTINE_OPERATOR),
    DIAGNOSTIC(CodexOperationsRole.ROUTINE_OPERATOR),
    RESTART_EXECUTION_SERVICE(CodexOperationsRole.PRIVILEGED_OPERATOR),
    RESTART_PROJECT_APP_SERVER(CodexOperationsRole.PRIVILEGED_OPERATOR);

    private final CodexOperationsRole minimumRole;

    AgentRunRecoveryAction(CodexOperationsRole minimumRole) {
        this.minimumRole = minimumRole;
    }

    public boolean isAllowedFor(CodexOperationsRole role) {
        return role.ordinal() >= minimumRole.ordinal();
    }
}
