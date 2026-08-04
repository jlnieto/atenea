package com.atenea.codexoperations;

import com.atenea.persistence.worksession.AgentRunEntity;
import com.atenea.persistence.worksession.AgentRunRecoveryAction;
import com.atenea.persistence.worksession.AgentRunRecoveryOperationEntity;
import com.atenea.persistence.worksession.AgentRunRecoveryOperationRepository;
import com.atenea.persistence.worksession.AgentRunRecoveryOutcome;
import com.atenea.persistence.worksession.AgentRunRecoveryState;
import com.atenea.persistence.worksession.AgentRunRepository;
import com.atenea.persistence.worksession.AgentRunStatus;
import com.atenea.remoteworker.RemoteAgentRunCoordinator;
import com.atenea.remoteworker.RemoteWorkerException;
import com.atenea.remoteworker.CanonicalSourceAdmissionService;
import com.atenea.service.worksession.AgentRunRecoveryConflictException;
import com.atenea.service.worksession.AgentRunRecoveryOperationService;
import com.atenea.service.worksession.AgentRunService;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class AgentRunRecoveryCoordinator {

    private static final Logger log = LoggerFactory.getLogger(AgentRunRecoveryCoordinator.class);
    private static final Duration TERMINAL_WAIT = Duration.ofMinutes(3);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(250);

    private final AgentRunRecoveryOperationService operationService;
    private final AgentRunRecoveryOperationRepository operationRepository;
    private final AgentRunRepository runRepository;
    private final AgentRunService runService;
    private final RemoteAgentRunCoordinator remoteCoordinator;
    private final CanonicalSourceAdmissionService canonicalSourceAdmissionService;
    private final ExecutorService executor;

    public AgentRunRecoveryCoordinator(
            AgentRunRecoveryOperationService operationService,
            AgentRunRecoveryOperationRepository operationRepository,
            AgentRunRepository runRepository,
            AgentRunService runService,
            RemoteAgentRunCoordinator remoteCoordinator,
            CanonicalSourceAdmissionService canonicalSourceAdmissionService) {
        this.operationService = operationService;
        this.operationRepository = operationRepository;
        this.runRepository = runRepository;
        this.runService = runService;
        this.remoteCoordinator = remoteCoordinator;
        this.canonicalSourceAdmissionService = canonicalSourceAdmissionService;
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "agent-run-recovery-coordinator");
            thread.setDaemon(true);
            return thread;
        };
        this.executor = Executors.newSingleThreadExecutor(factory);
    }

    public void schedule(UUID operationId) {
        executor.submit(() -> executeOne(operationId));
    }

    public int resumePending() {
        List<AgentRunRecoveryOperationEntity> pending = operationRepository
                .findByStateInOrderByCreatedAtAsc(List.of(
                        AgentRunRecoveryState.REQUESTED,
                        AgentRunRecoveryState.IN_PROGRESS));
        pending.forEach(operation -> schedule(operation.getOperationId()));
        return pending.size();
    }

    void executeOne(UUID operationId) {
        AgentRunRecoveryOperationEntity operation;
        try {
            operation = operationService.start(operationId);
        } catch (AgentRunRecoveryConflictException exception) {
            return;
        }
        Long runId = operation.getAgentRun().getId();
        try {
            switch (operation.getAction()) {
                case CANCEL -> cancel(operationId, runId);
                case RECONCILE -> reconcile(operationId, runId);
                case RETRY -> retry(operationId, runId);
                case DIAGNOSTIC -> diagnostic(operationId, runId);
                case RESTART_EXECUTION_SERVICE, RESTART_PROJECT_APP_SERVER ->
                        operationService.complete(
                                operationId, AgentRunRecoveryOutcome.POLICY_BLOCKED, null);
            }
        } catch (RemoteWorkerException exception) {
            operationService.complete(operationId, remoteFailure(exception), null);
        } catch (RuntimeException exception) {
            log.warn("recovery operation failed safely operationId={} action={}",
                    operationId, operation.getAction(), exception);
            operationService.complete(operationId, AgentRunRecoveryOutcome.OPERATION_FAILED, null);
        }
    }

    private void cancel(UUID operationId, Long runId) {
        AgentRunEntity before = runRepository.findById(runId).orElseThrow();
        if (before.getStatus().isTerminal()) {
            operationService.complete(operationId,
                    before.getStatus() == AgentRunStatus.CANCELLED
                            ? AgentRunRecoveryOutcome.CANCELLED
                            : AgentRunRecoveryOutcome.NO_CHANGE,
                    null);
            return;
        }
        remoteCoordinator.requestCancellation(runId);
        AgentRunStatus terminal = awaitTerminal(runId);
        AgentRunRecoveryOutcome outcome = switch (terminal) {
            case CANCELLED -> AgentRunRecoveryOutcome.CANCELLED;
            case SUCCEEDED, FAILED -> AgentRunRecoveryOutcome.NO_CHANGE;
            case RECONCILING -> AgentRunRecoveryOutcome.WORKER_UNREACHABLE;
            default -> AgentRunRecoveryOutcome.OPERATION_FAILED;
        };
        operationService.complete(operationId, outcome, null);
    }

    private void reconcile(UUID operationId, Long runId) {
        AgentRunEntity before = runRepository.findById(runId).orElseThrow();
        if (before.getStatus().isTerminal()) {
            operationService.complete(operationId, AgentRunRecoveryOutcome.NO_CHANGE, null);
            return;
        }
        AgentRunStatus status = remoteCoordinator.requestReconciliation(runId);
        operationService.complete(operationId,
                status != null && status.isTerminal()
                        ? AgentRunRecoveryOutcome.RECONCILED
                        : AgentRunRecoveryOutcome.EXECUTION_STILL_LIVE,
                null);
    }

    private void retry(UUID operationId, Long runId) {
        AgentRunEntity source = runRepository.findWithSessionById(runId).orElseThrow();
        if (source.getStatus() != AgentRunStatus.FAILED) {
            operationService.complete(operationId, AgentRunRecoveryOutcome.NOT_TERMINAL, null);
            return;
        }
        if (runRepository.existsBySessionIdAndStatusIn(
                source.getSession().getId(), AgentRunStatus.nonTerminalStatuses())) {
            operationService.complete(
                    operationId, AgentRunRecoveryOutcome.NON_TERMINAL_RUN_EXISTS, null);
            return;
        }
        try {
            runService.requireRemoteRetryEligible(source);
        } catch (AgentRunRecoveryConflictException exception) {
            operationService.complete(
                    operationId, AgentRunRecoveryOutcome.POLICY_BLOCKED, null);
            return;
        }
        canonicalSourceAdmissionService.admitBeforeWrite(source.getSession());
        RemoteAgentRunCoordinator.RetryProof proof = remoteCoordinator.proveTerminalOrAbsent(runId);
        if (proof == RemoteAgentRunCoordinator.RetryProof.STILL_LIVE) {
            operationService.complete(
                    operationId, AgentRunRecoveryOutcome.EXECUTION_STILL_LIVE, null);
            return;
        }
        AgentRunEntity retry = runService.createRemoteRetryRun(runId);
        operationService.complete(
                operationId, AgentRunRecoveryOutcome.RETRY_CREATED, retry.getId());
        remoteCoordinator.dispatchAfterCommit(retry.getId());
    }

    private void diagnostic(UUID operationId, Long runId) {
        remoteCoordinator.requestDiagnostic(runId);
        operationService.complete(
                operationId, AgentRunRecoveryOutcome.DIAGNOSTIC_READY, null);
    }

    private AgentRunStatus awaitTerminal(Long runId) {
        Instant deadline = Instant.now().plus(TERMINAL_WAIT);
        AgentRunStatus latest = runRepository.findById(runId).orElseThrow().getStatus();
        while (!latest.isTerminal() && Instant.now().isBefore(deadline)) {
            try {
                Thread.sleep(POLL_INTERVAL.toMillis());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return latest;
            }
            latest = runRepository.findById(runId).orElseThrow().getStatus();
        }
        return latest;
    }

    private static AgentRunRecoveryOutcome remoteFailure(RemoteWorkerException exception) {
        return exception.getStatusCode() == 409
                ? AgentRunRecoveryOutcome.OWNERSHIP_MISMATCH
                : AgentRunRecoveryOutcome.WORKER_UNREACHABLE;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
