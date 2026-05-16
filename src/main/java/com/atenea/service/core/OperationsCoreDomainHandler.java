package com.atenea.service.core;

import com.atenea.api.operations.ManagedHostResponse;
import com.atenea.api.operations.OperationsActionRunResponse;
import com.atenea.api.operations.OperationsExecutionReportResponse;
import com.atenea.api.operations.OperationsExecutionStepResponse;
import com.atenea.api.operations.OperationsHostStatusResponse;
import com.atenea.api.operations.OperationsIncidentListResponse;
import com.atenea.api.operations.OperationsIncidentResponse;
import com.atenea.api.operations.OperationsRecoveryResponse;
import com.atenea.api.operations.OperationsServiceCheckResponse;
import com.atenea.api.operations.WebsiteCheckResponse;
import com.atenea.persistence.core.CoreDomain;
import com.atenea.persistence.core.CoreResultType;
import com.atenea.persistence.core.CoreTargetType;
import com.atenea.service.operations.OperationsService;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import org.springframework.stereotype.Service;

@Service
public class OperationsCoreDomainHandler implements CoreDomainHandler {

    private final OperationsService operationsService;

    public OperationsCoreDomainHandler(OperationsService operationsService) {
        this.operationsService = operationsService;
    }

    @Override
    public CoreDomain domain() {
        return CoreDomain.OPERATIONS;
    }

    @Override
    public CoreCommandExecutionResult execute(CoreIntentEnvelope intent, CoreExecutionContext context) {
        return switch (intent.capability()) {
            case "list_hosts" -> listHosts();
            case "get_host_status" -> getHostStatus(intent.parameters());
            case "check_service" -> checkService(intent.parameters());
            case "recover_apache_hung_processes" -> recoverApacheHungProcesses(intent.parameters());
            case "list_operations_incidents" -> listOperationsIncidents();
            case "close_operations_incident" -> closeOperationsIncident(intent.parameters());
            default -> throw new CoreCapabilityDisabledException(
                    "Operations capability is not supported by the current Core handler: " + intent.capability());
        };
    }

    private CoreCommandExecutionResult listHosts() {
        List<ManagedHostResponse> hosts = operationsService.listHosts();
        String message = hosts.isEmpty()
                ? "No hay servidores operativos registrados en Atenea."
                : "Hay " + hosts.size() + " servidor" + (hosts.size() == 1 ? "" : "es")
                + " operativo" + (hosts.size() == 1 ? "" : "s") + " registrado" + (hosts.size() == 1 ? "" : "s") + ".";
        return new CoreCommandExecutionResult(
                CoreResultType.OPERATIONS_HOST_LIST,
                null,
                null,
                hosts,
                "Returned " + hosts.size() + " managed hosts",
                message,
                message);
    }

    private CoreCommandExecutionResult getHostStatus(Map<String, Object> parameters) {
        Long hostId = requireLong(parameters, "hostId");
        OperationsHostStatusResponse response = operationsService.getHostStatus(hostId);
        long unhealthyWebsites = response.websiteChecks().stream()
                .filter(check -> !check.healthy())
                .count();
        String result = unhealthyWebsites == 0
                ? "He revisado " + response.host().name() + ". Los checks web configurados responden correctamente."
                : "He revisado " + response.host().name() + ". Hay " + unhealthyWebsites
                + " check" + (unhealthyWebsites == 1 ? "" : "s") + " web con problema.";
        String message = buildDetailedMessage(
                "He revisado el servidor `" + response.host().name() + "`.",
                response.hostStatusRun(),
                response.websiteChecks(),
                result);
        return new CoreCommandExecutionResult(
                CoreResultType.OPERATIONS_HOST_STATUS,
                CoreTargetType.MANAGED_HOST,
                hostId,
                response,
                "Checked managed host " + hostId,
                message,
                message);
    }

    private CoreCommandExecutionResult checkService(Map<String, Object> parameters) {
        Long hostId = requireLong(parameters, "hostId");
        String serviceName = text(parameters, "serviceName", "apache");
        OperationsServiceCheckResponse response = operationsService.checkService(hostId, serviceName);
        String result = response.actionRun().status().name().equals("SUCCEEDED")
                ? "El diagnóstico de " + response.service().name() + " en " + response.host().name() + " ha terminado correctamente."
                : "El diagnóstico de " + response.service().name() + " en " + response.host().name() + " ha detectado un problema.";
        String message = buildDetailedMessage(
                "He diagnosticado `" + response.service().name() + "` en `" + response.host().name() + "`.",
                response.actionRun(),
                List.of(),
                result);
        return new CoreCommandExecutionResult(
                CoreResultType.OPERATIONS_SERVICE_CHECK,
                CoreTargetType.MANAGED_SERVICE,
                response.service().id(),
                response,
                "Checked service " + response.service().id(),
                message,
                message);
    }

    private CoreCommandExecutionResult recoverApacheHungProcesses(Map<String, Object> parameters) {
        Long hostId = requireLong(parameters, "hostId");
        OperationsRecoveryResponse response = operationsService.recoverApacheHungProcesses(hostId);
        long unhealthyWebsites = response.validationChecks().stream()
                .filter(check -> !check.healthy())
                .count();
        String result = unhealthyWebsites == 0
                ? "He ejecutado la recuperación controlada de Apache en " + response.host().name()
                + " y las webs configuradas responden correctamente."
                : "He ejecutado la recuperación controlada de Apache en " + response.host().name()
                + ", pero quedan " + unhealthyWebsites + " checks web con problema.";
        String message = buildDetailedMessage(
                "He ejecutado la recuperación controlada de Apache en `" + response.host().name() + "`.",
                response.actionRun(),
                response.validationChecks(),
                result);
        return new CoreCommandExecutionResult(
                CoreResultType.OPERATIONS_ACTION_RUN,
                CoreTargetType.OPERATIONS_ACTION_RUN,
                response.actionRun().id(),
                response,
                "Executed Apache recovery on host " + hostId,
                message,
                message);
    }

