package com.atenea.service.operations;

import com.atenea.api.operations.ManagedHostResponse;
import com.atenea.api.operations.ManagedServiceResponse;
import com.atenea.api.operations.OperationsActionRunResponse;
import com.atenea.api.operations.OperationsExecutionReportResponse;
import com.atenea.api.operations.OperationsExecutionStepResponse;
import com.atenea.api.operations.OperationsHostStatusResponse;
import com.atenea.api.operations.OperationsIncidentListResponse;
import com.atenea.api.operations.OperationsIncidentResponse;
import com.atenea.api.operations.OperationsRecoveryResponse;
import com.atenea.api.operations.OperationsServiceCheckResponse;
import com.atenea.api.operations.WebsiteCheckResponse;
import com.atenea.persistence.operations.ManagedHostEntity;
import com.atenea.persistence.operations.ManagedHostRepository;
import com.atenea.persistence.operations.ManagedServiceEntity;
import com.atenea.persistence.operations.ManagedServiceRepository;
import com.atenea.persistence.operations.ManagedServiceType;
import com.atenea.persistence.operations.ManagedWebsiteRepository;
import com.atenea.persistence.operations.OperationsActionRunEntity;
import com.atenea.persistence.operations.OperationsActionRunRepository;
import com.atenea.persistence.operations.OperationsActionRunStatus;
import com.atenea.persistence.operations.OperationsIncidentEntity;
import com.atenea.persistence.operations.OperationsIncidentRepository;
import com.atenea.persistence.operations.OperationsIncidentStatus;
import com.atenea.persistence.operations.OperationsSeverity;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OperationsService {

    private static final Collection<OperationsIncidentStatus> ACTIVE_INCIDENT_STATUSES = List.of(
            OperationsIncidentStatus.OPEN,
            OperationsIncidentStatus.MITIGATING,
            OperationsIncidentStatus.FAILED);

    private final ManagedHostRepository managedHostRepository;
    private final ManagedServiceRepository managedServiceRepository;
    private final ManagedWebsiteRepository managedWebsiteRepository;
    private final OperationsIncidentRepository operationsIncidentRepository;
    private final OperationsActionRunRepository operationsActionRunRepository;
    private final OperationsRemoteExecutor operationsRemoteExecutor;
    private final OperationsWebsiteCheckService operationsWebsiteCheckService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public OperationsService(
            ManagedHostRepository managedHostRepository,
            ManagedServiceRepository managedServiceRepository,
            ManagedWebsiteRepository managedWebsiteRepository,
            OperationsIncidentRepository operationsIncidentRepository,
            OperationsActionRunRepository operationsActionRunRepository,
            OperationsRemoteExecutor operationsRemoteExecutor,
            OperationsWebsiteCheckService operationsWebsiteCheckService
    ) {
        this.managedHostRepository = managedHostRepository;
        this.managedServiceRepository = managedServiceRepository;
        this.managedWebsiteRepository = managedWebsiteRepository;
        this.operationsIncidentRepository = operationsIncidentRepository;
        this.operationsActionRunRepository = operationsActionRunRepository;
        this.operationsRemoteExecutor = operationsRemoteExecutor;
        this.operationsWebsiteCheckService = operationsWebsiteCheckService;
    }

    @Transactional(readOnly = true)
    public List<ManagedHostResponse> listHosts() {
        return managedHostRepository.findByActiveTrueOrderByNameAsc().stream()
                .map(this::toHostResponse)
                .toList();
    }

    @Transactional
    public OperationsHostStatusResponse getHostStatus(Long hostId) {
        ManagedHostEntity host = requireHost(hostId);
        OperationsActionRunEntity hostStatusRun = executeRemote(host, null, null, "HOST_STATUS",
                "sudo /usr/local/sbin/atenea-host-status", Duration.ofSeconds(20));
        return new OperationsHostStatusResponse(
                toHostResponse(host),
                toActionRunResponse(hostStatusRun),
                listServices(host.getId()),
                checkWebsites(host.getId()),
                activeIncidentsForHost(host.getId()));
    }

    @Transactional
    public OperationsServiceCheckResponse checkService(Long hostId, String requestedServiceName) {
        ManagedHostEntity host = requireHost(hostId);
        ManagedServiceEntity service = resolveService(hostId, requestedServiceName);
        OperationsActionRunEntity run = executeRemote(host, service, null, "SERVICE_CHECK",
                serviceCheckCommand(service), Duration.ofSeconds(20));
        OperationsIncidentEntity incident = null;
        if (run.getStatus() == OperationsActionRunStatus.FAILED) {
            incident = openOrUpdateIncident(
                    host,
                    service,
                    OperationsSeverity.WARNING,
                    service.getName() + " requiere atención",
                    "El diagnóstico remoto de " + service.getName() + " terminó con error.");
        } else {
            incident = resolveServiceIncidentIfHealthy(host, service);
        }
        return new OperationsServiceCheckResponse(
                toHostResponse(host),
                toServiceResponse(service),
                toActionRunResponse(run),
                incident == null ? null : toIncidentResponse(incident));
    }

    @Transactional
    public OperationsRecoveryResponse recoverApacheHungProcesses(Long hostId) {
        ManagedHostEntity host = requireHost(hostId);
        ManagedServiceEntity service = resolveService(hostId, "apache");
        OperationsIncidentEntity incident = openOrUpdateIncident(
                host,
                service,
                OperationsSeverity.CRITICAL,
                "Recuperación controlada de Apache",
                "Atenea va a ejecutar el runbook de recuperación de Apache.");
        incident.setStatus(OperationsIncidentStatus.MITIGATING);
        touchIncident(incident, Instant.now());

        OperationsActionRunEntity run = executeRemote(host, service, incident, "APACHE_RECOVERY",
                "sudo /usr/local/sbin/atenea-apache-recover", Duration.ofSeconds(90));
        List<WebsiteCheckResponse> validationChecks = checkWebsites(host.getId());
        boolean websitesHealthy = validationChecks.stream().allMatch(WebsiteCheckResponse::healthy);
        if (run.getStatus() == OperationsActionRunStatus.SUCCEEDED && websitesHealthy) {
            incident.setStatus(OperationsIncidentStatus.RESOLVED);
            incident.setSeverity(OperationsSeverity.INFO);
            incident.setSummary("Recuperación de Apache completada y webs validadas correctamente.");
            incident.setResolvedAt(Instant.now());
        } else {
            incident.setStatus(OperationsIncidentStatus.FAILED);
            incident.setSeverity(OperationsSeverity.CRITICAL);
            incident.setSummary("La recuperación de Apache no ha dejado todos los checks en estado correcto.");
        }
        touchIncident(incident, Instant.now());

        return new OperationsRecoveryResponse(
                toHostResponse(host),
                toServiceResponse(service),
                toActionRunResponse(run),
                toIncidentResponse(incident),
                validationChecks);
    }

    @Transactional(readOnly = true)
    public OperationsIncidentListResponse listActiveIncidents() {
        return new OperationsIncidentListResponse(operationsIncidentRepository
                .findByStatusInOrderByLastActivityAtDesc(ACTIVE_INCIDENT_STATUSES)
                .stream()
                .map(this::toIncidentResponse)
                .toList());
    }

    @Transactional
    public OperationsIncidentResponse closeIncident(Long incidentId) {
        OperationsIncidentEntity incident = operationsIncidentRepository.findById(incidentId)
                .orElseThrow(() -> new OperationsIncidentNotFoundException(incidentId));
        incident.setStatus(OperationsIncidentStatus.RESOLVED);
        incident.setSeverity(OperationsSeverity.INFO);
        incident.setSummary(firstNonBlank(incident.getSummary(), "Incidente cerrado por el operador."));
        incident.setResolvedAt(Instant.now());
        touchIncident(incident, Instant.now());
        return toIncidentResponse(incident);
    }

    private ManagedHostEntity requireHost(Long hostId) {
        return managedHostRepository.findByIdAndActiveTrue(hostId)
                .orElseThrow(() -> new OperationsHostNotFoundException(hostId));
    }

    private ManagedServiceEntity resolveService(Long hostId, String requestedServiceName) {
        String serviceName = requestedServiceName == null || requestedServiceName.isBlank()
                ? "apache"
                : requestedServiceName.trim();
        if ("apache".equalsIgnoreCase(serviceName)) {
            return managedServiceRepository
                    .findFirstByHostIdAndServiceTypeAndActiveTrueOrderByNameAsc(hostId, ManagedServiceType.WEB_SERVER)
                    .or(() -> managedServiceRepository.findFirstByHostIdAndNameIgnoreCaseAndActiveTrue(hostId, serviceName))
                    .orElseThrow(() -> new OperationsServiceNotFoundException(hostId, serviceName));
        }
        return managedServiceRepository.findFirstByHostIdAndNameIgnoreCaseAndActiveTrue(hostId, serviceName)
                .orElseThrow(() -> new OperationsServiceNotFoundException(hostId, serviceName));
    }

    private List<ManagedServiceResponse> listServices(Long hostId) {
        return managedServiceRepository.findByHostIdAndActiveTrueOrderByNameAsc(hostId).stream()
                .map(this::toServiceResponse)
                .toList();
    }

    private List<WebsiteCheckResponse> checkWebsites(Long hostId) {
        return managedWebsiteRepository.findByHostIdAndActiveTrueOrderByNameAsc(hostId)
                .stream()
                .map(operationsWebsiteCheckService::check)
                .toList();
    }

    private List<OperationsIncidentResponse> activeIncidentsForHost(Long hostId) {
        return operationsIncidentRepository.findByStatusInOrderByLastActivityAtDesc(ACTIVE_INCIDENT_STATUSES)
                .stream()
                .filter(incident -> incident.getHost().getId().equals(hostId))
                .map(this::toIncidentResponse)
                .toList();
    }

    private OperationsIncidentEntity openOrUpdateIncident(
            ManagedHostEntity host,
            ManagedServiceEntity service,
            OperationsSeverity severity,
            String title,
            String summary
    ) {
        Instant now = Instant.now();
        OperationsIncidentEntity incident = operationsIncidentRepository
                .findFirstByHostIdAndServiceIdAndStatusInOrderByLastActivityAtDesc(
                        host.getId(),
                        service.getId(),
                        ACTIVE_INCIDENT_STATUSES)
                .orElseGet(() -> {
                    OperationsIncidentEntity created = new OperationsIncidentEntity();
                    created.setHost(host);
                    created.setService(service);
                    created.setStatus(OperationsIncidentStatus.OPEN);
                    created.setOpenedAt(now);
                    created.setCreatedAt(now);
                    return created;
                });
        incident.setSeverity(severity);
        incident.setTitle(title);
        incident.setSummary(summary);
        touchIncident(incident, now);
        return operationsIncidentRepository.save(incident);
    }

    private OperationsIncidentEntity resolveServiceIncidentIfHealthy(ManagedHostEntity host, ManagedServiceEntity service) {
        return operationsIncidentRepository
                .findFirstByHostIdAndServiceIdAndStatusInOrderByLastActivityAtDesc(
                        host.getId(),
                        service.getId(),
                        ACTIVE_INCIDENT_STATUSES)
                .map(incident -> {
                    incident.setStatus(OperationsIncidentStatus.RESOLVED);
                    incident.setSeverity(OperationsSeverity.INFO);
                    incident.setSummary("Diagnóstico actual correcto; incidencia cerrada automáticamente por Atenea.");
                    incident.setResolvedAt(Instant.now());
                    touchIncident(incident, Instant.now());
                    return operationsIncidentRepository.save(incident);
                })
                .orElse(null);
    }

    private OperationsActionRunEntity executeRemote(
            ManagedHostEntity host,
            ManagedServiceEntity service,
            OperationsIncidentEntity incident,
            String action,
            String command,
            Duration timeout
    ) {
        Instant started = Instant.now();
        OperationsActionRunEntity run = new OperationsActionRunEntity();
        run.setHost(host);
        run.setService(service);
        run.setIncident(incident);
        run.setAction(action);
        run.setStatus(OperationsActionRunStatus.RUNNING);
        run.setStartedAt(started);
        run.setCreatedAt(started);
        run = operationsActionRunRepository.save(run);

        try {
            RemoteCommandResult result = operationsRemoteExecutor.execute(host, command, timeout);
            run.setExitCode(result.exitCode());
            run.setStdoutSummary(preview(result.stdout()));
            run.setStderrSummary(preview(result.stderr()));
            run.setResultJson(jsonOrNull(result.stdout()));
            run.setStatus(result.exitCode() == 0 ? OperationsActionRunStatus.SUCCEEDED : OperationsActionRunStatus.FAILED);
        } catch (RuntimeException exception) {
            run.setStatus(OperationsActionRunStatus.FAILED);
            run.setStderrSummary(preview(exception.getMessage()));
        }
        run.setFinishedAt(Instant.now());
        return operationsActionRunRepository.save(run);
    }

    private String serviceCheckCommand(ManagedServiceEntity service) {
        if (service.getServiceType() == ManagedServiceType.WEB_SERVER) {
            return "sudo /usr/local/sbin/atenea-apache-status";
        }
        String unit = firstNonBlank(service.getSystemdUnit(), service.getName());
        return "systemctl is-active " + shellSingleQuote(unit);
    }

    private ManagedHostResponse toHostResponse(ManagedHostEntity host) {
        return new ManagedHostResponse(
                host.getId(),
                host.getName(),
                host.getDescription(),
                host.getEnvironment(),
                host.isActive());
    }

    private ManagedServiceResponse toServiceResponse(ManagedServiceEntity service) {
        return new ManagedServiceResponse(
                service.getId(),
                service.getHost().getId(),
                service.getName(),
                service.getServiceType(),
                service.getSystemdUnit(),
                service.getProcessPattern(),
                service.isActive());
    }

    private OperationsIncidentResponse toIncidentResponse(OperationsIncidentEntity incident) {
        ManagedServiceEntity service = incident.getService();
        return new OperationsIncidentResponse(
                incident.getId(),
                incident.getHost().getId(),
                incident.getHost().getName(),
                service == null ? null : service.getId(),
                service == null ? null : service.getName(),
                incident.getStatus(),
                incident.getSeverity(),
                incident.getTitle(),
                incident.getSummary(),
                incident.getOpenedAt(),
                incident.getLastActivityAt(),
                incident.getResolvedAt());
    }

    private OperationsActionRunResponse toActionRunResponse(OperationsActionRunEntity run) {
        return new OperationsActionRunResponse(
                run.getId(),
                run.getIncident() == null ? null : run.getIncident().getId(),
                run.getHost().getId(),
                run.getService() == null ? null : run.getService().getId(),
                run.getAction(),
                run.getStatus(),
                run.getExitCode(),
                run.getStdoutSummary(),
                run.getStderrSummary(),
                reportFromJson(run.getResultJson()),
                run.getStartedAt(),
                run.getFinishedAt());
    }

    private OperationsExecutionReportResponse reportFromJson(String resultJson) {
        if (resultJson == null || resultJson.isBlank()) {
            return null;
        }
        try {
            Map<String, Object> root = objectMapper.readValue(
                    resultJson,
                    new TypeReference<LinkedHashMap<String, Object>>() {
                    });
            Map<String, Object> metrics = metricsFrom(root.get("metrics"));
            if (metrics.isEmpty()) {
                metrics = legacyMetricsFrom(root);
            }
            return new OperationsExecutionReportResponse(
                    textValue(root.get("action")),
                    textValue(root.get("host")),
                    textValue(root.get("status")),
                    firstNonBlank(textValue(root.get("summary")), legacySummary(root)),
                    stepsFrom(root.get("steps")),
                    metrics);
        } catch (JsonProcessingException exception) {
            return null;
        }
    }

    private List<OperationsExecutionStepResponse> stepsFrom(Object value) {
        if (!(value instanceof List<?> entries)) {
            return List.of();
        }
        return entries.stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .map(entry -> new OperationsExecutionStepResponse(
                        textValue(entry.get("name")),
                        textValue(entry.get("status")),
                        textValue(entry.get("detail"))))
                .toList();
    }

    private Map<String, Object> metricsFrom(Object value) {
        if (!(value instanceof Map<?, ?> metrics)) {
            return Collections.emptyMap();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : metrics.entrySet()) {
            if (entry.getKey() instanceof String key) {
                result.put(key, entry.getValue());
            }
        }
        return result;
    }

    private String textValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Map<String, Object> legacyMetricsFrom(Map<String, Object> root) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : root.entrySet()) {
            String key = entry.getKey();
            if ("action".equals(key)
                    || "host".equals(key)
                    || "status".equals(key)
                    || "summary".equals(key)
                    || "steps".equals(key)
                    || "metrics".equals(key)) {
                continue;
            }
            if (entry.getValue() instanceof Map<?, ?> || entry.getValue() instanceof List<?>) {
                continue;
            }
            result.put(key, entry.getValue());
        }
        return result;
    }

    private String legacySummary(Map<String, Object> root) {
        if (root.containsKey("unit") && root.containsKey("active")) {
            return "Servicio " + root.get("unit")
                    + ": active=" + root.get("active")
                    + ", failed=" + root.getOrDefault("failed", "desconocido")
                    + ", procesos=" + root.getOrDefault("processCount", "desconocido")
                    + ".";
        }
        if (root.containsKey("load1") || root.containsKey("rootDiskUsed")) {
            return "Servidor: carga " + root.getOrDefault("load1", "desconocida")
                    + ", memoria disponible " + root.getOrDefault("memAvailableKb", "desconocida")
                    + " KB, disco raíz " + root.getOrDefault("rootDiskUsed", "desconocido") + ".";
        }
        return null;
    }

    private void touchIncident(OperationsIncidentEntity incident, Instant timestamp) {
        incident.setLastActivityAt(timestamp);
        incident.setUpdatedAt(timestamp);
    }

    private String preview(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= 500) {
            return normalized;
        }
        return normalized.substring(0, 497).trim() + "...";
    }

    private String jsonOrNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.startsWith("{") || trimmed.startsWith("[") ? trimmed : null;
    }

    private String shellSingleQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
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
