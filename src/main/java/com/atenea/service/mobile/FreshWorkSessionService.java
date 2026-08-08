package com.atenea.service.mobile;

import com.atenea.api.mobile.StartFreshWorkSessionRequest;
import com.atenea.api.mobile.StartFreshWorkSessionResponse;
import com.atenea.auth.AuthenticatedOperator;
import com.atenea.persistence.auth.CodexOperationsRole;
import com.atenea.persistence.auth.OperatorEntity;
import com.atenea.persistence.auth.OperatorRepository;
import com.atenea.persistence.worksession.AgentRunEntity;
import com.atenea.persistence.worksession.AgentRunRepository;
import com.atenea.persistence.worksession.WorkSessionEntity;
import com.atenea.persistence.worksession.WorkSessionRepository;
import com.atenea.persistence.worksession.WorkSessionStatus;
import com.atenea.remoteworker.ProjectCodexIdentity;
import com.atenea.remoteworker.RemoteWorkerClient;
import com.atenea.remoteworker.RemoteWorkerProperties;
import com.atenea.service.worksession.AgentRunService;
import com.atenea.service.worksession.WorkSessionService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class FreshWorkSessionService {

    private final JdbcTemplate jdbcTemplate;
    private final OperatorRepository operatorRepository;
    private final WorkSessionRepository workSessionRepository;
    private final AgentRunRepository agentRunRepository;
    private final AgentRunService agentRunService;
    private final RemoteWorkerProperties properties;
    private final RemoteWorkerClient remoteWorkerClient;
    private final WorkSessionService workSessionService;
    private final TransactionTemplate transaction;

    public FreshWorkSessionService(
            JdbcTemplate jdbcTemplate,
            OperatorRepository operatorRepository,
            WorkSessionRepository workSessionRepository,
            AgentRunRepository agentRunRepository,
            AgentRunService agentRunService,
            RemoteWorkerProperties properties,
            RemoteWorkerClient remoteWorkerClient,
            WorkSessionService workSessionService,
            PlatformTransactionManager transactionManager
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.operatorRepository = operatorRepository;
        this.workSessionRepository = workSessionRepository;
        this.agentRunRepository = agentRunRepository;
        this.agentRunService = agentRunService;
        this.properties = properties;
        this.remoteWorkerClient = remoteWorkerClient;
        this.workSessionService = workSessionService;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    public StartFreshWorkSessionResponse start(
            AuthenticatedOperator authenticated,
            Long sourceSessionId,
            StartFreshWorkSessionRequest request
    ) {
        if (authenticated == null || request == null || request.idempotencyKey() == null) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "Exact fresh-session request is required");
        }
        if (!properties.isFreshSessionOnSourceAdvanceEnabledFor(
                ProjectCodexIdentity.PROJECT_IDENTITY)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Fresh session is disabled");
        }
        FreshOperation operation;
        try {
            operation = transaction.execute(ignored -> requestOperation(
                    authenticated.operatorId(), sourceSessionId, request.idempotencyKey()));
        } catch (DataIntegrityViolationException concurrentRequest) {
            operation = findByOperatorAndKey(
                    authenticated.operatorId(), request.idempotencyKey());
            if (operation == null || !sourceSessionId.equals(operation.sourceSessionId())) {
                throw conflict("Fresh-session request conflicts with another operation");
            }
        }
        if (operation == null) {
            throw new IllegalStateException("Fresh-session operation was not persisted");
        }
        return resume(operation.operationId());
    }

    private FreshOperation requestOperation(
            Long operatorId,
            Long sourceSessionId,
            UUID idempotencyKey
    ) {
        FreshOperation idempotent = findByOperatorAndKey(operatorId, idempotencyKey);
        if (idempotent != null) {
            if (!sourceSessionId.equals(idempotent.sourceSessionId())) {
                throw conflict("Idempotency key belongs to another fresh-session request");
            }
            return idempotent;
        }
        FreshOperation sourceOperation = findBySourceSession(sourceSessionId);
        if (sourceOperation != null) {
            throw conflict("WorkSession already has a different fresh-session operation");
        }
        OperatorEntity operator = operatorRepository.findByIdForRecoveryRequest(operatorId)
                .orElseThrow(() -> forbidden("Operator not found"));
        if (!operator.isActive()
                || operator.getCodexOperationsRole()
                    != CodexOperationsRole.PLATFORM_ADMINISTRATOR) {
            throw forbidden("PLATFORM_ADMINISTRATOR is required");
        }
        WorkSessionEntity source = workSessionRepository
                .findLockedWithProjectById(sourceSessionId)
                .orElseThrow(() -> conflict("Source WorkSession was not found"));
        AgentRunEntity run = agentRunRepository
                .findFirstBySessionIdOrderByCreatedAtDesc(sourceSessionId)
                .orElseThrow(() -> conflict("Source WorkSession has no retained AgentRun"));
        if (source.getStatus() != WorkSessionStatus.OPEN
                || run.getSession() == null
                || !sourceSessionId.equals(run.getSession().getId())
                || !ProjectCodexIdentity.matches(run)
                || !agentRunService.isRemoteRetryEligible(run.getId())) {
            throw conflict("Source WorkSession is not eligible for a fresh current-code session");
        }
        RemoteWorkerClient.WorkspaceReadiness readiness =
                remoteWorkerClient.diagnoseWorkspaceReadiness(run);
        if (!"SOURCE_ADVANCED".equals(readiness.state())
                || readiness.retryAllowed()
                || !"START_FRESH_SESSION".equals(readiness.nextAction())) {
            throw conflict("Canonical source has not advanced exactly");
        }
        UUID operationId = UUID.randomUUID();
        String fingerprint = sha256(String.join("\n",
                operatorId.toString(),
                sourceSessionId.toString(),
                run.getId().toString(),
                idempotencyKey.toString(),
                readiness.requestedCommit(),
                readiness.canonicalCommit(),
                readiness.relationshipFingerprintSha256()));
        Instant now = Instant.now();
        jdbcTemplate.update("""
                INSERT INTO fresh_work_session_operation (
                    operation_id, idempotency_key, request_fingerprint_sha256,
                    operator_id, source_work_session_id, source_agent_run_id,
                    requested_commit, canonical_commit,
                    relationship_fingerprint_sha256, state, requested_at,
                    updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'REQUESTED', ?, ?)
                """,
                operationId,
                idempotencyKey,
                fingerprint,
                operatorId,
                sourceSessionId,
                run.getId(),
                readiness.requestedCommit(),
                readiness.canonicalCommit(),
                readiness.relationshipFingerprintSha256(),
                Timestamp.from(now),
                Timestamp.from(now));
        return findByOperation(operationId);
    }

    private StartFreshWorkSessionResponse resume(UUID operationId) {
        FreshOperation operation = findByOperation(operationId);
        if (operation == null) {
            throw conflict("Fresh-session operation was not found");
        }
        if ("COMPLETED".equals(operation.state())) {
            return completedResponse(operation, false);
        }
        if ("REQUESTED".equals(operation.state())) {
            workSessionService.closeSession(operation.sourceSessionId());
            transaction.executeWithoutResult(ignored -> markSourceReleased(operationId));
            operation = findByOperation(operationId);
        }
        ResolvedSuccessor resolved = transaction.execute(
                ignored -> resolveAndComplete(operationId));
        if (resolved == null) {
            throw conflict("Fresh successor was not durably resolved");
        }
        return completedResponse(findByOperation(operationId), resolved.created());
    }

    private ResolvedSuccessor resolveAndComplete(UUID operationId) {
        FreshOperation operation = findByOperationForUpdate(operationId);
        if ("COMPLETED".equals(operation.state())) {
            return new ResolvedSuccessor(operation.resultSessionId(), false);
        }
        if (!"SOURCE_RELEASED".equals(operation.state())) {
            throw conflict("Source WorkSession is not durably released");
        }
        WorkSessionEntity source = workSessionRepository
                .findWithProjectById(operation.sourceSessionId())
                .orElseThrow(() -> conflict("Source WorkSession was not found"));
        var resolved = workSessionService.resolveFreshSessionConversationView(
                source.getProject().getId(),
                source.getTitle(),
                operationId);
        Long resultId = resolved.view().view().session().id();
        complete(operationId, resultId);
        return new ResolvedSuccessor(resultId, resolved.created());
    }

    private void markSourceReleased(UUID operationId) {
        FreshOperation operation = findByOperationForUpdate(operationId);
        if (!"REQUESTED".equals(operation.state())) {
            return;
        }
        WorkSessionEntity source = workSessionRepository
                .findLockedWithProjectById(operation.sourceSessionId())
                .orElseThrow(() -> conflict("Source WorkSession was not found"));
        if (source.getStatus() != WorkSessionStatus.CLOSED
                || source.getRemoteCloseState()
                    != com.atenea.persistence.worksession.RemoteCloseState.RELEASED
                || source.getRemoteCloseReceiptSha256() == null
                || !source.getRemoteCloseReceiptSha256().matches("^[0-9a-f]{64}$")) {
            throw conflict("Source WorkSession release receipt is not durable");
        }
        Instant now = Instant.now();
        jdbcTemplate.update("""
                UPDATE fresh_work_session_operation
                SET state = 'SOURCE_RELEASED', source_released_at = ?, updated_at = ?
                WHERE operation_id = ? AND state = 'REQUESTED'
                """, Timestamp.from(now), Timestamp.from(now), operationId);
    }

    private void complete(UUID operationId, Long resultSessionId) {
        FreshOperation operation = findByOperationForUpdate(operationId);
        if ("COMPLETED".equals(operation.state())) {
            if (!resultSessionId.equals(operation.resultSessionId())) {
                throw conflict("Fresh-session operation already owns another successor");
            }
            return;
        }
        if (!"SOURCE_RELEASED".equals(operation.state())) {
            throw conflict("Source WorkSession is not durably released");
        }
        WorkSessionEntity result = workSessionRepository
                .findWithProjectById(resultSessionId)
                .orElseThrow(() -> conflict("Fresh successor was not found"));
        if (!operationId.equals(result.getFreshStartOperationId())
                || result.getStatus() != WorkSessionStatus.OPEN) {
            throw conflict("Fresh successor identity is not exact");
        }
        Instant now = Instant.now();
        jdbcTemplate.update("""
                UPDATE fresh_work_session_operation
                SET state = 'COMPLETED', result_work_session_id = ?,
                    completed_at = ?, updated_at = ?
                WHERE operation_id = ? AND state = 'SOURCE_RELEASED'
                """, resultSessionId, Timestamp.from(now), Timestamp.from(now), operationId);
    }

    private StartFreshWorkSessionResponse completedResponse(
            FreshOperation operation,
            boolean created
    ) {
        if (operation == null || operation.resultSessionId() == null) {
            throw conflict("Fresh-session operation is not complete");
        }
        return new StartFreshWorkSessionResponse(
                operation.operationId(),
                operation.state(),
                operation.sourceSessionId(),
                operation.resultSessionId(),
                created,
                workSessionService.getSessionConversationView(operation.resultSessionId()));
    }

    private FreshOperation findByOperatorAndKey(Long operatorId, UUID key) {
        return query("""
                SELECT operation_id, idempotency_key, operator_id,
                       source_work_session_id, source_agent_run_id, state,
                       result_work_session_id
                FROM fresh_work_session_operation
                WHERE operator_id = ? AND idempotency_key = ?
                """, operatorId, key);
    }

    private FreshOperation findBySourceSession(Long sessionId) {
        return query("""
                SELECT operation_id, idempotency_key, operator_id,
                       source_work_session_id, source_agent_run_id, state,
                       result_work_session_id
                FROM fresh_work_session_operation
                WHERE source_work_session_id = ?
                """, sessionId);
    }

    private FreshOperation findByOperation(UUID operationId) {
        return query("""
                SELECT operation_id, idempotency_key, operator_id,
                       source_work_session_id, source_agent_run_id, state,
                       result_work_session_id
                FROM fresh_work_session_operation
                WHERE operation_id = ?
                """, operationId);
    }

    private FreshOperation findByOperationForUpdate(UUID operationId) {
        return query("""
                SELECT operation_id, idempotency_key, operator_id,
                       source_work_session_id, source_agent_run_id, state,
                       result_work_session_id
                FROM fresh_work_session_operation
                WHERE operation_id = ? FOR UPDATE
                """, operationId);
    }

    private FreshOperation query(String sql, Object... arguments) {
        List<FreshOperation> rows = jdbcTemplate.query(sql, (result, ignored) ->
                new FreshOperation(
                        result.getObject("operation_id", UUID.class),
                        result.getObject("idempotency_key", UUID.class),
                        result.getLong("operator_id"),
                        result.getLong("source_work_session_id"),
                        result.getLong("source_agent_run_id"),
                        result.getString("state"),
                        result.getObject("result_work_session_id", Long.class)),
                arguments);
        return rows.stream().findFirst().orElse(null);
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    private ResponseStatusException forbidden(String message) {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, message);
    }

    private record FreshOperation(
            UUID operationId,
            UUID idempotencyKey,
            Long operatorId,
            Long sourceSessionId,
            Long sourceAgentRunId,
            String state,
            Long resultSessionId
    ) {
    }

    private record ResolvedSuccessor(Long sessionId, boolean created) {
    }
}
