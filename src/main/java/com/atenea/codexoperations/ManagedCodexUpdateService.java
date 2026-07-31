package com.atenea.codexoperations;

import com.atenea.auth.AuthenticatedOperator;
import com.atenea.persistence.auth.CodexOperationsRole;
import com.atenea.persistence.auth.OperatorRepository;
import com.atenea.remoteworker.RemoteWorkerClient;
import com.atenea.remoteworker.RemoteWorkerException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
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
    private static final String UPDATE_STAGE_OPERATION = "STAGE_CODEX_UPDATE";
    private static final String AUTHORIZE_ACTIVATION_OPERATION = "AUTHORIZE_CODEX_UPDATE_ACTIVATION";
    private static final String ACTIVATE_UPDATE_OPERATION = "ACTIVATE_CODEX_UPDATE";
    private static final String AUTHORIZE_ROLLBACK_OPERATION = "AUTHORIZE_CODEX_UPDATE_ROLLBACK";
    private static final String ROLLBACK_UPDATE_OPERATION = "ROLLBACK_CODEX_UPDATE";
    private static final Duration ACTIVATION_AUTHORIZATION_LIFETIME = Duration.ofMinutes(10);
    private static final String EXPECTED_UPDATE_IMPACT = "No installation or restart; a later activation would restart only the exact Codex/worker boundary, never project runtimes or unrelated slots.";

    private final CodexSessionOperationsProperties properties;
    private final JdbcTemplate jdbcTemplate;
    private final OperatorRepository operatorRepository;
    private final RemoteWorkerClient remoteWorkerClient;

    public ManagedCodexUpdateService(
            CodexSessionOperationsProperties properties,
            JdbcTemplate jdbcTemplate,
            OperatorRepository operatorRepository,
            RemoteWorkerClient remoteWorkerClient) {
        this.properties = properties;
        this.jdbcTemplate = jdbcTemplate;
        this.operatorRepository = operatorRepository;
        this.remoteWorkerClient = remoteWorkerClient;
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

    @Transactional
    public UpdateStageResponse stageUpdate(
            AuthenticatedOperator operator, UpdateStageRequest request) {
        requireManagedUpdates();
        requirePlatformAdministrator(operator);
        if (request == null || !UPDATE_STAGE_OPERATION.equals(request.operation())
                || request.planId() == null || request.candidateId() == null
                || request.idempotencyKey() == null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Exact managed update stage request required");
        }
        List<ExistingStage> idempotent = jdbcTemplate.query("""
                SELECT stage_id, plan_id, candidate_inventory_id
                  FROM worker_codex_stage_operation
                 WHERE requested_by = ? AND idempotency_key = ?
                """, (rs, row) -> new ExistingStage(
                (UUID) rs.getObject("stage_id"), (UUID) rs.getObject("plan_id"),
                (UUID) rs.getObject("candidate_inventory_id")),
                operator.operatorId(), request.idempotencyKey());
        if (!idempotent.isEmpty()) {
            ExistingStage existing = idempotent.getFirst();
            if (!existing.planId().equals(request.planId())
                    || !existing.candidateId().equals(request.candidateId())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Idempotency key belongs to a different update stage");
            }
            return updateStageForAdministrator(existing.stageId());
        }
        List<UUID> alreadyStaged = jdbcTemplate.queryForList("""
                SELECT stage_id FROM worker_codex_stage_operation
                 WHERE plan_id = ? AND candidate_inventory_id = ?
                """, UUID.class, request.planId(), request.candidateId());
        if (!alreadyStaged.isEmpty()) {
            return updateStageForAdministrator(alreadyStaged.getFirst());
        }

        StageContext context = stageContext(request.planId());
        if (!"READY".equals(context.planState())
                || !request.candidateId().equals(context.candidateId())
                || !"COMPATIBLE".equals(context.compatibilityState())
                || !("DISCOVERED".equals(context.installationState())
                    || "STAGED".equals(context.installationState()))
                || !"NONE".equals(context.linkState())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Persisted update plan candidate is not stageable");
        }
        WorkerInventoryResponse before = workerInventory(context.workerId());
        ReleaseInventoryResponse currentBefore = linked(before.releases(), "CURRENT");
        ReleaseInventoryResponse previousBefore = linked(before.releases(), "PREVIOUS");
        if (!retainedInstalled(currentBefore) || !retainedInstalled(previousBefore)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Current and previous verified releases must be retained before staging");
        }

        RemoteWorkerClient.CodexUpdateStage staged;
        try {
            staged = remoteWorkerClient.stageCodexUpdate(
                    request.planId(), request.candidateId(), request.idempotencyKey());
        } catch (RemoteWorkerException exception) {
            throw new ResponseStatusException(
                    exception.getStatusCode() == 0 || exception.getStatusCode() >= 500
                            ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.CONFLICT,
                    "Closed Codex release staging failed", exception);
        }
        validateStageResult(request, context, staged);

        UUID stageId = UUID.randomUUID();
        Instant now = Instant.now();
        jdbcTemplate.update("""
                INSERT INTO worker_codex_stage_operation (
                    stage_id, plan_id, worker_id, requested_by, idempotency_key,
                    candidate_inventory_id, state, release_digest_sha256,
                    catalog_revision, release_manifest_sha256, schema_manifest_sha256,
                    release_verification_gate, schema_generation_gate, retention_gate,
                    current_link_fingerprint, previous_link_fingerprint,
                    links_changed, values_exposed, created_at, completed_at)
                VALUES (?, ?, ?, ?, ?, ?, 'STAGED', ?, ?, ?, ?, 'PASS', 'PASS',
                    'PASS', ?, ?, FALSE, FALSE, ?, ?)
                """, stageId, request.planId(), context.workerId(), operator.operatorId(),
                request.idempotencyKey(), request.candidateId(),
                staged.releaseDigestSha256(), staged.catalogRevision(),
                staged.releaseManifestSha256(), staged.schemaManifestSha256(),
                staged.currentLinkFingerprint(), staged.previousLinkFingerprint(),
                Timestamp.from(now), Timestamp.from(now));
        jdbcTemplate.update("""
                UPDATE worker_codex_release_inventory
                   SET installation_state = 'STAGED', updated_at = ?
                 WHERE inventory_id = ? AND worker_id = ?
                   AND installation_state IN ('DISCOVERED', 'STAGED')
                   AND link_state = 'NONE'
                """, Timestamp.from(now), request.candidateId(), context.workerId());

        WorkerInventoryResponse after = workerInventory(context.workerId());
        ReleaseInventoryResponse currentAfter = linked(after.releases(), "CURRENT");
        ReleaseInventoryResponse previousAfter = linked(after.releases(), "PREVIOUS");
        if (!sameRelease(currentBefore, currentAfter)
                || !sameRelease(previousBefore, previousAfter)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Staging changed retained current or previous inventory");
        }
        return updateStageForAdministrator(stageId);
    }

    @Transactional(readOnly = true)
    public UpdateStageResponse updateStage(
            AuthenticatedOperator operator, UUID stageId) {
        requireManagedUpdates();
        requirePlatformAdministrator(operator);
        return updateStageForAdministrator(stageId);
    }

    @Transactional
    public ActivationAuthorizationResponse authorizeActivation(
            AuthenticatedOperator operator, ActivationAuthorizationRequest request) {
        requireManagedUpdates();
        requirePlatformAdministrator(operator);
        if (request == null || !AUTHORIZE_ACTIVATION_OPERATION.equals(request.operation())
                || request.planId() == null || request.candidateId() == null
                || request.idempotencyKey() == null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Exact Codex activation authorization request required");
        }
        List<ExistingAuthorization> existing = jdbcTemplate.query("""
                SELECT authorization_id, plan_id, candidate_inventory_id
                  FROM worker_codex_activation_authorization
                 WHERE requested_by = ? AND idempotency_key = ?
                """, (rs, row) -> new ExistingAuthorization(
                (UUID) rs.getObject("authorization_id"),
                (UUID) rs.getObject("plan_id"),
                (UUID) rs.getObject("candidate_inventory_id")),
                operator.operatorId(), request.idempotencyKey());
        if (!existing.isEmpty()) {
            ExistingAuthorization authorization = existing.getFirst();
            if (!authorization.planId().equals(request.planId())
                    || !authorization.candidateId().equals(request.candidateId())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Idempotency key belongs to a different activation authorization");
            }
            return activationAuthorizationForAdministrator(authorization.authorizationId());
        }

        ActivationContext context = activationContext(request.planId(), request.candidateId());
        if (!"READY".equals(context.planState()) || !"STAGED".equals(context.stageState())
                || !"CURRENT".equals(context.currentLinkState())
                || !"INSTALLED".equals(context.currentInstallationState())
                || !"NONE".equals(context.candidateLinkState())
                || !"STAGED".equals(context.candidateInstallationState())
                || !"COMPATIBLE".equals(context.candidateCompatibilityState())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Persisted plan and staged candidate are not authorizable");
        }
        UUID authorizationId = UUID.randomUUID();
        Instant createdAt = Instant.now();
        Instant expiresAt = createdAt.plus(ACTIVATION_AUTHORIZATION_LIFETIME);
        String authorizationDigest = sha256(String.join("|",
                authorizationId.toString(), request.planId().toString(), context.workerId(),
                context.currentId().toString(), context.currentVersion(),
                request.candidateId().toString(), context.candidateVersion(),
                context.candidateDigest(), expiresAt.toString()));
        jdbcTemplate.update("""
                INSERT INTO worker_codex_activation_authorization (
                    authorization_id, plan_id, worker_id, requested_by,
                    idempotency_key, current_inventory_id, candidate_inventory_id,
                    current_version, candidate_version, release_digest_sha256,
                    authorization_digest_sha256, expires_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, authorizationId, request.planId(), context.workerId(),
                operator.operatorId(), request.idempotencyKey(), context.currentId(),
                request.candidateId(), context.currentVersion(), context.candidateVersion(),
                context.candidateDigest(), authorizationDigest,
                Timestamp.from(expiresAt), Timestamp.from(createdAt));
        return activationAuthorizationForAdministrator(authorizationId);
    }

    @Transactional(readOnly = true)
    public ActivationAuthorizationResponse activationAuthorization(
            AuthenticatedOperator operator, UUID authorizationId) {
        requireManagedUpdates();
        requirePlatformAdministrator(operator);
        return activationAuthorizationForAdministrator(authorizationId);
    }

    @Transactional
    public UpdateActivationResponse activateUpdate(
            AuthenticatedOperator operator, UpdateActivationRequest request) {
        requireManagedUpdates();
        requirePlatformAdministrator(operator);
        if (request == null || !ACTIVATE_UPDATE_OPERATION.equals(request.operation())
                || request.planId() == null || request.candidateId() == null
                || request.authorizationId() == null || request.idempotencyKey() == null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Exact Codex update activation request required");
        }
        List<ExistingActivation> existing = jdbcTemplate.query("""
                SELECT activation_id, plan_id, candidate_inventory_id, authorization_id
                  FROM worker_codex_activation_operation
                 WHERE requested_by = ? AND idempotency_key = ?
                """, (rs, row) -> new ExistingActivation(
                (UUID) rs.getObject("activation_id"), (UUID) rs.getObject("plan_id"),
                (UUID) rs.getObject("candidate_inventory_id"),
                (UUID) rs.getObject("authorization_id")),
                operator.operatorId(), request.idempotencyKey());
        if (!existing.isEmpty()) {
            ExistingActivation activation = existing.getFirst();
            if (!activation.planId().equals(request.planId())
                    || !activation.candidateId().equals(request.candidateId())
                    || !activation.authorizationId().equals(request.authorizationId())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Idempotency key belongs to a different update activation");
            }
            return updateActivationForAdministrator(activation.activationId());
        }

        AuthorizationContext authorization = authorizationContext(request.authorizationId());
        lockActivationBarrier(authorization.workerId());
        authorization = authorizationContext(request.authorizationId());
        Instant now = Instant.now();
        if (!authorization.requestedBy().equals(operator.operatorId())
                || !authorization.planId().equals(request.planId())
                || !authorization.candidateId().equals(request.candidateId())
                || authorization.consumedAt() != null || !authorization.expiresAt().isAfter(now)
                || !"READY".equals(authorization.planState())
                || !"CURRENT".equals(authorization.currentLinkState())
                || !"STAGED".equals(authorization.candidateInstallationState())
                || !"NONE".equals(authorization.candidateLinkState())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Activation authorization is expired, consumed or no longer exact");
        }
        Integer activeRuns = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM agent_run
                 WHERE selected_worker_id = ?
                   AND status IN ('QUEUED', 'STARTING', 'RUNNING', 'CANCELLING', 'RECONCILING')
                """, Integer.class, authorization.workerId());
        if (activeRuns == null || activeRuns != 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Codex activation requires zero active worker executions");
        }

        RemoteWorkerClient.CodexUpdateActivation activated;
        try {
            activated = remoteWorkerClient.activateCodexUpdate(
                    request.planId(), request.candidateId(), request.authorizationId(),
                    request.idempotencyKey());
        } catch (RemoteWorkerException exception) {
            throw new ResponseStatusException(
                    exception.getStatusCode() == 0 || exception.getStatusCode() >= 500
                            ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.CONFLICT,
                    "Closed Codex update activation failed", exception);
        }
        validateActivationResult(request, authorization, activated);

        UUID activationId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO worker_codex_activation_operation (
                    activation_id, authorization_id, plan_id, worker_id, requested_by,
                    idempotency_key, candidate_inventory_id, state,
                    schema_comparison_gate, focused_contracts_gate, worker_health_gate,
                    canary_gate, current_before_fingerprint, previous_before_fingerprint,
                    current_after_fingerprint, previous_after_fingerprint,
                    automatic_restore, values_exposed, created_at, completed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'ACTIVATED', 'PASS', 'PASS', 'PASS',
                    'PASS', ?, ?, ?, ?, ?, FALSE, ?, ?)
                """, activationId, request.authorizationId(), request.planId(),
                authorization.workerId(), operator.operatorId(), request.idempotencyKey(),
                request.candidateId(), activated.currentBeforeFingerprint(),
                activated.previousBeforeFingerprint(), activated.currentAfterFingerprint(),
                activated.previousAfterFingerprint(), activated.automaticRestore(),
                Timestamp.from(now), Timestamp.from(now));
        jdbcTemplate.update("""
                UPDATE worker_codex_release_inventory SET link_state = 'NONE', updated_at = ?
                 WHERE worker_id = ? AND link_state = 'PREVIOUS'
                """, Timestamp.from(now), authorization.workerId());
        jdbcTemplate.update("""
                UPDATE worker_codex_release_inventory SET link_state = 'PREVIOUS', updated_at = ?
                 WHERE worker_id = ? AND inventory_id = ? AND link_state = 'CURRENT'
                """, Timestamp.from(now), authorization.workerId(), authorization.currentId());
        jdbcTemplate.update("""
                UPDATE worker_codex_release_inventory
                   SET installation_state = 'INSTALLED', link_state = 'CURRENT', updated_at = ?
                 WHERE worker_id = ? AND inventory_id = ?
                   AND installation_state = 'STAGED' AND link_state = 'NONE'
                """, Timestamp.from(now), authorization.workerId(), request.candidateId());
        jdbcTemplate.update("UPDATE worker_codex_update_plan SET state = 'ACTIVATED' WHERE plan_id = ?",
                request.planId());
        jdbcTemplate.update("""
                UPDATE worker_codex_activation_authorization
                   SET consumed_at = ?, consumed_activation_id = ?
                 WHERE authorization_id = ? AND consumed_at IS NULL
                """, Timestamp.from(now), activationId, request.authorizationId());
        return updateActivationForAdministrator(activationId);
    }

    private void lockActivationBarrier(String workerId) {
        jdbcTemplate.update("""
                INSERT INTO worker_codex_activation_barrier (worker_id)
                VALUES (?) ON CONFLICT (worker_id) DO NOTHING
                """, workerId);
        jdbcTemplate.queryForObject("""
                SELECT worker_id FROM worker_codex_activation_barrier
                 WHERE worker_id = ? FOR UPDATE
                """, String.class, workerId);
    }

    @Transactional(readOnly = true)
    public UpdateActivationResponse updateActivation(
            AuthenticatedOperator operator, UUID activationId) {
        requireManagedUpdates();
        requirePlatformAdministrator(operator);
        return updateActivationForAdministrator(activationId);
    }

    @Transactional
    public RollbackAuthorizationResponse authorizeRollback(
            AuthenticatedOperator operator, RollbackAuthorizationRequest request) {
        requireManagedUpdates();
        requirePlatformAdministrator(operator);
        if (request == null || !AUTHORIZE_ROLLBACK_OPERATION.equals(request.operation())
                || request.activationId() == null || request.idempotencyKey() == null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Exact Codex rollback authorization request required");
        }
        List<ExistingRollbackAuthorization> existing = jdbcTemplate.query("""
                SELECT authorization_id, activation_id
                  FROM worker_codex_rollback_authorization
                 WHERE requested_by = ? AND idempotency_key = ?
                """, (rs, row) -> new ExistingRollbackAuthorization(
                (UUID) rs.getObject("authorization_id"),
                (UUID) rs.getObject("activation_id")),
                operator.operatorId(), request.idempotencyKey());
        if (!existing.isEmpty()) {
            ExistingRollbackAuthorization authorization = existing.getFirst();
            if (!authorization.activationId().equals(request.activationId())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Idempotency key belongs to a different rollback authorization");
            }
            return rollbackAuthorizationForAdministrator(authorization.authorizationId());
        }
        RollbackActivationContext context = rollbackActivationContext(request.activationId());
        if (!"ACTIVATED".equals(context.activationState())
                || !"ACTIVATED".equals(context.planState())
                || !"CURRENT".equals(context.currentLinkState())
                || !"INSTALLED".equals(context.currentInstallationState())
                || !"PREVIOUS".equals(context.previousLinkState())
                || !"INSTALLED".equals(context.previousInstallationState())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Persisted activation is not exactly rollback-authorizable");
        }
        UUID authorizationId = UUID.randomUUID();
        Instant createdAt = Instant.now();
        Instant expiresAt = createdAt.plus(ACTIVATION_AUTHORIZATION_LIFETIME);
        String digest = sha256(String.join("|", authorizationId.toString(),
                request.activationId().toString(), context.planId().toString(),
                context.workerId(), context.currentId().toString(),
                context.previousId().toString(), expiresAt.toString()));
        jdbcTemplate.update("""
                INSERT INTO worker_codex_rollback_authorization (
                    authorization_id, activation_id, plan_id, worker_id, requested_by,
                    idempotency_key, current_inventory_id, previous_inventory_id,
                    authorization_digest_sha256, expires_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, authorizationId, request.activationId(), context.planId(),
                context.workerId(), operator.operatorId(), request.idempotencyKey(),
                context.currentId(), context.previousId(), digest,
                Timestamp.from(expiresAt), Timestamp.from(createdAt));
        return rollbackAuthorizationForAdministrator(authorizationId);
    }

    @Transactional(readOnly = true)
    public RollbackAuthorizationResponse rollbackAuthorization(
            AuthenticatedOperator operator, UUID authorizationId) {
        requireManagedUpdates();
        requirePlatformAdministrator(operator);
        return rollbackAuthorizationForAdministrator(authorizationId);
    }

    @Transactional
    public UpdateRollbackResponse rollbackUpdate(
            AuthenticatedOperator operator, UpdateRollbackRequest request) {
        requireManagedUpdates();
        requirePlatformAdministrator(operator);
        if (request == null || !ROLLBACK_UPDATE_OPERATION.equals(request.operation())
                || request.activationId() == null || request.authorizationId() == null
                || request.idempotencyKey() == null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Exact Codex rollback request required");
        }
        List<ExistingRollback> existing = jdbcTemplate.query("""
                SELECT rollback_id, activation_id, authorization_id
                  FROM worker_codex_rollback_operation
                 WHERE requested_by = ? AND idempotency_key = ?
                """, (rs, row) -> new ExistingRollback(
                (UUID) rs.getObject("rollback_id"), (UUID) rs.getObject("activation_id"),
                (UUID) rs.getObject("authorization_id")),
                operator.operatorId(), request.idempotencyKey());
        if (!existing.isEmpty()) {
            ExistingRollback rollback = existing.getFirst();
            if (!rollback.activationId().equals(request.activationId())
                    || !rollback.authorizationId().equals(request.authorizationId())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Idempotency key belongs to a different Codex rollback");
            }
            return updateRollbackForAdministrator(rollback.rollbackId());
        }
        RollbackAuthorizationContext authorization =
                rollbackAuthorizationContext(request.authorizationId());
        lockActivationBarrier(authorization.workerId());
        authorization = rollbackAuthorizationContext(request.authorizationId());
        Instant now = Instant.now();
        if (!authorization.requestedBy().equals(operator.operatorId())
                || !authorization.activationId().equals(request.activationId())
                || authorization.consumedAt() != null || !authorization.expiresAt().isAfter(now)
                || !"ACTIVATED".equals(authorization.activationState())
                || !"ACTIVATED".equals(authorization.planState())
                || !"CURRENT".equals(authorization.currentLinkState())
                || !"PREVIOUS".equals(authorization.previousLinkState())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Rollback authorization is expired, consumed or no longer exact");
        }
        Integer activeRuns = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM agent_run
                 WHERE selected_worker_id = ?
                   AND status IN ('QUEUED', 'STARTING', 'RUNNING', 'CANCELLING', 'RECONCILING')
                """, Integer.class, authorization.workerId());
        if (activeRuns == null || activeRuns != 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Codex rollback requires zero active worker executions");
        }
        RemoteWorkerClient.CodexUpdateRollback rolledBack;
        try {
            rolledBack = remoteWorkerClient.rollbackCodexUpdate(
                    authorization.planId(), authorization.currentId(), request.activationId(),
                    request.authorizationId(), request.idempotencyKey());
        } catch (RemoteWorkerException exception) {
            throw new ResponseStatusException(
                    exception.getStatusCode() == 0 || exception.getStatusCode() >= 500
                            ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.CONFLICT,
                    "Closed Codex rollback failed", exception);
        }
        validateRollbackResult(request, authorization, rolledBack);
        UUID rollbackId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO worker_codex_rollback_operation (
                    rollback_id, authorization_id, activation_id, plan_id, worker_id,
                    requested_by, idempotency_key, state, link_restore,
                    worker_service_restart, affected_services,
                    app_server_services_restarted, current_before_fingerprint,
                    previous_before_fingerprint, current_after_fingerprint,
                    previous_after_fingerprint, values_exposed, created_at, completed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'ROLLED_BACK', 'PASS', 'PASS',
                    'atenea-agent-run-worker-v1.service', 0, ?, ?, ?, ?, FALSE, ?, ?)
                """, rollbackId, request.authorizationId(), request.activationId(),
                authorization.planId(), authorization.workerId(), operator.operatorId(),
                request.idempotencyKey(), rolledBack.currentBeforeFingerprint(),
                rolledBack.previousBeforeFingerprint(), rolledBack.currentAfterFingerprint(),
                rolledBack.previousAfterFingerprint(), Timestamp.from(now), Timestamp.from(now));
        jdbcTemplate.update("""
                UPDATE worker_codex_release_inventory SET link_state = 'NONE', updated_at = ?
                 WHERE worker_id = ? AND inventory_id = ? AND link_state = 'CURRENT'
                """, Timestamp.from(now), authorization.workerId(), authorization.currentId());
        jdbcTemplate.update("""
                UPDATE worker_codex_release_inventory SET link_state = 'CURRENT', updated_at = ?
                 WHERE worker_id = ? AND inventory_id = ? AND link_state = 'PREVIOUS'
                """, Timestamp.from(now), authorization.workerId(), authorization.previousId());
        jdbcTemplate.update("""
                UPDATE worker_codex_release_inventory SET link_state = 'PREVIOUS', updated_at = ?
                 WHERE worker_id = ? AND inventory_id = ? AND link_state = 'NONE'
                """, Timestamp.from(now), authorization.workerId(), authorization.currentId());
        jdbcTemplate.update("""
                UPDATE worker_codex_update_plan SET state = 'ROLLED_BACK' WHERE plan_id = ?
                """, authorization.planId());
        jdbcTemplate.update("""
                UPDATE worker_codex_rollback_authorization
                   SET consumed_at = ?, consumed_rollback_id = ?
                 WHERE authorization_id = ? AND consumed_at IS NULL
                """, Timestamp.from(now), rollbackId, request.authorizationId());
        return updateRollbackForAdministrator(rollbackId);
    }

    @Transactional(readOnly = true)
    public UpdateRollbackResponse updateRollback(
            AuthenticatedOperator operator, UUID rollbackId) {
        requireManagedUpdates();
        requirePlatformAdministrator(operator);
        return updateRollbackForAdministrator(rollbackId);
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

    private StageContext stageContext(UUID planId) {
        return jdbcTemplate.query("""
                SELECT p.worker_id, p.state AS plan_state, p.candidate_inventory_id,
                       r.codex_version, r.release_digest_sha256,
                       r.installation_state, r.link_state, r.compatibility_state,
                       r.catalog_revision
                  FROM worker_codex_update_plan p
                  JOIN worker_codex_release_inventory r
                    ON r.worker_id = p.worker_id
                   AND r.inventory_id = p.candidate_inventory_id
                 WHERE p.plan_id = ?
                """, (rs, row) -> new StageContext(
                rs.getString("worker_id"), rs.getString("plan_state"),
                (UUID) rs.getObject("candidate_inventory_id"),
                rs.getString("codex_version"), rs.getString("release_digest_sha256"),
                rs.getString("installation_state"), rs.getString("link_state"),
                rs.getString("compatibility_state"), rs.getString("catalog_revision")),
                planId).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Stageable update plan not found"));
    }

    private void validateStageResult(
            UpdateStageRequest request,
            StageContext context,
            RemoteWorkerClient.CodexUpdateStage result) {
        if (result == null
                || !"codex-update-stage-v1".equals(result.schemaVersion())
                || !UPDATE_STAGE_OPERATION.equals(result.operation())
                || !context.workerId().equals(result.workerId())
                || !request.planId().equals(result.planId())
                || !request.candidateId().equals(result.candidateId())
                || !request.idempotencyKey().equals(result.idempotencyKey())
                || !"STAGED".equals(result.state())
                || !context.codexVersion().equals(result.codexVersion())
                || !context.releaseDigestSha256().equals(result.releaseDigestSha256())
                || !context.catalogRevision().equals(result.catalogRevision())
                || !digest(result.releaseManifestSha256())
                || !digest(result.schemaManifestSha256())
                || !digest(result.currentLinkFingerprint())
                || !digest(result.previousLinkFingerprint())
                || !"PASS".equals(result.releaseVerification())
                || !"PASS".equals(result.schemaGeneration())
                || !"PASS".equals(result.retention())
                || result.linksChanged()
                || result.valuesExposed()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Worker stage result conflicts with the persisted update plan");
        }
    }

    private UpdateStageResponse updateStageForAdministrator(UUID stageId) {
        return jdbcTemplate.query("""
                SELECT stage_id, plan_id, worker_id, candidate_inventory_id,
                       state, release_manifest_sha256, schema_manifest_sha256,
                       release_verification_gate, schema_generation_gate,
                       retention_gate, links_changed, values_exposed,
                       created_at, completed_at
                  FROM worker_codex_stage_operation
                 WHERE stage_id = ?
                """, (rs, row) -> {
                    String workerId = rs.getString("worker_id");
                    WorkerInventoryResponse inventory = workerInventory(workerId);
                    return new UpdateStageResponse(
                            (UUID) rs.getObject("stage_id"),
                            (UUID) rs.getObject("plan_id"), workerId,
                            rs.getString("state"),
                            linked(inventory.releases(), "CURRENT"),
                            linked(inventory.releases(), "PREVIOUS"),
                            byId(inventory.releases(),
                                    (UUID) rs.getObject("candidate_inventory_id")),
                            rs.getString("release_manifest_sha256"),
                            rs.getString("schema_manifest_sha256"),
                            List.of(
                                    new CompatibilityGateResponse(
                                            "RELEASE_VERIFICATION",
                                            rs.getString("release_verification_gate")),
                                    new CompatibilityGateResponse(
                                            "SCHEMA_GENERATION",
                                            rs.getString("schema_generation_gate")),
                                    new CompatibilityGateResponse(
                                            "CURRENT_PREVIOUS_RETENTION",
                                            rs.getString("retention_gate"))),
                            rs.getBoolean("links_changed"),
                            rs.getBoolean("values_exposed"),
                            rs.getTimestamp("created_at").toInstant(),
                            rs.getTimestamp("completed_at").toInstant());
                }, stageId).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Update stage not found"));
    }

    private ActivationContext activationContext(UUID planId, UUID candidateId) {
        return jdbcTemplate.query("""
                SELECT p.worker_id, p.state AS plan_state, s.state AS stage_state,
                       c.inventory_id AS current_id, c.codex_version AS current_version,
                       c.installation_state AS current_installation_state,
                       c.link_state AS current_link_state,
                       n.codex_version AS candidate_version,
                       n.release_digest_sha256 AS candidate_digest,
                       n.installation_state AS candidate_installation_state,
                       n.link_state AS candidate_link_state,
                       n.compatibility_state AS candidate_compatibility_state
                  FROM worker_codex_update_plan p
                  JOIN worker_codex_stage_operation s
                    ON s.plan_id = p.plan_id AND s.worker_id = p.worker_id
                   AND s.candidate_inventory_id = p.candidate_inventory_id
                  JOIN worker_codex_release_inventory c
                    ON c.worker_id = p.worker_id AND c.inventory_id = p.current_inventory_id
                  JOIN worker_codex_release_inventory n
                    ON n.worker_id = p.worker_id AND n.inventory_id = p.candidate_inventory_id
                 WHERE p.plan_id = ? AND p.candidate_inventory_id = ?
                """, (rs, row) -> new ActivationContext(
                rs.getString("worker_id"), rs.getString("plan_state"),
                rs.getString("stage_state"), (UUID) rs.getObject("current_id"),
                rs.getString("current_version"),
                rs.getString("current_installation_state"), rs.getString("current_link_state"),
                rs.getString("candidate_version"), rs.getString("candidate_digest"),
                rs.getString("candidate_installation_state"),
                rs.getString("candidate_link_state"),
                rs.getString("candidate_compatibility_state")),
                planId, candidateId).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Authorizable staged candidate not found"));
    }

    private AuthorizationContext authorizationContext(UUID authorizationId) {
        return jdbcTemplate.query("""
                SELECT a.plan_id, a.worker_id, a.requested_by, a.current_inventory_id,
                       a.candidate_inventory_id, a.current_version, a.candidate_version,
                       a.release_digest_sha256, a.expires_at, a.consumed_at,
                       p.state AS plan_state, c.link_state AS current_link_state,
                       n.installation_state AS candidate_installation_state,
                       n.link_state AS candidate_link_state
                  FROM worker_codex_activation_authorization a
                  JOIN worker_codex_update_plan p ON p.plan_id = a.plan_id
                  JOIN worker_codex_release_inventory c
                    ON c.worker_id = a.worker_id AND c.inventory_id = a.current_inventory_id
                  JOIN worker_codex_release_inventory n
                    ON n.worker_id = a.worker_id AND n.inventory_id = a.candidate_inventory_id
                 WHERE a.authorization_id = ?
                """, (rs, row) -> new AuthorizationContext(
                (UUID) rs.getObject("plan_id"), rs.getString("worker_id"),
                rs.getLong("requested_by"), (UUID) rs.getObject("current_inventory_id"),
                (UUID) rs.getObject("candidate_inventory_id"),
                rs.getString("current_version"), rs.getString("candidate_version"),
                rs.getString("release_digest_sha256"),
                rs.getTimestamp("expires_at").toInstant(), timestamp(rs, "consumed_at"),
                rs.getString("plan_state"), rs.getString("current_link_state"),
                rs.getString("candidate_installation_state"),
                rs.getString("candidate_link_state")), authorizationId).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Activation authorization not found"));
    }

    private ActivationAuthorizationResponse activationAuthorizationForAdministrator(
            UUID authorizationId) {
        return jdbcTemplate.query("""
                SELECT authorization_id, plan_id, worker_id, current_inventory_id,
                       candidate_inventory_id, authorization_digest_sha256,
                       expires_at, consumed_at, consumed_activation_id, created_at
                  FROM worker_codex_activation_authorization
                 WHERE authorization_id = ?
                """, (rs, row) -> new ActivationAuthorizationResponse(
                (UUID) rs.getObject("authorization_id"), (UUID) rs.getObject("plan_id"),
                rs.getString("worker_id"), (UUID) rs.getObject("current_inventory_id"),
                (UUID) rs.getObject("candidate_inventory_id"),
                rs.getString("authorization_digest_sha256"),
                rs.getTimestamp("expires_at").toInstant(), timestamp(rs, "consumed_at"),
                (UUID) rs.getObject("consumed_activation_id"),
                rs.getTimestamp("created_at").toInstant(), true), authorizationId)
                .stream().findFirst().orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Activation authorization not found"));
    }

    private void validateActivationResult(
            UpdateActivationRequest request, AuthorizationContext context,
            RemoteWorkerClient.CodexUpdateActivation result) {
        if (result == null || !"codex-update-activate-v1".equals(result.schemaVersion())
                || !ACTIVATE_UPDATE_OPERATION.equals(result.operation())
                || !context.workerId().equals(result.workerId())
                || !request.planId().equals(result.planId())
                || !request.candidateId().equals(result.candidateId())
                || !request.authorizationId().equals(result.authorizationId())
                || !request.idempotencyKey().equals(result.idempotencyKey())
                || !"ACTIVATED".equals(result.state())
                || !context.candidateVersion().equals(result.codexVersion())
                || !context.releaseDigest().equals(result.releaseDigestSha256())
                || !digest(result.catalogRevision())
                || !"PASS".equals(result.schemaComparison())
                || !"PASS".equals(result.focusedContracts())
                || !"PASS".equals(result.workerHealth())
                || !"PASS".equals(result.canary())
                || !digest(result.currentBeforeFingerprint())
                || !digest(result.previousBeforeFingerprint())
                || !digest(result.currentAfterFingerprint())
                || !digest(result.previousAfterFingerprint())
                || !("NOT_REQUIRED".equals(result.automaticRestore())
                    || "PASS".equals(result.automaticRestore()))
                || result.valuesExposed()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Worker activation result conflicts with exact authorization");
        }
    }

    private UpdateActivationResponse updateActivationForAdministrator(UUID activationId) {
        return jdbcTemplate.query("""
                SELECT activation_id, authorization_id, plan_id, worker_id,
                       candidate_inventory_id, state, schema_comparison_gate,
                       focused_contracts_gate, worker_health_gate, canary_gate,
                       automatic_restore, values_exposed, created_at, completed_at
                  FROM worker_codex_activation_operation WHERE activation_id = ?
                """, (rs, row) -> {
                    WorkerInventoryResponse inventory = workerInventory(rs.getString("worker_id"));
                    return new UpdateActivationResponse(
                            (UUID) rs.getObject("activation_id"),
                            (UUID) rs.getObject("authorization_id"),
                            (UUID) rs.getObject("plan_id"), rs.getString("worker_id"),
                            rs.getString("state"), linked(inventory.releases(), "CURRENT"),
                            linked(inventory.releases(), "PREVIOUS"),
                            byId(inventory.releases(),
                                    (UUID) rs.getObject("candidate_inventory_id")),
                            List.of(
                                    new CompatibilityGateResponse("SCHEMA_COMPARISON", rs.getString("schema_comparison_gate")),
                                    new CompatibilityGateResponse("FOCUSED_CONTRACTS", rs.getString("focused_contracts_gate")),
                                    new CompatibilityGateResponse("WORKER_HEALTH", rs.getString("worker_health_gate")),
                                    new CompatibilityGateResponse("CANARY", rs.getString("canary_gate"))),
                            rs.getString("automatic_restore"), rs.getBoolean("values_exposed"),
                            rs.getTimestamp("created_at").toInstant(),
                            rs.getTimestamp("completed_at").toInstant());
                }, activationId).stream().findFirst().orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Update activation not found"));
    }

    private RollbackActivationContext rollbackActivationContext(UUID activationId) {
        return jdbcTemplate.query("""
                SELECT a.activation_id, a.plan_id, a.worker_id, a.state AS activation_state,
                       p.state AS plan_state, aa.candidate_inventory_id AS current_id,
                       aa.current_inventory_id AS previous_id,
                       c.installation_state AS current_installation_state,
                       c.link_state AS current_link_state,
                       v.installation_state AS previous_installation_state,
                       v.link_state AS previous_link_state
                  FROM worker_codex_activation_operation a
                  JOIN worker_codex_activation_authorization aa
                    ON aa.authorization_id = a.authorization_id
                  JOIN worker_codex_update_plan p ON p.plan_id = a.plan_id
                  JOIN worker_codex_release_inventory c
                    ON c.worker_id = a.worker_id
                   AND c.inventory_id = aa.candidate_inventory_id
                  JOIN worker_codex_release_inventory v
                    ON v.worker_id = a.worker_id
                   AND v.inventory_id = aa.current_inventory_id
                 WHERE a.activation_id = ?
                """, (rs, row) -> new RollbackActivationContext(
                (UUID) rs.getObject("activation_id"), (UUID) rs.getObject("plan_id"),
                rs.getString("worker_id"), rs.getString("activation_state"),
                rs.getString("plan_state"), (UUID) rs.getObject("current_id"),
                (UUID) rs.getObject("previous_id"),
                rs.getString("current_installation_state"),
                rs.getString("current_link_state"),
                rs.getString("previous_installation_state"),
                rs.getString("previous_link_state")), activationId).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Rollback-authorizable activation not found"));
    }

    private RollbackAuthorizationContext rollbackAuthorizationContext(UUID authorizationId) {
        return jdbcTemplate.query("""
                SELECT r.activation_id, r.plan_id, r.worker_id, r.requested_by,
                       r.current_inventory_id, r.previous_inventory_id,
                       r.expires_at, r.consumed_at,
                       a.state AS activation_state, p.state AS plan_state,
                       c.link_state AS current_link_state,
                       v.link_state AS previous_link_state
                  FROM worker_codex_rollback_authorization r
                  JOIN worker_codex_activation_operation a
                    ON a.activation_id = r.activation_id
                  JOIN worker_codex_update_plan p ON p.plan_id = r.plan_id
                  JOIN worker_codex_release_inventory c
                    ON c.worker_id = r.worker_id
                   AND c.inventory_id = r.current_inventory_id
                  JOIN worker_codex_release_inventory v
                    ON v.worker_id = r.worker_id
                   AND v.inventory_id = r.previous_inventory_id
                 WHERE r.authorization_id = ?
                """, (rs, row) -> new RollbackAuthorizationContext(
                (UUID) rs.getObject("activation_id"), (UUID) rs.getObject("plan_id"),
                rs.getString("worker_id"), rs.getLong("requested_by"),
                (UUID) rs.getObject("current_inventory_id"),
                (UUID) rs.getObject("previous_inventory_id"),
                rs.getTimestamp("expires_at").toInstant(), timestamp(rs, "consumed_at"),
                rs.getString("activation_state"), rs.getString("plan_state"),
                rs.getString("current_link_state"), rs.getString("previous_link_state")),
                authorizationId).stream().findFirst().orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Rollback authorization not found"));
    }

    private RollbackAuthorizationResponse rollbackAuthorizationForAdministrator(
            UUID authorizationId) {
        return jdbcTemplate.query("""
                SELECT authorization_id, activation_id, plan_id, worker_id,
                       current_inventory_id, previous_inventory_id,
                       authorization_digest_sha256, expires_at, consumed_at,
                       consumed_rollback_id, created_at
                  FROM worker_codex_rollback_authorization
                 WHERE authorization_id = ?
                """, (rs, row) -> new RollbackAuthorizationResponse(
                (UUID) rs.getObject("authorization_id"),
                (UUID) rs.getObject("activation_id"), (UUID) rs.getObject("plan_id"),
                rs.getString("worker_id"), (UUID) rs.getObject("current_inventory_id"),
                (UUID) rs.getObject("previous_inventory_id"),
                rs.getString("authorization_digest_sha256"),
                rs.getTimestamp("expires_at").toInstant(), timestamp(rs, "consumed_at"),
                (UUID) rs.getObject("consumed_rollback_id"),
                rs.getTimestamp("created_at").toInstant()), authorizationId).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Rollback authorization not found"));
    }

    private void validateRollbackResult(
            UpdateRollbackRequest request, RollbackAuthorizationContext context,
            RemoteWorkerClient.CodexUpdateRollback result) {
        if (result == null || !"codex-update-rollback-v1".equals(result.schemaVersion())
                || !ROLLBACK_UPDATE_OPERATION.equals(result.operation())
                || !context.workerId().equals(result.workerId())
                || !context.planId().equals(result.planId())
                || !context.currentId().equals(result.candidateId())
                || !request.activationId().equals(result.activationId())
                || !request.authorizationId().equals(result.authorizationId())
                || !request.idempotencyKey().equals(result.idempotencyKey())
                || !"ROLLED_BACK".equals(result.state())
                || !"PASS".equals(result.linkRestore())
                || !"PASS".equals(result.workerServiceRestart())
                || !List.of("atenea-agent-run-worker-v1.service")
                        .equals(result.affectedServices())
                || result.appServerServicesRestarted() != 0
                || !digest(result.currentBeforeFingerprint())
                || !digest(result.previousBeforeFingerprint())
                || !digest(result.currentAfterFingerprint())
                || !digest(result.previousAfterFingerprint())
                || result.valuesExposed()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Worker rollback result conflicts with exact authorization");
        }
    }

    private UpdateRollbackResponse updateRollbackForAdministrator(UUID rollbackId) {
        return jdbcTemplate.query("""
                SELECT rollback_id, authorization_id, activation_id, plan_id, worker_id,
                       state, link_restore, worker_service_restart, affected_services,
                       app_server_services_restarted, values_exposed, created_at, completed_at
                  FROM worker_codex_rollback_operation WHERE rollback_id = ?
                """, (rs, row) -> {
                    WorkerInventoryResponse inventory = workerInventory(rs.getString("worker_id"));
                    return new UpdateRollbackResponse(
                            (UUID) rs.getObject("rollback_id"),
                            (UUID) rs.getObject("authorization_id"),
                            (UUID) rs.getObject("activation_id"),
                            (UUID) rs.getObject("plan_id"), rs.getString("worker_id"),
                            rs.getString("state"), linked(inventory.releases(), "CURRENT"),
                            linked(inventory.releases(), "PREVIOUS"),
                            rs.getString("link_restore"),
                            rs.getString("worker_service_restart"),
                            List.of(rs.getString("affected_services")),
                            rs.getInt("app_server_services_restarted"),
                            rs.getBoolean("values_exposed"),
                            rs.getTimestamp("created_at").toInstant(),
                            rs.getTimestamp("completed_at").toInstant());
                }, rollbackId).stream().findFirst().orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Update rollback not found"));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static boolean retainedInstalled(ReleaseInventoryResponse release) {
        return release != null && "INSTALLED".equals(release.installationState());
    }

    private static boolean sameRelease(
            ReleaseInventoryResponse before, ReleaseInventoryResponse after) {
        return before != null && after != null
                && before.inventoryId().equals(after.inventoryId())
                && before.codexVersion().equals(after.codexVersion())
                && before.releaseDigestSha256().equals(after.releaseDigestSha256())
                && before.linkState().equals(after.linkState())
                && before.installationState().equals(after.installationState());
    }

    private static boolean digest(String value) {
        return value != null && value.matches("^[0-9a-f]{64}$");
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
    private record ExistingStage(UUID stageId, UUID planId, UUID candidateId) {}
    private record ExistingAuthorization(UUID authorizationId, UUID planId, UUID candidateId) {}
    private record ExistingActivation(UUID activationId, UUID planId, UUID candidateId,
                                      UUID authorizationId) {}
    private record ExistingRollbackAuthorization(UUID authorizationId, UUID activationId) {}
    private record ExistingRollback(UUID rollbackId, UUID activationId, UUID authorizationId) {}
    private record StageContext(String workerId, String planState, UUID candidateId,
                                String codexVersion, String releaseDigestSha256,
                                String installationState, String linkState,
                                String compatibilityState, String catalogRevision) {}
    private record ActivationContext(String workerId, String planState, String stageState,
                                     UUID currentId, String currentVersion,
                                     String currentInstallationState, String currentLinkState,
                                     String candidateVersion, String candidateDigest,
                                     String candidateInstallationState, String candidateLinkState,
                                     String candidateCompatibilityState) {}
    private record AuthorizationContext(UUID planId, String workerId, Long requestedBy,
                                        UUID currentId, UUID candidateId,
                                        String currentVersion, String candidateVersion,
                                        String releaseDigest, Instant expiresAt, Instant consumedAt,
                                        String planState, String currentLinkState,
                                        String candidateInstallationState,
                                        String candidateLinkState) {}
    private record RollbackActivationContext(
            UUID activationId, UUID planId, String workerId, String activationState,
            String planState, UUID currentId, UUID previousId,
            String currentInstallationState, String currentLinkState,
            String previousInstallationState, String previousLinkState) {}
    private record RollbackAuthorizationContext(
            UUID activationId, UUID planId, String workerId, Long requestedBy,
            UUID currentId, UUID previousId, Instant expiresAt, Instant consumedAt,
            String activationState, String planState, String currentLinkState,
            String previousLinkState) {}
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
    public record UpdateStageRequest(String operation, UUID planId, UUID candidateId,
                                     UUID idempotencyKey) {}
    public record ActivationAuthorizationRequest(String operation, UUID planId,
                                                 UUID candidateId, UUID idempotencyKey) {}
    public record UpdateActivationRequest(String operation, UUID planId, UUID candidateId,
                                          UUID authorizationId, UUID idempotencyKey) {}
    public record RollbackAuthorizationRequest(
            String operation, UUID activationId, UUID idempotencyKey) {}
    public record UpdateRollbackRequest(
            String operation, UUID activationId, UUID authorizationId, UUID idempotencyKey) {}
    public record CompatibilityGateResponse(String gate, String state) {}
    public record UpdatePlanResponse(UUID planId, String workerId, String state,
                                     String compatibilityState,
                                     ReleaseInventoryResponse current,
                                     ReleaseInventoryResponse previous,
                                     ReleaseInventoryResponse candidate,
                                     List<CompatibilityGateResponse> gates,
                                     String expectedServiceImpact, Instant createdAt) {}
    public record UpdateStageResponse(UUID stageId, UUID planId, String workerId,
                                      String state, ReleaseInventoryResponse current,
                                      ReleaseInventoryResponse previous,
                                      ReleaseInventoryResponse candidate,
                                      String releaseManifestSha256,
                                      String schemaManifestSha256,
                                      List<CompatibilityGateResponse> gates,
                                      boolean linksChanged, boolean valuesExposed,
                                      Instant createdAt, Instant completedAt) {}
    public record ActivationAuthorizationResponse(UUID authorizationId, UUID planId,
                                                  String workerId, UUID currentInventoryId,
                                                  UUID candidateInventoryId,
                                                  String authorizationDigestSha256,
                                                  Instant expiresAt, Instant consumedAt,
                                                  UUID consumedActivationId, Instant createdAt,
                                                  boolean automaticRestoreAuthorized) {}
    public record UpdateActivationResponse(UUID activationId, UUID authorizationId,
                                           UUID planId, String workerId, String state,
                                           ReleaseInventoryResponse current,
                                           ReleaseInventoryResponse previous,
                                           ReleaseInventoryResponse candidate,
                                           List<CompatibilityGateResponse> gates,
                                           String automaticRestore, boolean valuesExposed,
                                           Instant createdAt, Instant completedAt) {}
    public record RollbackAuthorizationResponse(
            UUID authorizationId, UUID activationId, UUID planId, String workerId,
            UUID currentInventoryId, UUID previousInventoryId,
            String authorizationDigestSha256, Instant expiresAt, Instant consumedAt,
            UUID consumedRollbackId, Instant createdAt) {}
    public record UpdateRollbackResponse(
            UUID rollbackId, UUID authorizationId, UUID activationId, UUID planId,
            String workerId, String state, ReleaseInventoryResponse current,
            ReleaseInventoryResponse previous, String linkRestore,
            String workerServiceRestart, List<String> affectedServices,
            int appServerServicesRestarted, boolean valuesExposed,
            Instant createdAt, Instant completedAt) {}
    public record AdministratorInventoryResponse(boolean profilesEnabled, boolean progressEnabled,
                                                 boolean recoveryEnabled, boolean notificationOutboxEnabled,
                                                 boolean managedUpdatesEnabled,
                                                 List<WorkerInventoryResponse> workers) {}
}