    private CoreCommandExecutionResult listOperationsIncidents() {
        OperationsIncidentListResponse response = operationsService.listActiveIncidents();
        String message = response.incidents().isEmpty()
                ? "No hay incidencias operativas abiertas."
                : "Hay " + response.incidents().size() + " incidencia"
                + (response.incidents().size() == 1 ? "" : "s") + " operativa"
                + (response.incidents().size() == 1 ? "" : "s") + " abierta"
                + (response.incidents().size() == 1 ? "" : "s") + ".";
        return new CoreCommandExecutionResult(
                CoreResultType.OPERATIONS_INCIDENT,
                null,
                null,
                response,
                "Returned active operations incidents",
                message,
                message);
    }

    private CoreCommandExecutionResult closeOperationsIncident(Map<String, Object> parameters) {
        Long incidentId = requireLong(parameters, "incidentId");
        OperationsIncidentResponse response = operationsService.closeIncident(incidentId);
        String message = "He cerrado la incidencia operativa " + incidentId + ".";
        return new CoreCommandExecutionResult(
                CoreResultType.OPERATIONS_INCIDENT,
                CoreTargetType.OPERATIONS_INCIDENT,
                incidentId,
                response,
                "Closed operations incident " + incidentId,
                message,
                message);
    }

    private Long requireLong(Map<String, Object> parameters, String name) {
        Object value = parameters.get(name);
        if (value instanceof Number number && number.longValue() > 0) {
            return number.longValue();
        }
        throw new CoreInvalidContextException("Missing or invalid Core parameter: " + name);
    }

    private String text(Map<String, Object> parameters, String name, String fallback) {
        Object value = parameters.get(name);
        if (value instanceof String text && !text.isBlank()) {
            return text.trim();
        }
        return fallback;
    }

    private String buildDetailedMessage(
            String title,
            OperationsActionRunResponse run,
            List<WebsiteCheckResponse> websiteChecks,
            String result
    ) {
        StringBuilder message = new StringBuilder(title);
        OperationsExecutionReportResponse report = run.report();

        if (report != null && report.summary() != null && !report.summary().isBlank()) {
            message.append("\n\nResultado del script:\n- ").append(report.summary().trim());
        } else if (run.stdoutSummary() != null && !run.stdoutSummary().isBlank()) {
            message.append("\n\nSalida del script:\n- ").append(run.stdoutSummary().trim());
        }

        if (report != null && !report.steps().isEmpty()) {
            message.append("\n\nQué he hecho:");
            for (OperationsExecutionStepResponse step : report.steps()) {
                message.append("\n- ")
                        .append(label(step.name()))
                        .append(": ")
                        .append(firstNonBlank(step.detail(), step.status(), "sin detalle"));
            }
        }

        if (report != null && !report.metrics().isEmpty()) {
            message.append("\n\nDatos relevantes:");
            report.metrics().entrySet().stream()
                    .limit(8)
                    .forEach(entry -> message.append("\n- ")
                            .append(label(entry.getKey()))
                            .append(": ")
                            .append(String.valueOf(entry.getValue())));
        }

        message.append("\n\nCómo lo he comprobado:");
        message.append("\n- Script remoto `")
                .append(run.action())
                .append("`: estado ")
                .append(run.status())
                .append(run.exitCode() == null ? "" : ", exit code " + run.exitCode())
                .append(".");

        if (report != null && report.status() != null && !report.status().isBlank()) {
            message.append("\n- Estado declarado por el script: ").append(report.status()).append(".");
        }

        if (websiteChecks != null && !websiteChecks.isEmpty()) {
            long healthy = websiteChecks.stream().filter(WebsiteCheckResponse::healthy).count();
            message.append("\n- Checks web externos desde Atenea: ")
                    .append(healthy)
                    .append("/")
                    .append(websiteChecks.size())
                    .append(" correctos.");
            for (WebsiteCheckResponse check : websiteChecks) {
                message.append("\n  - ")
                        .append(check.name())
                        .append(": ");
                if (check.healthy()) {
                    message.append("HTTP ")
                            .append(check.statusCode())
                            .append(" en ")
                            .append(check.durationMillis())
                            .append(" ms.");
                } else {
                    message.append("falló");
                    if (check.statusCode() != null) {
                        message.append(" con HTTP ").append(check.statusCode());
                    }
                    if (check.error() != null && !check.error().isBlank()) {
                        message.append(" (").append(check.error()).append(")");
                    }
                    message.append(".");
                }
            }
        }

        if (run.stderrSummary() != null && !run.stderrSummary().isBlank()) {
            message.append("\n\nAvisos o errores capturados:\n- ").append(run.stderrSummary().trim());
        }

        message.append("\n\nResultado: ").append(result);
        return message.toString();
    }

    private String label(String value) {
        if (value == null || value.isBlank()) {
            return "paso";
        }
        String spaced = value
                .replaceAll("([a-z])([A-Z])", "$1 $2")
                .replace('_', ' ')
                .replace('-', ' ')
                .trim()
                .toLowerCase(Locale.ROOT);
        if (spaced.isBlank()) {
            return "paso";
        }
        return Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }
}
