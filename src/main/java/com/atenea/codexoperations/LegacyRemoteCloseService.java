package com.atenea.codexoperations;

import com.atenea.auth.AuthenticatedOperator;
import com.atenea.persistence.auth.CodexOperationsRole;
import com.atenea.persistence.auth.OperatorRepository;
import com.atenea.persistence.worksession.ExecutionTarget;
import com.atenea.persistence.worksession.RemoteCloseState;
import com.atenea.persistence.worksession.WorkSessionEntity;
import com.atenea.persistence.worksession.WorkSessionRepository;
import com.atenea.persistence.worksession.WorkSessionStatus;
import com.atenea.remoteworker.ProjectCodexIdentity;
import com.atenea.remoteworker.RemoteWorkerProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class LegacyRemoteCloseService {

    static final String OPERATION = "RECONCILE_REMOTE_CLOSE";
    static final Duration CONFIRMATION_LIFETIME = Duration.ofMinutes(10);
    private static final String REQUIRED_ROLE = "PLATFORM_ADMINISTRATOR";
    private static final String EXPECTED_IMPACT =
            "Release only the selected closed Atenea session's exact active remote ownership; retained history and delivery remain unchanged.";

    private final RemoteWorkerProperties properties;
    private final JdbcTemplate jdbcTemplate;
    private final OperatorRepository operatorRepository;
    private final WorkSessionRepository sessionRepository;
    private final Clock clock;

    @Autowired
    public LegacyRemoteCloseService(
            RemoteWorkerProperties properties,
            JdbcTemplate jdbcTemplate,
            OperatorRepository operatorRepository,
            WorkSessionRepository sessionRepository) {
        this(properties, jdbcTemplate, operatorRepository, sessionRepository, Clock.systemUTC());
    }

    LegacyRemoteCloseService(
            RemoteWorkerProperties properties,
            JdbcTemplate jdbcTemplate,
            OperatorRepository operatorRepository,
            WorkSessionRepository sessionRepository,
            Clock clock) {
        this.properties = properties;
        this.jdbcTemplate = jdbcTemplate;
        this.operatorRepository = operatorRepository;
        this.sessionRepository = sessionRepository;
        this.clock = clock;
    }

    @Transactional
    public LegacyRemoteClosePlanResponse createPlan(
            AuthenticatedOperator operator,
            Long sessionId,
            LegacyRemoteClosePlanRequest request) {
        requireEnabled();
        requirePlatformAdministrator(operator);
        if (sessionId == null || request == null || !OPERATION.equals(request.operation())
                || request.idempotencyKey() == null) {
            throw unprocessable("Exact legacy remote-close plan request required");
        }

        List<ExistingPlan> existing = jdbcTemplate.query("""
                SELECT plan_id, work_session_id
                  FROM remote_close_legacy_plan
                 WHERE requested_by = ? AND idempotency_key = ?
                """, (rs, row) -> new ExistingPlan(
                (UUID) rs.getObject("plan_id"), rs.getLong("work_session_id")),
                operator.operatorId(), request.idempotencyKey());
        if (!existing.isEmpty()) {
            ExistingPlan plan = existing.getFirst();
            if (!sessionId.equals(plan.workSessionId())) {
                throw conflict("Idempotency key belongs to a different legacy close plan");
            }
            return planForAdministrator(plan.planId());
        }

        WorkSessionEntity session = exactLegacySession(sessionId);
        String ownershipFingerprint = ownershipFingerprint(session);
        String requestFingerprint = sha256(String.join("\n",
                "legacy-remote-close-plan-v1",
                operator.operatorId().toString(),
                sessionId.toString(),
                OPERATION,
                request.idempotencyKey().toString(),
                ownershipFingerprint));
        Instant createdAt = clock.instant();
        UUID planId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO remote_close_legacy_plan (
                    plan_id, work_session_id, requested_by, idempotency_key,
                    operation, worker_id, project_identity, remote_session_id,
                    workspace_identity, ownership_fingerprint_sha256,
                    request_fingerprint_sha256, expires_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (requested_by, idempotency_key) DO NOTHING
                """, planId, sessionId, operator.operatorId(), request.idempotencyKey(),
                OPERATION, ProjectCodexIdentity.WORKER_ID,
                ProjectCodexIdentity.PROJECT_IDENTITY, session.getRemoteSessionId(),
                session.getWorkspaceIdentity(), ownershipFingerprint, requestFingerprint,
                Timestamp.from(createdAt.plus(CONFIRMATION_LIFETIME)),
                Timestamp.from(createdAt));
        ExistingPlan persisted = jdbcTemplate.query("""
                SELECT plan_id, work_session_id FROM remote_close_legacy_plan
                 WHERE requested_by = ? AND idempotency_key = ?
                """, (rs, row) -> new ExistingPlan(
                (UUID) rs.getObject("plan_id"), rs.getLong("work_session_id")),
                operator.operatorId(), request.idempotencyKey()).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Legacy remote-close plan was not persisted"));
        if (!sessionId.equals(persisted.workSessionId())) {
            throw conflict("Idempotency key belongs to a different legacy close plan");
        }
        return planForAdministrator(Objects.requireNonNull(persisted.planId()));
    }

    @Transactional(readOnly = true)
    public LegacyRemoteClosePlanResponse plan(
            AuthenticatedOperator operator, UUID planId) {
        requireEnabled();
        requirePlatformAdministrator(operator);
        return planForAdministrator(planId);
    }

    @Transactional
    public LegacyRemoteCloseOperationResponse confirm(
            AuthenticatedOperator operator,
            Long sessionId,
            LegacyRemoteCloseConfirmationRequest request) {
        requireEnabled();
        requirePlatformAdministrator(operator);
        if (sessionId == null || request == null || !OPERATION.equals(request.operation())
                || request.planId() == null || request.ownershipFingerprintSha256() == null
                || !request.ownershipFingerprintSha256().matches("^[0-9a-f]{64}$")
                || request.idempotencyKey() == null) {
            throw unprocessable("Exact legacy remote-close confirmation required");
        }

        List<ExistingOperation> idempotent = existingOperation(
                operator.operatorId(), request.idempotencyKey());
        if (!idempotent.isEmpty()) {
            ExistingOperation existing = idempotent.getFirst();
            if (!existing.planId().equals(request.planId())
                    || !existing.workSessionId().equals(sessionId)
                    || !existing.ownershipFingerprint().equals(
                            request.ownershipFingerprintSha256())) {
                throw conflict("Idempotency key belongs to a different legacy close operation");
            }
            return operationForAdministrator(existing.operationId());
        }

        LegacyPlanBinding plan = lockedPlan(request.planId());
        if (!plan.workSessionId().equals(sessionId)
                || !plan.ownershipFingerprint().equals(
                        request.ownershipFingerprintSha256())) {
            throw conflict("Legacy close confirmation does not match its read-only plan");
        }
        if (!clock.instant().isBefore(plan.expiresAt())) {
            throw conflict("Legacy close confirmation expired; create a fresh read-only plan");
        }
        WorkSessionEntity session = exactLegacySession(sessionId);
        if (!plan.ownershipFingerprint().equals(ownershipFingerprint(session))) {
            throw conflict("Legacy close ownership changed; create a fresh read-only plan");
        }

        String requestFingerprint = sha256(String.join("\n",
                "legacy-remote-close-confirmation-v1",
                operator.operatorId().toString(),
                sessionId.toString(),
                request.operation(),
                request.planId().toString(),
                request.ownershipFingerprintSha256(),
                request.idempotencyKey().toString()));
        UUID operationId = UUID.randomUUID();
        Instant createdAt = clock.instant();
        jdbcTemplate.update("""
                INSERT INTO remote_close_legacy_operation (
                    operation_id, plan_id, work_session_id, requested_by,
                    idempotency_key, operation, ownership_fingerprint_sha256,
                    request_fingerprint_sha256, state, requested_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'REQUESTED', ?, ?)
                ON CONFLICT DO NOTHING
                """, operationId, request.planId(), sessionId, operator.operatorId(),
                request.idempotencyKey(), OPERATION,
                request.ownershipFingerprintSha256(), requestFingerprint,
                Timestamp.from(createdAt), Timestamp.from(createdAt));

        List<ExistingOperation> persisted = existingOperation(
                operator.operatorId(), request.idempotencyKey());
        if (persisted.isEmpty()) {
            throw conflict("Legacy close confirmation was already consumed");
        }
        ExistingOperation operation = persisted.getFirst();
        if (!operation.planId().equals(request.planId())
                || !operation.workSessionId().equals(sessionId)
                || !operation.ownershipFingerprint().equals(
                        request.ownershipFingerprintSha256())) {
            throw conflict("Legacy close confirmation was already consumed");
        }
        return operationForAdministrator(operation.operationId());
    }

    @Transactional(readOnly = true)
    public LegacyRemoteCloseOperationResponse operation(
            AuthenticatedOperator operator, UUID operationId) {
        requireEnabled();
        requirePlatformAdministrator(operator);
        return operationForAdministrator(operationId);
    }

    private LegacyRemoteClosePlanResponse planForAdministrator(UUID planId) {
        if (planId == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Legacy remote-close plan not found");
        }
        return jdbcTemplate.query("""
                SELECT p.plan_id, p.work_session_id, p.operation,
                       p.ownership_fingerprint_sha256, p.expires_at, p.created_at,
                       EXISTS (SELECT 1 FROM remote_close_legacy_operation o
                                WHERE o.plan_id = p.plan_id) AS consumed
                  FROM remote_close_legacy_plan p
                 WHERE p.plan_id = ?
                """, (rs, row) -> {
                    Instant expiresAt = rs.getTimestamp("expires_at").toInstant();
                    boolean consumed = rs.getBoolean("consumed");
                    String state = consumed ? "CONSUMED"
                            : !clock.instant().isBefore(expiresAt)
                                    ? "EXPIRED" : "READY_FOR_CONFIRMATION";
                    return new LegacyRemoteClosePlanResponse(
                            (UUID) rs.getObject("plan_id"),
                            rs.getLong("work_session_id"),
                            rs.getString("operation"), state, REQUIRED_ROLE,
                            rs.getString("ownership_fingerprint_sha256"),
                            expiresAt, consumed, EXPECTED_IMPACT, false,
                            rs.getTimestamp("created_at").toInstant());
                }, planId)
                .stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Legacy remote-close plan not found"));
    }

    private LegacyRemoteCloseOperationResponse operationForAdministrator(UUID operationId) {
        if (operationId == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Legacy remote-close operation not found");
        }
        return jdbcTemplate.query("""
                SELECT operation_id, plan_id, work_session_id, operation, state,
                       ownership_fingerprint_sha256, requested_at
                  FROM remote_close_legacy_operation
                 WHERE operation_id = ?
                """, (rs, row) -> new LegacyRemoteCloseOperationResponse(
                (UUID) rs.getObject("operation_id"),
                (UUID) rs.getObject("plan_id"),
                rs.getLong("work_session_id"), rs.getString("operation"),
                rs.getString("state"), rs.getString("ownership_fingerprint_sha256"),
                rs.getTimestamp("requested_at").toInstant(), false), operationId)
                .stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Legacy remote-close operation not found"));
    }

    private List<ExistingOperation> existingOperation(Long operatorId, UUID idempotencyKey) {
        return jdbcTemplate.query("""
                SELECT operation_id, plan_id, work_session_id,
                       ownership_fingerprint_sha256
                  FROM remote_close_legacy_operation
                 WHERE requested_by = ? AND idempotency_key = ?
                """, (rs, row) -> new ExistingOperation(
                (UUID) rs.getObject("operation_id"),
                (UUID) rs.getObject("plan_id"),
                rs.getLong("work_session_id"),
                rs.getString("ownership_fingerprint_sha256")),
                operatorId, idempotencyKey);
    }

    private LegacyPlanBinding lockedPlan(UUID planId) {
        return jdbcTemplate.query("""
                SELECT work_session_id, ownership_fingerprint_sha256, expires_at
                  FROM remote_close_legacy_plan
                 WHERE plan_id = ?
                 FOR UPDATE
                """, (rs, row) -> new LegacyPlanBinding(
                rs.getLong("work_session_id"),
                rs.getString("ownership_fingerprint_sha256"),
                rs.getTimestamp("expires_at").toInstant()), planId)
                .stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Legacy remote-close plan not found"));
    }

    private WorkSessionEntity exactLegacySession(Long sessionId) {
        WorkSessionEntity session = sessionRepository.findWithProjectById(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "WorkSession not found"));
        String remoteId = session.getRemoteSessionId() == null
                ? null : session.getRemoteSessionId().toString();
        if (session.getStatus() != WorkSessionStatus.CLOSED
                || session.getRemoteCloseState() != RemoteCloseState.UNVERIFIED_LEGACY
                || session.getExecutionTarget() != ExecutionTarget.REMOTE
                || !ProjectCodexIdentity.hasCanonicalSourceObservation(session)
                || !ProjectCodexIdentity.WORKER_ID.equals(session.getSelectedWorkerId())
                || !ProjectCodexIdentity.WORKLOAD_KIND.equals(session.getRemoteWorkloadKind())
                || remoteId == null
                || !("remote:" + ProjectCodexIdentity.WORKER_ID
                    + ":work-session:" + remoteId).equals(session.getWorkspaceIdentity())
                || !("atenea/session-" + remoteId).equals(session.getWorkspaceBranch())) {
            throw conflict("Selected WorkSession is not an exact legacy Atenea remote owner");
        }
        return session;
    }

    static String ownershipFingerprint(WorkSessionEntity session) {
        return sha256(String.join("\n",
                field("schema", "legacy-remote-close-ownership-v1"),
                field("workSessionId", session.getId()),
                field("remoteSessionId", session.getRemoteSessionId()),
                field("workerId", session.getSelectedWorkerId()),
                field("projectIdentity", ProjectCodexIdentity.PROJECT_IDENTITY),
                field("projectId", session.getProject().getId()),
                field("projectName", session.getProject().getName()),
                field("projectRepoPath", session.getProject().getRepoPath()),
                field("executionTarget", session.getExecutionTarget()),
                field("workSessionStatus", session.getStatus()),
                field("remoteCloseState", session.getRemoteCloseState()),
                field("workspaceIdentity", session.getWorkspaceIdentity()),
                field("remoteWorkloadKind", session.getRemoteWorkloadKind()),
                field("baseBranch", session.getBaseBranch()),
                field("workspaceBranch", session.getWorkspaceBranch()),
                field("canonicalSourceRef", session.getCanonicalSourceRef()),
                field("canonicalSourceCommit", session.getCanonicalSourceCommit()),
                field("canonicalSourceObservationSha256",
                        session.getCanonicalSourceObservationSha256()),
                field("canonicalSourceObservedAt", session.getCanonicalSourceObservedAt()),
                field("acceptanceState", session.getAcceptanceState()),
                field("sourceTreeFingerprintSha256", session.getSourceTreeFingerprintSha256()),
                field("validationProjectionSha256", session.getValidationProjectionSha256()),
                field("pullRequestStatus", session.getPullRequestStatus()),
                field("pullRequestUrl", session.getPullRequestUrl()),
                field("finalCommitSha", session.getFinalCommitSha()),
                field("closedAt", session.getClosedAt())));
    }

    private static String field(String name, Object value) {
        String normalized = value == null ? "" : value.toString();
        return name + ":" + normalized.length() + ":" + normalized;
    }

    private void requireEnabled() {
        if (!properties.isRemoteCloseReconciliationEnabledFor(
                ProjectCodexIdentity.PROJECT_IDENTITY)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Legacy remote-close reconciliation is disabled");
        }
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

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static ResponseStatusException conflict(String reason) {
        return new ResponseStatusException(HttpStatus.CONFLICT, reason);
    }

    private static ResponseStatusException unprocessable(String reason) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, reason);
    }

    private record ExistingPlan(UUID planId, Long workSessionId) {}
    private record LegacyPlanBinding(
            Long workSessionId, String ownershipFingerprint, Instant expiresAt) {}
    private record ExistingOperation(
            UUID operationId, UUID planId, Long workSessionId,
            String ownershipFingerprint) {}

    public record LegacyRemoteClosePlanRequest(String operation, UUID idempotencyKey) {}

    public record LegacyRemoteCloseConfirmationRequest(
            String operation,
            UUID planId,
            String ownershipFingerprintSha256,
            UUID idempotencyKey) {}

    public record LegacyRemoteClosePlanResponse(
            UUID planId,
            Long workSessionId,
            String operation,
            String state,
            String requiredRole,
            String ownershipFingerprintSha256,
            Instant expiresAt,
            boolean consumed,
            String expectedImpact,
            boolean valuesExposed,
            Instant createdAt) {}

    public record LegacyRemoteCloseOperationResponse(
            UUID operationId,
            UUID planId,
            Long workSessionId,
            String operation,
            String state,
            String ownershipFingerprintSha256,
            Instant requestedAt,
            boolean valuesExposed) {}
}
