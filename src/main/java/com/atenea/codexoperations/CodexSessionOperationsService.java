package com.atenea.codexoperations;

import com.atenea.auth.AuthenticatedOperator;
import com.atenea.persistence.auth.CodexOperationsRole;
import com.atenea.persistence.auth.OperatorPushDeviceEntity;
import com.atenea.persistence.auth.OperatorPushDeviceRepository;
import com.atenea.persistence.auth.OperatorRepository;
import com.atenea.persistence.notification.NotificationCategory;
import com.atenea.persistence.notification.NotificationPreferenceEntity;
import com.atenea.persistence.notification.NotificationPreferenceRepository;
import com.atenea.persistence.project.ProjectEntity;
import com.atenea.persistence.project.ProjectRepository;
import com.atenea.persistence.worksession.AgentRunEntity;
import com.atenea.persistence.worksession.AgentRunProgressEventEntity;
import com.atenea.persistence.worksession.AgentRunRecoveryAction;
import com.atenea.persistence.worksession.AgentRunRecoveryOperationEntity;
import com.atenea.persistence.worksession.AgentRunRepository;
import com.atenea.persistence.worksession.CodexReasoningEffort;
import com.atenea.persistence.worksession.WorkSessionEntity;
import com.atenea.persistence.worksession.WorkSessionRepository;
import com.atenea.service.worksession.AgentRunNotFoundException;
import com.atenea.service.worksession.AgentRunProgressReplay;
import com.atenea.service.worksession.AgentRunProgressService;
import com.atenea.service.worksession.AgentRunRecoveryOperationService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class CodexSessionOperationsService {

    private static final String WORKER_ID = "ax42-01";

    private final CodexSessionOperationsProperties properties;
    private final JdbcTemplate jdbcTemplate;
    private final ProjectRepository projectRepository;
    private final WorkSessionRepository sessionRepository;
    private final AgentRunRepository runRepository;
    private final AgentRunProgressService progressService;
    private final AgentRunRecoveryOperationService recoveryService;
    private final OperatorPushDeviceRepository deviceRepository;
    private final OperatorRepository operatorRepository;
    private final NotificationPreferenceRepository preferenceRepository;

    public CodexSessionOperationsService(
            CodexSessionOperationsProperties properties,
            JdbcTemplate jdbcTemplate,
            ProjectRepository projectRepository,
            WorkSessionRepository sessionRepository,
            AgentRunRepository runRepository,
            AgentRunProgressService progressService,
            AgentRunRecoveryOperationService recoveryService,
            OperatorPushDeviceRepository deviceRepository,
            OperatorRepository operatorRepository,
            NotificationPreferenceRepository preferenceRepository) {
        this.properties = properties;
        this.jdbcTemplate = jdbcTemplate;
        this.projectRepository = projectRepository;
        this.sessionRepository = sessionRepository;
        this.runRepository = runRepository;
        this.progressService = progressService;
        this.recoveryService = recoveryService;
        this.deviceRepository = deviceRepository;
        this.operatorRepository = operatorRepository;
        this.preferenceRepository = preferenceRepository;
    }

    @Transactional(readOnly = true)
    public CatalogResponse catalog() {
        require(properties.isProfilesEnabled(), "Codex profiles");
        List<CatalogHeader> headers = jdbcTemplate.query("""
                SELECT worker_id, catalog_revision, schema_version, codex_version,
                       generated_at, observed_at
                  FROM worker_codex_catalog
                 WHERE worker_id = ?
                 ORDER BY generated_at DESC, catalog_revision DESC
                 LIMIT 1
                """, (rs, row) -> new CatalogHeader(
                rs.getString("worker_id"), rs.getString("catalog_revision"),
                rs.getString("schema_version"), rs.getString("codex_version"),
                rs.getTimestamp("generated_at").toInstant(),
                rs.getTimestamp("observed_at").toInstant()), WORKER_ID);
        if (headers.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Worker catalog is unavailable");
        }
        CatalogHeader header = headers.getFirst();
        List<ModelResponse> models = jdbcTemplate.query("""
                SELECT model_id, display_name, default_effort, availability, position
                  FROM worker_codex_model
                 WHERE worker_id = ? AND catalog_revision = ?
                 ORDER BY position
                """, (rs, row) -> new ModelResponse(
                rs.getString("model_id"), rs.getString("display_name"),
                rs.getString("default_effort"), rs.getString("availability"),
                jdbcTemplate.queryForList("""
                        SELECT effort FROM worker_codex_model_effort
                         WHERE worker_id = ? AND catalog_revision = ? AND model_id = ?
                         ORDER BY position
                        """, String.class, WORKER_ID, header.catalogRevision(), rs.getString("model_id"))),
                WORKER_ID, header.catalogRevision());
        return new CatalogResponse(header.workerId(), header.catalogRevision(),
                header.schemaVersion(), header.codexVersion(), header.generatedAt(),
                header.observedAt(), List.copyOf(models));
    }

    @Transactional(readOnly = true)
    public SettingsResponse projectSettings(Long projectId) {
        require(properties.isProfilesEnabled(), "Codex profiles");
        ProjectEntity project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found"));
        return settings("PROJECT", project.getId(), project.getDefaultCodexModelId(),
                project.getDefaultCodexReasoningEffort());
    }

    @Transactional
    public SettingsResponse updateProjectSettings(Long projectId, ProfileRequest request) {
        require(properties.isProfilesEnabled(), "Codex profiles");
        validateProfile(request);
        ProjectEntity project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found"));
        project.setDefaultCodexModelId(request.modelId());
        project.setDefaultCodexReasoningEffort(CodexReasoningEffort.fromCanonicalValue(request.reasoningEffort()));
        project.setUpdatedAt(Instant.now());
        projectRepository.save(project);
        return settings("PROJECT", project.getId(), project.getDefaultCodexModelId(),
                project.getDefaultCodexReasoningEffort());
    }

    @Transactional(readOnly = true)
    public SettingsResponse sessionSettings(Long sessionId) {
        require(properties.isProfilesEnabled(), "Codex profiles");
        WorkSessionEntity session = sessionRepository.findWithProjectById(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "WorkSession not found"));
        return settings("WORK_SESSION", session.getId(), session.getDefaultCodexModelId(),
                session.getDefaultCodexReasoningEffort());
    }

    @Transactional
    public SettingsResponse updateSessionSettings(Long sessionId, ProfileRequest request) {
        require(properties.isProfilesEnabled(), "Codex profiles");
        validateProfile(request);
        WorkSessionEntity session = sessionRepository.findLockedWithProjectById(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "WorkSession not found"));
        session.setDefaultCodexModelId(request.modelId());
        session.setDefaultCodexReasoningEffort(CodexReasoningEffort.fromCanonicalValue(request.reasoningEffort()));
        session.setUpdatedAt(Instant.now());
        sessionRepository.save(session);
        return settings("WORK_SESSION", session.getId(), session.getDefaultCodexModelId(),
                session.getDefaultCodexReasoningEffort());
    }

    @Transactional(readOnly = true)
    public RunDetailResponse runDetail(Long runId) {
        require(properties.isProgressEnabled(), "Codex progress");
        AgentRunEntity run = runRepository.findWithSessionById(runId)
                .orElseThrow(() -> new AgentRunNotFoundException(runId));
        return new RunDetailResponse(run.getId(), run.getSession().getId(), run.getStatus().name(),
                run.getCodexModelId(), enumName(run.getCodexModelSource()),
                run.getCodexReasoningEffort() == null ? null : run.getCodexReasoningEffort().canonicalValue(),
                enumName(run.getCodexEffortSource()), run.getCodexCatalogRevision(), run.getCodexVersion(),
                run.getProgressCurrentState() == null ? null : run.getProgressCurrentState().name(),
                run.getProgressLatestSequence(), run.getProgressRetainedFloor(),
                run.getProgressElapsedMillis(), enumName(run.getProgressRequiredNextAction()),
                run.getRetryOfRun() == null ? null : run.getRetryOfRun().getId());
    }

    @Transactional(readOnly = true)
    public ProgressReplayResponse progress(Long runId, long afterSequence) {
        require(properties.isProgressEnabled(), "Codex progress");
        AgentRunProgressReplay replay = progressService.replay(runId, afterSequence);
        List<ProgressEventResponse> events = new ArrayList<>();
        for (AgentRunProgressEventEntity event : replay.events()) {
            events.add(new ProgressEventResponse(event.getSequence(), event.getCategory().name(),
                    event.getOperatorMessage(), event.getOccurredAt()));
        }
        return new ProgressReplayResponse(replay.requestedAfterSequence(), replay.retainedFloor(),
                replay.cursorWasBelowRetainedFloor(), enumName(replay.currentState()),
                replay.latestEvent() == null ? null : new ProgressEventResponse(
                        replay.latestEvent().sequence(), replay.latestEvent().category().name(),
                        replay.latestEvent().operatorMessage(), replay.latestEvent().occurredAt()),
                enumName(replay.terminalOutcome()), replay.elapsedMillis(),
                enumName(replay.requiredNextAction()), List.copyOf(events));
    }

    @Transactional
    public RecoveryResponse recovery(
            AuthenticatedOperator operator,
            Long runId,
            RecoveryRequest request) {
        require(properties.isRecoveryEnabled(), "Codex recovery");
        if (operator == null || request == null || request.workSessionId() == null
                || request.action() == null || request.idempotencyKey() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Complete recovery request required");
        }
        AgentRunRecoveryOperationEntity operation = recoveryService.request(
                operator.operatorId(), request.workSessionId(), runId,
                request.action(), request.idempotencyKey()).operation();
        return new RecoveryResponse(operation.getOperationId(), operation.getState().name(),
                operation.getAction().name(), enumName(operation.getOutcomeCode()),
                operation.getOutcomeSummary(), enumName(operation.getRequiredNextAction()),
                operation.getResultAgentRun() == null ? null : operation.getResultAgentRun().getId());
    }

    @Transactional(readOnly = true)
    public List<PreferenceResponse> preferences(AuthenticatedOperator operator, Long deviceId) {
        require(properties.isNotificationOutboxEnabled(), "Notification preferences");
        OperatorPushDeviceEntity device = ownedDevice(operator, deviceId);
        return EnumSet.allOf(NotificationCategory.class).stream()
                .map(category -> new PreferenceResponse(category.name(),
                        preferenceRepository.findByDeviceIdAndCategory(device.getId(), category)
                                .map(NotificationPreferenceEntity::isEnabled).orElse(true),
                        preferenceRepository.findByDeviceIdAndCategory(device.getId(), category).isPresent()))
                .toList();
    }

    @Transactional
    public PreferenceResponse updatePreference(
            AuthenticatedOperator operator,
            Long deviceId,
            PreferenceRequest request) {
        require(properties.isNotificationOutboxEnabled(), "Notification preferences");
        OperatorPushDeviceEntity device = ownedDevice(operator, deviceId);
        NotificationPreferenceEntity preference = preferenceRepository
                .findByDeviceIdAndCategory(device.getId(), request.category())
                .orElseGet(NotificationPreferenceEntity::new);
        Instant now = Instant.now();
        if (preference.getId() == null) {
            preference.setDevice(device);
            preference.setCategory(request.category());
            preference.setCreatedAt(now);
        }
        preference.setEnabled(request.enabled());
        preference.setUpdatedAt(now);
        preferenceRepository.save(preference);
        return new PreferenceResponse(request.category().name(), request.enabled(), true);
    }

    @Transactional(readOnly = true)
    public AdministratorInventoryResponse administratorInventory(AuthenticatedOperator operator) {
        CodexOperationsRole role = operatorRepository.findById(operator.operatorId())
                .filter(account -> account.isActive())
                .map(account -> account.getCodexOperationsRole())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Active platform administrator required"));
        if (role != CodexOperationsRole.PLATFORM_ADMINISTRATOR) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Platform administrator role required");
        }
        List<WorkerInventoryResponse> workers = jdbcTemplate.query("""
                SELECT w.id, w.protocol_version, w.enabled, w.healthy,
                       c.catalog_revision, c.codex_version, c.observed_at
                  FROM worker_node w
                  LEFT JOIN LATERAL (
                    SELECT catalog_revision, codex_version, observed_at
                      FROM worker_codex_catalog c
                     WHERE c.worker_id = w.id
                     ORDER BY generated_at DESC, catalog_revision DESC LIMIT 1
                  ) c ON TRUE
                 ORDER BY w.id
                """, (rs, row) -> new WorkerInventoryResponse(rs.getString("id"),
                rs.getString("protocol_version"), rs.getBoolean("enabled"),
                rs.getBoolean("healthy"), rs.getString("catalog_revision"),
                rs.getString("codex_version"), rs.getTimestamp("observed_at") == null
                        ? null : rs.getTimestamp("observed_at").toInstant()));
        return new AdministratorInventoryResponse(properties.isProfilesEnabled(),
                properties.isProgressEnabled(), properties.isRecoveryEnabled(),
                properties.isNotificationOutboxEnabled(), properties.isManagedUpdatesEnabled(),
                List.copyOf(workers));
    }

    private void validateProfile(ProfileRequest request) {
        if (request == null || request.idempotencyKey() == null
                || request.modelId() == null || request.reasoningEffort() == null
                || request.catalogRevision() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Complete profile request required");
        }
        try {
            CodexReasoningEffort.fromCanonicalValue(request.reasoningEffort());
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Unsupported reasoning effort");
        }
        Integer accepted = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM worker_codex_model model
                JOIN worker_codex_model_effort effort
                  ON effort.worker_id = model.worker_id
                 AND effort.catalog_revision = model.catalog_revision
                 AND effort.model_id = model.model_id
               WHERE model.worker_id = ? AND model.catalog_revision = ?
                 AND model.model_id = ? AND model.availability = 'AVAILABLE'
                 AND effort.effort = ?
                """, Integer.class, WORKER_ID, request.catalogRevision(),
                request.modelId(), request.reasoningEffort());
        if (accepted == null || accepted != 1) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Profile is not available in the selected worker catalog");
        }
    }

    private OperatorPushDeviceEntity ownedDevice(AuthenticatedOperator operator, Long deviceId) {
        OperatorPushDeviceEntity device = deviceRepository.findById(deviceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Device not found"));
        if (!device.getOperator().getId().equals(operator.operatorId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Device not found");
        }
        return device;
    }

    private static SettingsResponse settings(
            String scope, Long id, String model, CodexReasoningEffort effort) {
        return new SettingsResponse(scope, id, model,
                effort == null ? null : effort.canonicalValue());
    }

    private static String enumName(Enum<?> value) { return value == null ? null : value.name(); }

    private static void require(boolean enabled, String capability) {
        if (!enabled) throw new ResponseStatusException(HttpStatus.NOT_FOUND, capability + " are disabled");
    }

    private record CatalogHeader(String workerId, String catalogRevision, String schemaVersion,
                                 String codexVersion, Instant generatedAt, Instant observedAt) {}

    public record ModelResponse(String modelId, String displayName, String defaultEffort,
                                String availability, List<String> efforts) {}
    public record CatalogResponse(String workerId, String catalogRevision, String schemaVersion,
                                  String codexVersion, Instant generatedAt, Instant observedAt,
                                  List<ModelResponse> models) {}
    public record ProfileRequest(String modelId, String reasoningEffort,
                                 String catalogRevision, UUID idempotencyKey) {}
    public record SettingsResponse(String scope, Long id, String modelId, String reasoningEffort) {}
    public record RunDetailResponse(Long runId, Long workSessionId, String status, String modelId,
                                    String modelSource, String reasoningEffort, String effortSource,
                                    String catalogRevision, String codexVersion, String currentState,
                                    Long latestSequence, Long retainedFloor, Long elapsedMillis,
                                    String requiredNextAction, Long retryOfRunId) {}
    public record ProgressEventResponse(long sequence, String category, String message, Instant occurredAt) {}
    public record ProgressReplayResponse(long requestedAfterSequence, long retainedFloor,
                                         boolean cursorWasBelowRetainedFloor, String currentState,
                                         ProgressEventResponse latestEvent, String terminalOutcome,
                                         long elapsedMillis, String requiredNextAction,
                                         List<ProgressEventResponse> events) {}
    public record RecoveryRequest(Long workSessionId, AgentRunRecoveryAction action, UUID idempotencyKey) {}
    public record RecoveryResponse(UUID operationId, String state, String action, String outcome,
                                   String summary, String requiredNextAction, Long resultAgentRunId) {}
    public record PreferenceRequest(NotificationCategory category, boolean enabled) {}
    public record PreferenceResponse(String category, boolean enabled, boolean explicit) {}
    public record WorkerInventoryResponse(String workerId, String protocolVersion, boolean enabled,
                                          boolean healthy, String catalogRevision, String codexVersion,
                                          Instant observedAt) {}
    public record AdministratorInventoryResponse(boolean profilesEnabled, boolean progressEnabled,
                                                 boolean recoveryEnabled, boolean notificationOutboxEnabled,
                                                 boolean managedUpdatesEnabled,
                                                 List<WorkerInventoryResponse> workers) {}
}
