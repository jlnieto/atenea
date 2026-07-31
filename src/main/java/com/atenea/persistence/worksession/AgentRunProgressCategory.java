package com.atenea.persistence.worksession;

public enum AgentRunProgressCategory {
    ACCEPTED("Solicitud aceptada", AgentRunProgressNextAction.CANCEL),
    QUEUED("Ejecución en cola", AgentRunProgressNextAction.CANCEL),
    PREPARING_WORKSPACE("Preparando el espacio de trabajo", AgentRunProgressNextAction.CANCEL),
    CODEX_STARTED("Codex iniciado", AgentRunProgressNextAction.CANCEL),
    INSPECTING_PROJECT("Revisando el proyecto", AgentRunProgressNextAction.CANCEL),
    RUNNING_COMMAND("Ejecutando una operación permitida", AgentRunProgressNextAction.CANCEL),
    CHECKING("Comprobando el resultado", AgentRunProgressNextAction.CANCEL),
    WAITING("Esperando una condición necesaria", AgentRunProgressNextAction.WAIT),
    RECONCILING("Reconciliando el estado", AgentRunProgressNextAction.WAIT),
    FINALIZING("Finalizando", AgentRunProgressNextAction.CANCEL),
    COMPLETED("Tarea completada", AgentRunProgressNextAction.NONE),
    FAILED("La tarea necesita atención", AgentRunProgressNextAction.RETRY),
    CANCELLED("Tarea cancelada", AgentRunProgressNextAction.NONE);

    private final String operatorMessage;
    private final AgentRunProgressNextAction nextAction;

    AgentRunProgressCategory(String operatorMessage, AgentRunProgressNextAction nextAction) {
        this.operatorMessage = operatorMessage;
        this.nextAction = nextAction;
    }

    public String operatorMessage() {
        return operatorMessage;
    }

    public AgentRunProgressNextAction nextAction() {
        return nextAction;
    }

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }
}
