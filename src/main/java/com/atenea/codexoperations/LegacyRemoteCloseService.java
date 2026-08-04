package com.atenea.codexoperations;

import com.atenea.auth.AuthenticatedOperator;
import com.atenea.persistence.auth.CodexOperationsRole;
import com.atenea.persistence.auth.OperatorRepository;
import com.atenea.persistence.worksession.AgentRunRecoveryNextAction;
import com.atenea.persistence.worksession.ExecutionTarget;
import com.atenea.persistence.worksession.RemoteCloseState;
import com.atenea.persistence.worksession.WorkSessionEntity;
import com.atenea.persistence.worksession.WorkSessionRepository;
import com.atenea.persistence.worksession.WorkSessionStatus;
import com.atenea.remoteworker.ProjectCodexIdentity;
import com.atenea.remoteworker.RemoteWorkerProperties;
import com.atenea.remoteworker.RemoteWorkerClient;
import com.atenea.remoteworker.RemoteWorkerException;
import com.atenea.remoteworker.RemoteWorkerFailureCategory;
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
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
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
    private final RemoteWorkerClient remoteWorkerClient;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    @Autowired
    public LegacyRemoteCloseService(
            RemoteWorkerProperties properties,
            JdbcTemplate jdbcTemplate,
            OperatorRepository operatorRepository,
            WorkSessionRepository sessionRepository,
            RemoteWorkerClient remoteWorkerClient,
            PlatformTransactionManager transactionManager) {
        this(properties, jdbcTemplate, operatorRepository, sessionRepository,
                remoteWorkerClient, requiresNew(transactionManager),
                Clock.systemUTC());
    }

    private static TransactionTemplate requiresNew(
            PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template;
    }

    LegacyRemoteCloseService(
            RemoteWorkerProperties properties,
            JdbcTemplate jdbcTemplate,
            OperatorRepository operatorRepository,
            WorkSessionRepository sessionRepository) {
        this(properties, jdbcTemplate, operatorRepository, sessionRepository,
                Clock.systemUTC());
    }

    LegacyRemoteCloseService(
            RemoteWorkerProperties properties,
            JdbcTemplate jdbcTemplate,
            OperatorRepository operatorRepository,
            WorkSessionRepository sessionRepository,
            Clock clock) {
        this(properties, jdbcTemplate, operatorRepository, sessionRepository,
                null, null, clock);
    }

    LegacyRemoteCloseService(
            RemoteWorkerProperties properties,
            JdbcTemplate jdbcTemplate,
            OperatorRepository operatorRepository,
            WorkSessionRepository sessionRepository,
            RemoteWorkerClient remoteWorkerClient,
            TransactionTemplate transactionTemplate,
            Clock clock) {
        this.properties = properties;
        this.jdbcTemplate = jdbcTemplate;
        this.operatorRepository = operatorRepository;
        this.sessionRepository = sessionRepository;
        this.remoteWorkerClient = remoteWorkerClient;
        this.transactionTemplate = transactionTemplate;
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

        LegacyReleaseInvocation invocation = Objects.requireNonNull(
                transactionTemplate.execute(ignored -> persistLegacyReleaseRequest(
                        operator, sessionId, request)),
                "Legacy remote-close request transaction returned no result");
        if (invocation.releasedResponse() != null) {
            return invocation.releasedResponse();
        }
        RemoteWorkerClient.WorkspaceRelease receipt;
        try {
            receipt = remoteWorkerClient.releaseWorkspace(invocation.session());
        } catch (RemoteWorkerException exception) {
            return Objects.requireNonNull(
                    transactionTemplate.execute(ignored -> persistLegacyReleaseFailure(
                            invocation.operationId(), exception)),
                    "Legacy remote-close failure transaction returned no result");
        }
        return Objects.requireNonNull(
                transactionTemplate.execute(ignored -> persistLegacyReleaseReceipt(
                        invocation.operationId(), invocation.planId(),
                        invocation.ownershipFingerprint(), receipt)),
                "Legacy remote-close receipt transaction returned no result");
    }

    private LegacyReleaseInvocation persistLegacyReleaseRequest(
            AuthenticatedOperator operator,
            Long sessionId,
            LegacyRemoteCloseConfirmationRequest request) {

        List<ExistingOperation> idempotent = existingOperation(
                operator.operatorId(), request.idempotencyKey());
        if (!idempotent.isEmpty()) {
            return resumeExistingOperation(
                    idempotent.getFirst(), sessionId, request);
        }

        LegacyPlanBinding plan = lockedPlan(request.planId());
        idempotent = existingOperation(operator.operatorId(), request.idempotencyKey());
        if (!idempotent.isEmpty()) {
            return resumeExistingOperation(
                    idempotent.getFirst(), sessionId, request);
        }
        if (!plan.workSessionId().equals(sessionId)
                || !plan.ownershipFingerprint().equals(
                        request.ownershipFingerprintSha256())) {
            throw conflict("Legacy close confirmation does not match its read-only plan");
        }
        if (!clock.instant().isBefore(plan.expiresAt())) {
            throw conflict("Legacy close confirmation expired; create a fresh read-only plan");
        }
        WorkSessionEntity session = exactLegacySessionLocked(sessionId);
        if (!plan.ownershipFingerprint().equals(ownershipFingerprint(session))) {
            throw conflict("Legacy close ownership changed; create a fresh read-only plan");
        }
        requireTerminalRuns(sessionId);

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
        int inserted = jdbcTemplate.update("""
                INSERT INTO remote_close_legacy_operation (
                    operation_id, plan_id, work_session_id, requested_by,
                    idempotency_key, operation, ownership_fingerprint_sha256,
                    request_fingerprint_sha256, state, revision, retryable,
                    requested_at, updated_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'REQUESTED', 1, FALSE, ?, ?, ?)
                ON CONFLICT DO NOTHING
                """, operationId, request.planId(), sessionId, operator.operatorId(),
                request.idempotencyKey(), OPERATION,
                request.ownershipFingerprintSha256(), requestFingerprint,
                Timestamp.from(createdAt), Timestamp.from(createdAt),
                Timestamp.from(createdAt));

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
        if (inserted == 0) {
            return resumeExistingOperation(operation, sessionId, request);
        }
        appendLegacyEvent(operation.operationId(), 1, "REQUESTED",
                null, null, null, false, null, createdAt);
        session.setRemoteCloseOperationId(operation.operationId());
        session.setRemoteCloseState(RemoteCloseState.REQUESTED);
        session.setRemoteCloseRevision(1);
        session.setRemoteCloseRequestedAt(createdAt);
        session.setRemoteCloseUpdatedAt(createdAt);
        session.setUpdatedAt(createdAt);
        WorkSessionEntity persistedSession = sessionRepository.saveAndFlush(session);
        return new LegacyReleaseInvocation(
                operation.operationId(), request.planId(),
                request.ownershipFingerprintSha256(), persistedSession, null);
    }

    private LegacyReleaseInvocation resumeExistingOperation(
            ExistingOperation existing,
            Long sessionId,
            LegacyRemoteCloseConfirmationRequest request) {
        if (!existing.planId().equals(request.planId())
                || !existing.workSessionId().equals(sessionId)
                || !existing.ownershipFingerprint().equals(
                        request.ownershipFingerprintSha256())) {
            throw conflict("Idempotency key belongs to a different legacy close operation");
        }
        LegacyPlanBinding plan = lockedPlan(existing.planId());
        WorkSessionEntity session = exactRequestedLegacySession(
                sessionId, existing.operationId());
        if (!plan.ownershipFingerprint().equals(ownershipFingerprint(session))) {
            throw conflict("Legacy close ownership changed; create a fresh read-only plan");
        }
        requireTerminalRuns(sessionId);
        if (session.getRemoteCloseState() == RemoteCloseState.RELEASED) {
            return new LegacyReleaseInvocation(
                    existing.operationId(), existing.planId(),
                    existing.ownershipFingerprint(), session,
                    operationForAdministrator(existing.operationId()));
        }
        if (session.getRemoteCloseState() == RemoteCloseState.BLOCKED) {
            return new LegacyReleaseInvocation(
                    existing.operationId(), existing.planId(),
                    existing.ownershipFingerprint(), session,
                    operationForAdministrator(existing.operationId()));
        }
        return new LegacyReleaseInvocation(
                existing.operationId(), existing.planId(),
                existing.ownershipFingerprint(), session, null);
    }

    private LegacyRemoteCloseOperationResponse persistLegacyReleaseFailure(
            UUID operationId, RemoteWorkerException exception) {
        WorkSessionEntity session = exactRequestedLegacySession(
                workSessionIdForOperation(operationId), operationId);
        if (session.getRemoteCloseState() == RemoteCloseState.RELEASED) {
            return operationForAdministrator(operationId);
        }
        LegacyFailure failure = safeFailure(exception);
        LegacyAudit audit = currentAudit(operationId);
        Instant now = clock.instant();
        long revision = audit.revision() + 1;
        int updated = jdbcTemplate.update("""
                UPDATE remote_close_legacy_operation
                   SET state = ?, revision = ?, error_code = ?, error_category = ?,
                       next_action = ?, retryable = ?, updated_at = ?
                 WHERE operation_id = ? AND revision = ?
                """, failure.state(), revision, failure.errorCode(),
                failure.category(), failure.nextAction(), failure.retryable(),
                Timestamp.from(now), operationId, audit.revision());
        if (updated != 1) {
            throw conflict("Legacy close lifecycle changed during failure persistence");
        }
        appendLegacyEvent(operationId, revision, failure.state(),
                failure.errorCode(), failure.category(), failure.nextAction(),
                failure.retryable(), null, now);
        session.setRemoteCloseState("RECONCILING".equals(failure.state())
                ? RemoteCloseState.RECONCILING : RemoteCloseState.BLOCKED);
        session.setRemoteCloseRevision(session.getRemoteCloseRevision() + 1);
        session.setRemoteCloseErrorCode(failure.errorCode());
        session.setRemoteCloseUpdatedAt(now);
        session.setUpdatedAt(now);
        sessionRepository.saveAndFlush(session);
        return operationForAdministrator(operationId);
    }

    private LegacyRemoteCloseOperationResponse persistLegacyReleaseReceipt(
            UUID operationId,
            UUID planId,
            String ownershipFingerprint,
            RemoteWorkerClient.WorkspaceRelease receipt) {
        WorkSessionEntity session = exactRequestedLegacySession(
                workSessionIdForOperation(operationId), operationId);
        LegacyPlanBinding plan = lockedPlan(planId);
        if (!plan.workSessionId().equals(session.getId())
                || !plan.ownershipFingerprint().equals(ownershipFingerprint)
                || !ownershipFingerprint.equals(ownershipFingerprint(session))) {
            throw conflict("Legacy close ownership changed before receipt persistence");
        }
        requireTerminalRuns(session.getId());
        if (!operationId.toString().equals(receipt.operationId())
                || !"RELEASED".equals(receipt.state())) {
            throw conflict("Legacy close release receipt identity changed");
        }
        if (session.getRemoteCloseState() == RemoteCloseState.RELEASED) {
            if (!receipt.receiptSha256().equals(session.getRemoteCloseReceiptSha256())) {
                throw conflict("Legacy close release receipt changed after persistence");
            }
            return operationForAdministrator(operationId);
        }
        Instant releasedAt = clock.instant();
        LegacyAudit audit = currentAudit(operationId);
        long auditRevision = audit.revision() + 1;
        int updated = jdbcTemplate.update("""
                UPDATE remote_close_legacy_operation
                   SET state = 'RELEASED', revision = ?, error_code = NULL,
                       error_category = NULL, next_action = NULL,
                       retryable = FALSE, receipt_sha256 = ?, updated_at = ?,
                       released_at = ?
                 WHERE operation_id = ? AND revision = ?
                """, auditRevision, receipt.receiptSha256(),
                Timestamp.from(releasedAt), Timestamp.from(releasedAt),
                operationId, audit.revision());
        if (updated != 1) {
            throw conflict("Legacy close lifecycle changed before receipt persistence");
        }
        appendLegacyEvent(operationId, auditRevision, "RELEASED",
                null, null, null, false, receipt.receiptSha256(), releasedAt);
        session.setRemoteCloseState(RemoteCloseState.RELEASED);
        session.setRemoteCloseRevision(Math.max(
                session.getRemoteCloseRevision() + 1, receipt.revision()));
        session.setRemoteCloseReceiptSha256(receipt.receiptSha256());
        session.setRemoteCloseErrorCode(null);
        session.setRemoteCloseUpdatedAt(releasedAt);
        session.setRemoteCloseReleasedAt(releasedAt);
        session.setUpdatedAt(releasedAt);
        sessionRepository.saveAndFlush(session);
        return operationForAdministrator(operationId);
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
                SELECT o.operation_id, o.plan_id, o.work_session_id, o.operation,
                       o.state, o.revision, o.ownership_fingerprint_sha256,
                       o.error_code, o.error_category, o.next_action, o.retryable,
                       o.receipt_sha256, o.requested_at, o.updated_at, o.released_at
                  FROM remote_close_legacy_operation o
                 WHERE o.operation_id = ?
                """, (rs, row) -> new LegacyRemoteCloseOperationResponse(
                (UUID) rs.getObject("operation_id"),
                (UUID) rs.getObject("plan_id"),
                rs.getLong("work_session_id"), rs.getString("operation"),
                rs.getString("state"), rs.getLong("revision"),
                rs.getString("ownership_fingerprint_sha256"),
                rs.getString("error_code"), rs.getString("error_category"),
                currentNextAction(rs.getString("state"), rs.getString("next_action")),
                rs.getBoolean("retryable"), rs.getString("receipt_sha256"),
                rs.getTimestamp("requested_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                rs.getTimestamp("released_at") == null
                        ? null : rs.getTimestamp("released_at").toInstant(),
                false), operationId)
                .stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Legacy remote-close operation not found"));
    }

    private static String currentNextAction(String state, String persisted) {
        if (persisted != null) {
            return persisted;
        }
        return switch (state) {
            case "REQUESTED" -> AgentRunRecoveryNextAction.WAIT.name();
            case "RELEASED" -> AgentRunRecoveryNextAction.NONE.name();
            default -> AgentRunRecoveryNextAction.CONTACT_PLATFORM_ADMINISTRATOR.name();
        };
    }

    private Long workSessionIdForOperation(UUID operationId) {
        return jdbcTemplate.query("""
                SELECT work_session_id
                  FROM remote_close_legacy_operation
                 WHERE operation_id = ?
                """, (rs, row) -> rs.getLong("work_session_id"), operationId)
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
        return requireExactLegacySession(session);
    }

    private WorkSessionEntity exactLegacySessionLocked(Long sessionId) {
        WorkSessionEntity session = sessionRepository.findLockedWithProjectById(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "WorkSession not found"));
        return requireExactLegacySession(session);
    }

    private WorkSessionEntity requireExactLegacySession(WorkSessionEntity session) {
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

    private WorkSessionEntity exactRequestedLegacySession(
            Long sessionId, UUID operationId) {
        WorkSessionEntity session = sessionRepository.findLockedWithProjectById(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "WorkSession not found"));
        String remoteId = session.getRemoteSessionId() == null
                ? null : session.getRemoteSessionId().toString();
        if (session.getStatus() != WorkSessionStatus.CLOSED
                || !Set.of(RemoteCloseState.REQUESTED, RemoteCloseState.RECONCILING,
                        RemoteCloseState.BLOCKED, RemoteCloseState.RELEASED)
                        .contains(session.getRemoteCloseState())
                || !operationId.equals(session.getRemoteCloseOperationId())
                || session.getRemoteCloseRevision() < 1
                || session.getRemoteCloseRequestedAt() == null
                || session.getRemoteCloseUpdatedAt() == null
                || session.getExecutionTarget() != ExecutionTarget.REMOTE
                || !ProjectCodexIdentity.hasCanonicalSourceObservation(session)
                || !ProjectCodexIdentity.WORKER_ID.equals(session.getSelectedWorkerId())
                || !ProjectCodexIdentity.WORKLOAD_KIND.equals(session.getRemoteWorkloadKind())
                || remoteId == null
                || !("remote:" + ProjectCodexIdentity.WORKER_ID
                    + ":work-session:" + remoteId).equals(session.getWorkspaceIdentity())
                || !("atenea/session-" + remoteId).equals(session.getWorkspaceBranch())) {
            throw conflict("Selected WorkSession is not an exact requested legacy Atenea owner");
        }
        return session;
    }

    private void requireTerminalRuns(Long sessionId) {
        Integer nonTerminal = jdbcTemplate.queryForObject("""
                SELECT count(*)
                  FROM agent_run
                 WHERE session_id = ?
                   AND status IN (
                       'QUEUED', 'STARTING', 'RUNNING', 'CANCELLING', 'RECONCILING')
                """, Integer.class, sessionId);
        if (nonTerminal == null || nonTerminal != 0) {
            throw conflict("Selected WorkSession still owns a non-terminal AgentRun");
        }
    }

    private LegacyAudit currentAudit(UUID operationId) {
        return jdbcTemplate.query("""
                SELECT state, revision
                  FROM remote_close_legacy_operation
                 WHERE operation_id = ?
                 FOR UPDATE
                """, (rs, row) -> new LegacyAudit(
                rs.getString("state"), rs.getLong("revision")), operationId)
                .stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Legacy remote-close operation not found"));
    }

    private void appendLegacyEvent(
            UUID operationId,
            long revision,
            String state,
            String errorCode,
            String category,
            String nextAction,
            boolean retryable,
            String receiptSha256,
            Instant occurredAt) {
        jdbcTemplate.update("""
                INSERT INTO remote_close_legacy_event (
                    operation_id, revision, state, error_code, error_category,
                    next_action, retryable, receipt_sha256, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, operationId, revision, state, errorCode, category,
                nextAction, retryable, receiptSha256, Timestamp.from(occurredAt));
    }

    private LegacyFailure safeFailure(RemoteWorkerException exception) {
        RemoteWorkerFailureCategory category = exception.getCategory();
        String code = exception.getFailureCode();
        if (category == null || code == null || !code.matches("^[A-Z][A-Z0-9_]{2,79}$")) {
            category = RemoteWorkerFailureCategory.PROTOCOL;
            code = "REMOTE_CLOSE_PROTOCOL_FAILURE";
        }
        boolean reconciling = category == RemoteWorkerFailureCategory.TRANSPORT
                || (exception.getStatusCode() >= 500
                    && category != RemoteWorkerFailureCategory.PROTOCOL);
        if (reconciling) {
            return new LegacyFailure(
                    "RECONCILING", code, category.name(),
                    AgentRunRecoveryNextAction.REQUEST_RECONCILIATION.name(), true);
        }
        AgentRunRecoveryNextAction action = exception.getNextAction();
        if (action == null || !Set.of(
                AgentRunRecoveryNextAction.WAIT,
                AgentRunRecoveryNextAction.REQUEST_RECONCILIATION,
                AgentRunRecoveryNextAction.CONTACT_PLATFORM_ADMINISTRATOR)
                .contains(action)) {
            action = AgentRunRecoveryNextAction.CONTACT_PLATFORM_ADMINISTRATOR;
        }
        return new LegacyFailure(
                "BLOCKED", code, category.name(), action.name(),
                exception.isRetryable());
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
    private record LegacyAudit(String state, long revision) {}
    private record LegacyFailure(
            String state,
            String errorCode,
            String category,
            String nextAction,
            boolean retryable) {}
    private record LegacyReleaseInvocation(
            UUID operationId,
            UUID planId,
            String ownershipFingerprint,
            WorkSessionEntity session,
            LegacyRemoteCloseOperationResponse releasedResponse) {}

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
            long revision,
            String ownershipFingerprintSha256,
            String errorCode,
            String errorCategory,
            String nextAction,
            boolean retryable,
            String receiptSha256,
            Instant requestedAt,
            Instant updatedAt,
            Instant releasedAt,
            boolean valuesExposed) {}
}
