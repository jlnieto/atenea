package com.atenea.service.worksession;

import com.atenea.persistence.auth.CodexOperationsRole;
import com.atenea.persistence.auth.OperatorEntity;
import com.atenea.persistence.auth.OperatorRepository;
import com.atenea.persistence.worksession.AgentRunEntity;
import com.atenea.persistence.worksession.AgentRunRecoveryAction;
import com.atenea.persistence.worksession.AgentRunRecoveryOperationEntity;
import com.atenea.persistence.worksession.AgentRunRecoveryOperationRepository;
import com.atenea.persistence.worksession.AgentRunRecoveryOutcome;
import com.atenea.persistence.worksession.AgentRunRecoveryState;
import com.atenea.persistence.worksession.AgentRunRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgentRunRecoveryOperationService {

    private static final EnumSet<AgentRunRecoveryOutcome> SUCCESS_OUTCOMES = EnumSet.of(
            AgentRunRecoveryOutcome.CANCELLED,
            AgentRunRecoveryOutcome.RETRY_CREATED,
            AgentRunRecoveryOutcome.RECONCILED,
            AgentRunRecoveryOutcome.DIAGNOSTIC_READY,
            AgentRunRecoveryOutcome.SERVICE_RESTARTED,
            AgentRunRecoveryOutcome.NO_CHANGE);

    private final OperatorRepository operatorRepository;
    private final AgentRunRepository agentRunRepository;
    private final AgentRunRecoveryOperationRepository operationRepository;
    private final Clock clock;

    @Autowired
    public AgentRunRecoveryOperationService(
            OperatorRepository operatorRepository,
            AgentRunRepository agentRunRepository,
            AgentRunRecoveryOperationRepository operationRepository) {
        this(operatorRepository, agentRunRepository, operationRepository, Clock.systemUTC());
    }

    AgentRunRecoveryOperationService(
            OperatorRepository operatorRepository,
            AgentRunRepository agentRunRepository,
            AgentRunRecoveryOperationRepository operationRepository,
            Clock clock) {
        this.operatorRepository = operatorRepository;
        this.agentRunRepository = agentRunRepository;
        this.operationRepository = operationRepository;
        this.clock = clock;
    }

    @Transactional
    public AgentRunRecoveryRequestResult request(
            Long operatorId,
            Long sessionId,
            Long runId,
            AgentRunRecoveryAction action,
            UUID idempotencyKey) {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");

        OperatorEntity operator = operatorRepository.findByIdForRecoveryRequest(operatorId)
                .orElseThrow(() -> new AgentRunRecoveryAuthorizationException("Operator not found"));
        if (!operator.isActive()) {
            throw new AgentRunRecoveryAuthorizationException("Operator is inactive");
        }
        AgentRunEntity run = agentRunRepository.findWithSessionById(runId)
                .orElseThrow(() -> new AgentRunNotFoundException(runId));
        if (!run.getSession().getId().equals(sessionId)) {
            throw new AgentRunRecoveryAuthorizationException(
                    "AgentRun does not belong to the requested WorkSession");
        }

        CodexOperationsRole role = operator.getCodexOperationsRole();
        String fingerprint = fingerprint(operatorId, sessionId, runId, action, role);
        AgentRunRecoveryOperationEntity existing = operationRepository
                .findByOperatorIdAndIdempotencyKey(operatorId, idempotencyKey)
                .orElse(null);
        if (existing != null) {
            if (!existing.getRequestFingerprintSha256().equals(fingerprint)) {
                throw new AgentRunRecoveryConflictException(
                        "Idempotency key is already bound to another recovery request");
            }
            return new AgentRunRecoveryRequestResult(existing, false);
        }

        Instant now = clock.instant();
        AgentRunRecoveryOperationEntity operation = new AgentRunRecoveryOperationEntity();
        operation.setOperationId(UUID.randomUUID());
        operation.setIdempotencyKey(idempotencyKey);
        operation.setRequestFingerprintSha256(fingerprint);
        operation.setOperator(operator);
        operation.setSession(run.getSession());
        operation.setAgentRun(run);
        operation.setRequestedRole(role);
        operation.setAction(action);
        operation.setRequestedAt(now);
        operation.setCreatedAt(now);
        operation.setUpdatedAt(now);

        if (!action.isAllowedFor(role)) {
            operation.setState(AgentRunRecoveryState.REJECTED);
            operation.setStartedAt(now);
            operation.setCompletedAt(now);
            applyOutcome(operation, AgentRunRecoveryOutcome.ROLE_REQUIRED);
        } else {
            operation.setState(AgentRunRecoveryState.REQUESTED);
        }
        return new AgentRunRecoveryRequestResult(operationRepository.save(operation), true);
    }

    @Transactional
    public AgentRunRecoveryOperationEntity start(UUID operationId) {
        AgentRunRecoveryOperationEntity operation = locked(operationId);
        if (operation.getState() == AgentRunRecoveryState.IN_PROGRESS) {
            return operation;
        }
        if (operation.getState().isTerminal()) {
            throw new AgentRunRecoveryConflictException("Recovery operation is already terminal");
        }
        Instant now = clock.instant();
        operation.setState(AgentRunRecoveryState.IN_PROGRESS);
        operation.setStartedAt(now);
        operation.setUpdatedAt(now);
        return operationRepository.save(operation);
    }

    @Transactional
    public AgentRunRecoveryOperationEntity complete(
            UUID operationId,
            AgentRunRecoveryOutcome outcome,
            Long resultAgentRunId) {
        Objects.requireNonNull(outcome, "outcome");
        AgentRunRecoveryOperationEntity operation = locked(operationId);
        if (operation.getState().isTerminal()) {
            Long existingResultId = operation.getResultAgentRun() == null
                    ? null : operation.getResultAgentRun().getId();
            if (operation.getOutcomeCode() == outcome
                    && Objects.equals(existingResultId, resultAgentRunId)) {
                return operation;
            }
            throw new AgentRunRecoveryConflictException(
                    "Recovery operation already has a different terminal outcome");
        }
        assertOutcomeAllowed(operation.getAction(), outcome);

        AgentRunEntity resultRun = null;
        if (outcome == AgentRunRecoveryOutcome.RETRY_CREATED) {
            if (resultAgentRunId == null) {
                throw new AgentRunRecoveryConflictException("Retry outcome requires its AgentRun");
            }
            resultRun = agentRunRepository.findWithSessionById(resultAgentRunId)
                    .orElseThrow(() -> new AgentRunNotFoundException(resultAgentRunId));
            assertRetryLineage(operation, resultRun);
        } else if (resultAgentRunId != null) {
            throw new AgentRunRecoveryConflictException(
                    "Only a retry outcome may own a result AgentRun");
        }

        Instant now = clock.instant();
        if (operation.getStartedAt() == null) {
            operation.setStartedAt(now);
        }
        operation.setState(outcome == AgentRunRecoveryOutcome.OPERATION_FAILED
                ? AgentRunRecoveryState.FAILED
                : SUCCESS_OUTCOMES.contains(outcome)
                        ? AgentRunRecoveryState.SUCCEEDED
                        : AgentRunRecoveryState.REJECTED);
        operation.setCompletedAt(now);
        operation.setResultAgentRun(resultRun);
        operation.setUpdatedAt(now);
        applyOutcome(operation, outcome);
        return operationRepository.save(operation);
    }

    private AgentRunRecoveryOperationEntity locked(UUID operationId) {
        return operationRepository.findByOperationIdForUpdate(operationId)
                .orElseThrow(() -> new AgentRunRecoveryConflictException(
                        "Recovery operation was not found"));
    }

    private static void assertRetryLineage(
            AgentRunRecoveryOperationEntity operation,
            AgentRunEntity resultRun) {
        AgentRunEntity source = operation.getAgentRun();
        if (operation.getAction() != AgentRunRecoveryAction.RETRY
                || source.getStatus() != com.atenea.persistence.worksession.AgentRunStatus.FAILED
                || !resultRun.getSession().getId().equals(operation.getSession().getId())
                || resultRun.getRetryOfRun() == null
                || !resultRun.getRetryOfRun().getId().equals(source.getId())) {
            throw new AgentRunRecoveryConflictException(
                    "Retry AgentRun lineage or WorkSession ownership does not match");
        }
    }

    private static void assertOutcomeAllowed(
            AgentRunRecoveryAction action,
            AgentRunRecoveryOutcome outcome) {
        boolean allowed = switch (action) {
            case CANCEL -> EnumSet.of(
                    AgentRunRecoveryOutcome.CANCELLED,
                    AgentRunRecoveryOutcome.NO_CHANGE,
                    AgentRunRecoveryOutcome.OWNERSHIP_MISMATCH,
                    AgentRunRecoveryOutcome.WORKER_UNREACHABLE,
                    AgentRunRecoveryOutcome.OPERATION_FAILED).contains(outcome);
            case RETRY -> EnumSet.of(
                    AgentRunRecoveryOutcome.RETRY_CREATED,
                    AgentRunRecoveryOutcome.NOT_TERMINAL,
                    AgentRunRecoveryOutcome.NON_TERMINAL_RUN_EXISTS,
                    AgentRunRecoveryOutcome.EXECUTION_STILL_LIVE,
                    AgentRunRecoveryOutcome.OWNERSHIP_MISMATCH,
                    AgentRunRecoveryOutcome.WORKER_UNREACHABLE,
                    AgentRunRecoveryOutcome.OPERATION_FAILED).contains(outcome);
            case RECONCILE -> EnumSet.of(
                    AgentRunRecoveryOutcome.RECONCILED,
                    AgentRunRecoveryOutcome.EXECUTION_STILL_LIVE,
                    AgentRunRecoveryOutcome.NO_CHANGE,
                    AgentRunRecoveryOutcome.OWNERSHIP_MISMATCH,
                    AgentRunRecoveryOutcome.WORKER_UNREACHABLE,
                    AgentRunRecoveryOutcome.OPERATION_FAILED).contains(outcome);
            case DIAGNOSTIC -> EnumSet.of(
                    AgentRunRecoveryOutcome.DIAGNOSTIC_READY,
                    AgentRunRecoveryOutcome.OWNERSHIP_MISMATCH,
                    AgentRunRecoveryOutcome.WORKER_UNREACHABLE,
                    AgentRunRecoveryOutcome.OPERATION_FAILED).contains(outcome);
            case RESTART_EXECUTION_SERVICE, RESTART_PROJECT_APP_SERVER -> EnumSet.of(
                    AgentRunRecoveryOutcome.SERVICE_RESTARTED,
                    AgentRunRecoveryOutcome.POLICY_BLOCKED,
                    AgentRunRecoveryOutcome.OWNERSHIP_MISMATCH,
                    AgentRunRecoveryOutcome.WORKER_UNREACHABLE,
                    AgentRunRecoveryOutcome.OPERATION_FAILED).contains(outcome);
        };
        if (!allowed) {
            throw new AgentRunRecoveryConflictException(
                    "Recovery outcome is not valid for the requested action");
        }
    }

    private static void applyOutcome(
            AgentRunRecoveryOperationEntity operation,
            AgentRunRecoveryOutcome outcome) {
        operation.setOutcomeCode(outcome);
        operation.setOutcomeSummary(outcome.summary());
        operation.setRequiredNextAction(outcome.nextAction());
    }

    private static String fingerprint(
            Long operatorId,
            Long sessionId,
            Long runId,
            AgentRunRecoveryAction action,
            CodexOperationsRole role) {
        String canonical = operatorId + "\n" + sessionId + "\n" + runId
                + "\n" + action.name() + "\n" + role.name();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
