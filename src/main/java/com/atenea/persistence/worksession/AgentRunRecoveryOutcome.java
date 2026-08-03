package com.atenea.persistence.worksession;

public enum AgentRunRecoveryOutcome {
    CANCELLED("Ejecución cancelada", AgentRunRecoveryNextAction.NONE),
    RETRY_CREATED("Reintento creado", AgentRunRecoveryNextAction.WAIT),
    RECONCILED("Estado reconciliado", AgentRunRecoveryNextAction.NONE),
    DIAGNOSTIC_READY("Diagnóstico disponible", AgentRunRecoveryNextAction.NONE),
    SERVICE_RESTARTED("Servicio mediado reiniciado", AgentRunRecoveryNextAction.REQUEST_RECONCILIATION),
    OWNERSHIP_MISMATCH("La identidad no pertenece a esta sesión", AgentRunRecoveryNextAction.NONE),
    ROLE_REQUIRED("Se necesita un operador privilegiado", AgentRunRecoveryNextAction.CONTACT_PRIVILEGED_OPERATOR),
    NOT_TERMINAL("La ejecución anterior aún no es terminal", AgentRunRecoveryNextAction.REQUEST_RECONCILIATION),
    NON_TERMINAL_RUN_EXISTS("La sesión ya tiene una ejecución activa", AgentRunRecoveryNextAction.WAIT),
    WORKER_UNREACHABLE("El worker no está accesible", AgentRunRecoveryNextAction.REQUEST_RECONCILIATION),
    POLICY_BLOCKED("La política no permite esta operación", AgentRunRecoveryNextAction.CONTACT_PLATFORM_ADMINISTRATOR),
    EXECUTION_STILL_LIVE("La ejecución anterior sigue activa", AgentRunRecoveryNextAction.REQUEST_RECONCILIATION),
    NO_CHANGE("El estado ya era el solicitado", AgentRunRecoveryNextAction.NONE),
    OPERATION_FAILED("La operación no pudo completarse", AgentRunRecoveryNextAction.RETRY);

    private final String summary;
    private final AgentRunRecoveryNextAction nextAction;

    AgentRunRecoveryOutcome(String summary, AgentRunRecoveryNextAction nextAction) {
        this.summary = summary;
        this.nextAction = nextAction;
    }

    public String summary() { return summary; }
    public AgentRunRecoveryNextAction nextAction() { return nextAction; }
}
