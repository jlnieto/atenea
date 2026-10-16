package com.atenea.service.mobile;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Allowlisted, recorded state. No paths, credentials, raw logs or commands. */
public record ChangeHelpContext(
        Long sessionId, Long projectId, UUID changeKey, Long sourceRevision, Instant observedAt,
        String changeStatus, String sessionStatus, String workspaceState, String sourceState,
        String validationState, String pullRequestState, String integrationState,
        String releaseState, boolean runInProgress, boolean validationInProgress,
        boolean sourceUpdateInProgress, String remoteCloseState, Long latestRunId,
        String latestRunStatus, String runFailureCode, boolean agentReportedGitInstructions,
        int passedChecks, int requiredChecks, String failedCheck, String validationFailure,
        String deliveryKind, String deliveryState, String deliveryError,
        String operatorRole, boolean deliveryEnabled, String runFailure,
        String deliveryTarget, Long deliveryExpiresAt, String deploymentStatus, boolean deliveryPlanVerified,
        com.atenea.delivery.ChangeDeliveryReadiness deliveryReadiness, Integer reportedInstalledVersionCode,
        String sourceBaseStatus
) {
    public List<String> facts() {
        var facts = new java.util.ArrayList<String>();
        facts.add("Cambio: " + readable(changeStatus) + "; sesión: " + readable(sessionStatus) + ".");
        facts.add("Espacio de trabajo: " + readable(workspaceState) + "; código: " + readable(sourceState) + ".");
        if (latestRunId != null) facts.add("Última tarea " + latestRunId + ": " + readable(latestRunStatus) + ".");
        facts.add(requiredChecks > 0 ? "Pruebas de esta revisión: " + passedChecks + "/" + requiredChecks + " correctas."
                : "No hay un catálogo de comprobaciones confirmado para este proyecto.");
        if (failedCheck != null) facts.add("Comprobación con atención pendiente: " + failedCheck + ".");
        if (runFailureCode != null) facts.add("Código de la tarea: " + runFailureCode + ".");
        if (runFailure != null) facts.add("Error guardado de la tarea: " + runFailure);
        if (validationFailure != null) facts.add("Resultado guardado: " + validationFailure);
        if (deliveryState != null) facts.add("SOURCE_UPDATE".equals(deliveryKind)
                ? "Actualización de la misma PR: " + readable(deliveryState) + "."
                : "Última operación de entrega: " + deliveryKind + " / " + deliveryState + ".");
        if (deliveryError != null) facts.add("Código de entrega: " + deliveryError + ".");
        if ("UPDATE_REQUIRED".equals(sourceBaseStatus)) facts.add("Base preparada: anterior a main; falta actualizar la misma PR antes de validar o publicar.");
        if ("UNAVAILABLE".equals(sourceBaseStatus)) facts.add("Base preparada: no se pudo confirmar su vigencia en GitHub.");
        if (deliveryReadiness!=null) {
            if ("CONFIRMED".equals(deliveryReadiness.requirementsStatus())) facts.add("Publicaciones requeridas por el diff: " + deliveryReadiness.requiredTargets() + ".");
            if (deliveryReadiness.android()!=null) {
                var apk=deliveryReadiness.android();
                facts.add("APK del canal: " + apk.versionName()+" / "+apk.versionCode()+"; fuente "+apk.sourceCommit().substring(0,12)+"; contiene este cambio: "+deliveryReadiness.androidStatus()+".");
            }
        }
        if (reportedInstalledVersionCode!=null) facts.add("Versión instalada informada por este móvil: " + reportedInstalledVersionCode + ".");
        if ("DEPLOYED".equals(deploymentStatus)) facts.add("Backend PROD: sano y contiene el cambio integrado.");
        else if ("NOT_INCLUDED".equals(deploymentStatus)) facts.add("Backend PROD: todavía no contiene este cambio.");
        else if ("UNAVAILABLE".equals(deploymentStatus) || "UNHEALTHY".equals(deploymentStatus)) {
            facts.add("Backend PROD: no se pudo confirmar un despliegue sano de este cambio.");
        }
        return List.copyOf(facts);
    }
    public String deliveryTargetLabel() {
        return switch (deliveryTarget == null ? "" : deliveryTarget) {
            case "APP_PROD" -> "Backend PROD";
            case "ANDROID_STABLE" -> "Android estable";
            case "AX42_PLATFORM" -> "Worker AX42";
            default -> "la publicación";
        };
    }
    private static String readable(String status) {
        if (status == null) return "no confirmado";
        return switch (status) {
            case "OPEN" -> "en curso";
            case "CLOSED" -> "cerrada";
            case "COMPLETED" -> "completado";
            case "READY" -> "preparado";
            case "DIRTY" -> "con modificaciones guardadas";
            case "CLEAN" -> "sin cambios pendientes";
            case "SUCCEEDED" -> "terminada correctamente";
            case "FAILED" -> "fallida";
            case "BLOCKED" -> "bloqueado";
            case "ATTENTION" -> "detenida; necesita atención";
            case "QUEUED", "PREPARE_CLAIMED" -> "preparando el código aprobado y los conflictos";
            case "UNCERTAIN" -> "comprobando el resultado de la misma operación";
            case "READY_TO_RESOLVE" -> "preparación terminada; iniciando la resolución";
            case "RESOLVING" -> "Codex está resolviendo los conflictos";
            case "UNKNOWN" -> "no confirmado";
            default -> status;
        };
    }
}
