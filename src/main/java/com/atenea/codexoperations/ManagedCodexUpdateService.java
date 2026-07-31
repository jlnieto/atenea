package com.atenea.codexoperations;

import com.atenea.auth.AuthenticatedOperator;
import com.atenea.persistence.auth.CodexOperationsRole;
import com.atenea.persistence.auth.OperatorRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ManagedCodexUpdateService {

    private static final String WORKER_ID = "ax42-01";
    private static final String UPDATE_PLAN_OPERATION = "PLAN_CODEX_UPDATE";
    private static final String EXPECTED_UPDATE_IMPACT = "No installation or restart; a later activation would restart only the exact Codex/worker boundary, never project runtimes or unrelated slots.";

    private final CodexSessionOperationsProperties properties;
    private final JdbcTemplate jdbcTemplate;
    private final OperatorRepository operatorRepository;

    public ManagedCodexUpdateService(
            CodexSessionOperationsProperties properties,
            JdbcTemplate jdbcTemplate,
            OperatorRepository operatorRepository) {
        this.properties = properties;
        this.jdbcTemplate = jdbcTemplate;
        this.operatorRepository = operatorRepository;
    }

    @Transactional(readOnly = true)
    public AdministratorInventoryResponse administratorInventory(AuthenticatedOperator operator) {
        requirePlatformAdministrator(operator);
        List<String> workerIds = jdbcTemplate.queryForList(
                "SELECT id FROM worker_node ORDER BY id", String.class);
        List<WorkerInventoryResponse> workers = workerIds.stream()
                .map(this::workerInventory).toList();
        return new AdministratorInventoryResponse(properties.isProfilesEnabled(),
                properties.isProgressEnabled(), properties.isRecoveryEnabled(),
                properties.isNotificationOutboxEnabled(), properties.isManagedUpdatesEnabled(),
                List.copyOf(workers));
    }

    @Transactional(readOnly = true)
    public WorkerInventoryResponse workerInventory(String workerId) {
        WorkerHeader worker = jdbcTemplate.query("""
                SELECT w.id, w.protocol_version, w.enabled, w.healthy,
                       c.catalog_revision, c.codex_version, c.observed_at
                  FROM worker_node w
                  LEFT JOIN LATERAL (
                    SELECT catalog_revision, codex_version, observed_at
                      FROM worker_codex_catalog c
                     WHERE c.worker_id = w.id
                     ORDER BY generated_at DESC, catalog_revision DESC LIMIT 1
                  ) c ON TRUE
                 WHERE w.id = ?
                """, (rs, row) -> new WorkerHeader(rs.getString("id"),
                rs.getString("protocol_version"), rs.getBoolean("enabled"),
                rs.getBoolean("healthy"), rs.getString("catalog_revision"),
                rs.getString("codex_version"), timestamp(rs, "observed_at")), workerId)
                .stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Worker not found"));
        List<ReleaseInventoryResponse> releases = jdbcTemplate.query("""
                SELECT inventory_id, codex_version, release_digest_sha256,
                       installation_state, link_state, compatibility_state,
                       catalog_revision, observed_at
                  FROM worker_codex_release_inventory
                 WHERE worker_id = ?
                 ORDER BY observed_at DESC, inventory_id
                """, (rs, row) -> release(rs), workerId);
        ReleaseInventoryResponse current = linked(releases, "CURRENT");
        ReleaseInventoryResponse previous = linked(releases, "PREVIOUS");
        List<String> installedVersions = releases.stream()
                .filter(release -> release.installationState().equals("INSTALLED"))
                .map(ReleaseInventoryResponse::codexVersion).distinct().toList();
        return new WorkerInventoryResponse(worker.workerId(), worker.protocolVersion(),
                worker.enabled(), worker.healthy(), worker.catalogRevision(),
                worker.catalogCodexVersion(), worker.observedAt(), installedVersions,
                current == null ? null : current.codexVersion(),
                previous == null ? null : previous.codexVersion(),
                inventoryCompatibility(worker, current), List.copyOf(releases));
    }

    @Transactional
    public UpdatePlanResponse createUpdatePlan(
            AuthenticatedOperator operator, UpdatePlanRequest request) {
        requireManagedUpdates();
        requirePlatformAdministrator(operator);
        if (request == null || !UPDATE_PLAN_OPERATION.equals(request.operation())
                || !WORKER_ID.equals(request.workerId()) || request.idempotencyKey() == null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Exact managed update plan request required");
        }
        List<UUID> existing = jdbcTemplate.queryForList("""
                SELECT plan_id FROM worker_codex_update_plan
                 WHERE requested_by = ? AND idempotency_key = ?
                """, UUID.class, operator.operatorId(), request.idempotencyKey());
        if (!existing.isEmpty()) {
            return updatePlanForAdministrator(existing.getFirst());
        }

        WorkerInventoryResponse inventory = workerInventory(request.workerId());
        ReleaseInventoryResponse current = linked(inventory.releases(), "CURRENT");
        ReleaseInventoryResponse previous = linked(inventory.releases(), "PREVIOUS");
        ReleaseInventoryResponse candidate = inventory.releases().stream()
                .filter(release -> release.linkState().equals("NONE"))
                .findFirst().orElse(null);
        String workerGate = pass(inventory.enabled() && inventory.healthy());
        String currentGate = pass(current != null);
        String catalogGate = pass(current != null && inventory.catalogCodexVersion() != null
                && inventory.catalogCodexVersion().equals(current.codexVersion())
                && inventory.catalogRevision() != null
                && inventory.catalogRevision().equals(current.catalogRevision()));
        String candidateGate = pass(candidate != null
                && candidate.compatibilityState().equals("COMPATIBLE"));
        boolean ready = List.of(workerGate, currentGate, catalogGate, candidateGate)
                .stream().allMatch("PASS"::equals);
        UUID planId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO worker_codex_update_plan (
                    plan_id, worker_id, requested_by, idempotency_key,
                    current_inventory_id, previous_inventory_id, candidate_inventory_id,
                    state, compatibility_state, worker_health_gate, current_link_gate,
                    catalog_alignment_gate, candidate_compatibility_gate,
                    expected_service_impact, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (requested_by, idempotency_key) DO NOTHING
                """, planId, request.workerId(), operator.operatorId(), request.idempotencyKey(),
                current == null ? null : current.inventoryId(),
                previous == null ? null : previous.inventoryId(),
                candidate == null ? null : candidate.inventoryId(),
                ready ? "READY" : "BLOCKED", ready ? "COMPATIBLE" : "BLOCKED",
                workerGate, currentGate, catalogGate, candidateGate,
                EXPECTED_UPDATE_IMPACT, Timestamp.from(Instant.now()));
        UUID persistedPlanId = jdbcTemplate.queryForObject("""
                SELECT plan_id FROM worker_codex_update_plan
                 WHERE requested_by = ? AND idempotency_key = ?
                """, UUID.class, operator.operatorId(), request.idempotencyKey());
        return updatePlanForAdministrator(persistedPlanId);
    }

    @Transactional(readOnly = true)
    public UpdatePlanResponse updatePlan(AuthenticatedOperator operator, UUID planId) {
        requireManagedUpdates();
        requirePlatformAdministrator(operator);
        return updatePlanForAdministrator(planId);
    }

    private UpdatePlanResponse updatePlanForAdministrator(UUID planId) {
        return jdbcTemplate.query("""
                SELECT plan_id, worker_id, state, compatibility_state,
                       current_inventory_id, previous_inventory_id, candidate_inventory_id,
                       worker_health_gate, current_link_gate, catalog_alignment_gate,
                       candidate_compatibility_gate, expected_service_impact, created_at
                  FROM worker_codex_update_plan WHERE plan_id = ?
                """, (rs, row) -> {
                    String workerId = rs.getString("worker_id");
                    WorkerInventoryResponse inventory = workerInventory(workerId);
                    return new UpdatePlanResponse(
                            (UUID) rs.getObject("plan_id"), workerId, rs.getString("state"),
                            rs.getString("compatibility_state"),
                            byId(inventory.releases(), (UUID) rs.getObject("current_inventory_id")),
                            byId(inventory.releases(), (UUID) rs.getObject("previous_inventory_id")),
                            byId(inventory.releases(), (UUID) rs.getObject("candidate_inventory_id")),
                            List.of(
                                    new CompatibilityGateResponse("WORKER_HEALTH", rs.getString("worker_health_gate")),
                                    new CompatibilityGateResponse("CURRENT_LINK", rs.getString("current_link_gate")),
                                    new CompatibilityGateResponse("CATALOG_ALIGNMENT", rs.getString("catalog_alignment_gate")),
                                    new CompatibilityGateResponse("CANDIDATE_COMPATIBILITY", rs.getString("candidate_compatibility_gate"))),
                            rs.getString("expected_service_impact"),
                            rs.getTimestamp("created_at").toInstant());
                }, planId).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Update plan not found"));
    }

    private void requirePlatformAdministrator(AuthenticatedOperator operator) {
        if (operator == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Active platform administrator required");
        }
        CodexOperationsRole role = operatorRepository.findById(operator.operatorId())
                .filter(account -> account.isActive())
                .map(account -> account.getCodexOperationsRole())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Active platform administrator required"));
        if (role != CodexOperationsRole.PLATFORM_ADMINISTRATOR) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Platform administrator role required");
        }
    }

    private void requireManagedUpdates() {
        if (!properties.isManagedUpdatesEnabled()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Managed Codex updates are disabled");
        }
    }

    private static String inventoryCompatibility(
            WorkerHeader worker, ReleaseInventoryResponse current) {
        if (!worker.enabled() || !worker.healthy()) return "WORKER_UNHEALTHY";
        if (current == null) return "CURRENT_UNAVAILABLE";
        if (!"COMPATIBLE".equals(current.compatibilityState())) return current.compatibilityState();
        if (worker.catalogCodexVersion() == null || worker.catalogRevision() == null) {
            return "CATALOG_UNAVAILABLE";
        }
        if (!worker.catalogCodexVersion().equals(current.codexVersion())
                || !worker.catalogRevision().equals(current.catalogRevision())) {
            return "CATALOG_MISMATCH";
        }
        return "COMPATIBLE";
    }

    private static String pass(boolean value) { return value ? "PASS" : "BLOCKED"; }

    private static ReleaseInventoryResponse linked(
            List<ReleaseInventoryResponse> releases, String linkState) {
        return releases.stream().filter(release -> release.linkState().equals(linkState))
                .findFirst().orElse(null);
    }

    private static ReleaseInventoryResponse byId(
            List<ReleaseInventoryResponse> releases, UUID inventoryId) {
        if (inventoryId == null) return null;
        return releases.stream().filter(release -> release.inventoryId().equals(inventoryId))
                .findFirst().orElse(null);
    }

    private static Instant timestamp(ResultSet resultSet, String column) throws SQLException {
        Timestamp value = resultSet.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static ReleaseInventoryResponse release(ResultSet resultSet) throws SQLException {
        return new ReleaseInventoryResponse((UUID) resultSet.getObject("inventory_id"),
                resultSet.getString("codex_version"),
                resultSet.getString("release_digest_sha256"),
                resultSet.getString("installation_state"), resultSet.getString("link_state"),
                resultSet.getString("compatibility_state"),
                resultSet.getString("catalog_revision"),
                resultSet.getTimestamp("observed_at").toInstant());
    }

    private record WorkerHeader(String workerId, String protocolVersion, boolean enabled,
                                boolean healthy, String catalogRevision,
                                String catalogCodexVersion, Instant observedAt) {}
    public record ReleaseInventoryResponse(UUID inventoryId, String codexVersion,
                                           String releaseDigestSha256,
                                           String installationState, String linkState,
                                           String compatibilityState, String catalogRevision,
                                           Instant observedAt) {}
    public record WorkerInventoryResponse(String workerId, String protocolVersion, boolean enabled,
                                          boolean healthy, String catalogRevision,
                                          String catalogCodexVersion, Instant observedAt,
                                          List<String> installedVersions, String currentVersion,
                                          String previousVersion, String compatibilityState,
                                          List<ReleaseInventoryResponse> releases) {}
    public record UpdatePlanRequest(String operation, String workerId, UUID idempotencyKey) {}
    public record CompatibilityGateResponse(String gate, String state) {}
    public record UpdatePlanResponse(UUID planId, String workerId, String state,
                                     String compatibilityState,
                                     ReleaseInventoryResponse current,
                                     ReleaseInventoryResponse previous,
                                     ReleaseInventoryResponse candidate,
                                     List<CompatibilityGateResponse> gates,
                                     String expectedServiceImpact, Instant createdAt) {}
    public record AdministratorInventoryResponse(boolean profilesEnabled, boolean progressEnabled,
                                                 boolean recoveryEnabled, boolean notificationOutboxEnabled,
                                                 boolean managedUpdatesEnabled,
                                                 List<WorkerInventoryResponse> workers) {}
}
