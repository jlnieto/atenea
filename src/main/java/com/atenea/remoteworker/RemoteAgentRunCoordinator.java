package com.atenea.remoteworker;

import com.atenea.persistence.worksession.AgentRunEntity;
import com.atenea.persistence.worksession.AgentRunRepository;
import com.atenea.persistence.worksession.AgentRunStatus;
import com.atenea.persistence.worksession.ExecutionTarget;
import com.atenea.persistence.worksession.SessionTurnActor;
import com.atenea.persistence.worksession.SessionTurnEntity;
import com.atenea.persistence.worksession.SessionTurnRepository;
import com.atenea.persistence.worksession.WorkSessionEntity;
import com.atenea.persistence.worksession.WorkSessionRepository;
import com.atenea.service.worksession.AgentRunNotFoundException;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class RemoteAgentRunCoordinator {

    private static final Logger log = LoggerFactory.getLogger(RemoteAgentRunCoordinator.class);

    private final AgentRunRepository agentRunRepository;
    private final WorkSessionRepository workSessionRepository;
    private final SessionTurnRepository sessionTurnRepository;
    private final RemoteWorkerClient client;
    private final RemoteWorkerProperties properties;
    private final TransactionTemplate transaction;
    private final ExecutorService executor;

    public RemoteAgentRunCoordinator(
            AgentRunRepository agentRunRepository,
            WorkSessionRepository workSessionRepository,
            SessionTurnRepository sessionTurnRepository,
            RemoteWorkerClient client,
            RemoteWorkerProperties properties,
            PlatformTransactionManager transactionManager
    ) {
        this.agentRunRepository = agentRunRepository;
        this.workSessionRepository = workSessionRepository;
        this.sessionTurnRepository = sessionTurnRepository;
        this.client = client;
        this.properties = properties;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "remote-agent-run-coordinator");
            thread.setDaemon(true);
            return thread;
        };
        this.executor = Executors.newFixedThreadPool(4, factory);
    }

    public void dispatchAfterCommit(Long runId) {
        executor.submit(() -> observe(runId));
    }

    public int reconcileAfterStartup() {
        List<AgentRunEntity> runs = transaction.execute(status ->
                agentRunRepository.findByExecutionTargetAndStatusInOrderByCreatedAtAsc(
                        ExecutionTarget.REMOTE,
                        AgentRunStatus.nonTerminalStatuses()));
        if (runs == null) {
            return 0;
        }
        for (AgentRunEntity run : runs) {
            transaction.executeWithoutResult(status -> {
                AgentRunEntity persisted = agentRunRepository.findById(run.getId()).orElse(null);
                if (persisted != null && persisted.getStatus().isNonTerminal()) {
                    persisted.setStatus(AgentRunStatus.RECONCILING);
                    persisted.setReconciliationStartedAt(Instant.now());
                    persisted.setStatusReason("Reconciling persisted remote execution after Atenea startup");
                    agentRunRepository.save(persisted);
                }
            });
            dispatchAfterCommit(run.getId());
        }
        return runs.size();
    }

    public void requestCancellation(Long runId) {
        AgentRunEntity run = transaction.execute(status -> {
            AgentRunEntity persisted = getRemoteRun(runId);
            if (persisted.getStatus().isTerminal()) {
                return persisted;
            }
            persisted.setStatus(AgentRunStatus.CANCELLING);
            persisted.setCancellationRequestedAt(Instant.now());
            persisted.setStatusReason("Cancellation requested by authenticated operator");
            return agentRunRepository.save(persisted);
        });
        if (run != null && run.getStatus() == AgentRunStatus.CANCELLING) {
            executor.submit(() -> {
                try {
                    AgentRunEntity cancellable = run;
                    if (cancellable.getRemoteExecutionId() == null) {
                        apply(runId, client.get(cancellable));
                        cancellable = transaction.execute(status -> getRemoteRun(runId));
                    }
                    apply(runId, client.cancel(cancellable));
                    observe(runId);
                } catch (RemoteWorkerException exception) {
                    if (run.getRemoteExecutionId() == null && exception.getStatusCode() == 404) {
                        cancelBeforeAdmission(runId);
                        return;
                    }
                    markReconciling(runId, exception.getMessage());
                }
            });
        }
    }

    private void observe(Long runId) {
        Instant unavailableSince = null;
        while (!Thread.currentThread().isInterrupted()) {
            AgentRunEntity run = transaction.execute(status ->
                    agentRunRepository.findWithSessionById(runId).orElse(null));
            if (run == null || run.getStatus().isTerminal() || run.getExecutionTarget() != ExecutionTarget.REMOTE) {
                return;
            }
            try {
                RemoteWorkerClient.Execution response;
                if (run.getRemoteExecutionId() == null) {
                    response = client.dispatch(run, run.getOriginTurn().getMessageText());
                } else if (run.getLeaseExpiresAt() != null
                        && run.getLeaseExpiresAt().isBefore(Instant.now().plusSeconds(30))) {
                    response = client.renew(run);
                } else {
                    response = client.get(run);
                }
                apply(runId, response);
                unavailableSince = null;
            } catch (RemoteWorkerException exception) {
                unavailableSince = unavailableSince == null ? Instant.now() : unavailableSince;
                markReconciling(runId, exception.getMessage());
                if (unavailableSince.plus(properties.getReconciliationTimeout()).isBefore(Instant.now())) {
                    failAfterReconciliationTimeout(runId);
                    return;
                }
            }

            AgentRunEntity after = transaction.execute(status -> agentRunRepository.findById(runId).orElse(null));
            if (after == null || after.getStatus().isTerminal()) {
                return;
            }
            try {
                Thread.sleep(properties.getPollInterval().toMillis());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void apply(Long runId, RemoteWorkerClient.Execution response) {
        transaction.executeWithoutResult(status -> {
            AgentRunEntity run = getRemoteRun(runId);
            if (run.getStatus().isTerminal()) {
                return;
            }
            verifyOwnership(run, response);
            if (response.revision() < run.getLifecycleRevision()) {
                return;
            }
            run.setRemoteExecutionId(response.executionId());
            run.setLifecycleRevision(response.revision());
            run.setLastHeartbeatAt(Instant.now());
            run.setLeaseExpiresAt(Instant.now().plus(properties.getLeaseDuration()));
            run.setStatusReason(response.statusReason());

            AgentRunStatus remoteStatus = AgentRunStatus.valueOf(response.status());
            if (!remoteStatus.isTerminal()) {
                run.setStatus(remoteStatus);
                agentRunRepository.save(run);
                return;
            }
            Instant finishedAt = response.finishedAt() == null ? Instant.now() : response.finishedAt();
            run.setFinishedAt(finishedAt);

            if (remoteStatus == AgentRunStatus.SUCCEEDED) {
                if (response.result() == null) {
                    run.setStatus(AgentRunStatus.FAILED);
                    run.setErrorSummary("Remote worker returned SUCCEEDED without a result");
                    agentRunRepository.save(run);
                    return;
                }
                WorkSessionEntity session = workSessionRepository.findById(run.getSession().getId()).orElseThrow();
                session.setExternalThreadId(response.result().threadId());
                session.setLastActivityAt(finishedAt);
                session.setUpdatedAt(finishedAt);
                workSessionRepository.save(session);

                SessionTurnEntity resultTurn = new SessionTurnEntity();
                resultTurn.setSession(session);
                resultTurn.setActor(SessionTurnActor.CODEX);
                resultTurn.setMessageText(response.result().finalAnswer() == null ? "" : response.result().finalAnswer());
                resultTurn.setInternal(false);
                resultTurn.setCreatedAt(finishedAt);
                resultTurn = sessionTurnRepository.save(resultTurn);

                run.setExternalTurnId(response.result().turnId());
                run.setResultTurn(resultTurn);
                run.setOutputSummary(response.result().outputSummary());
                run.setErrorSummary(null);
            } else {
                run.setOutputSummary(null);
                run.setErrorSummary(remoteStatus == AgentRunStatus.FAILED ? response.statusReason() : null);
            }
            run.setStatus(remoteStatus);
            agentRunRepository.save(run);
        });
    }

    private void verifyOwnership(AgentRunEntity run, RemoteWorkerClient.Execution response) {
        if (!run.getDispatchId().toString().equals(response.dispatchId())
                || !run.getWorkspaceIdentity().equals(response.workspaceIdentity())
                || run.getRemoteSessionId() == null
                || !run.getRemoteSessionId().toString().equals(response.sessionId())
                || (run.getRemoteExecutionId() != null
                    && !run.getRemoteExecutionId().equals(response.executionId()))) {
            throw new RemoteWorkerException("Remote worker ownership response does not match persisted AgentRun", 409);
        }
    }

    private void markReconciling(Long runId, String reason) {
        transaction.executeWithoutResult(status -> {
            AgentRunEntity run = agentRunRepository.findById(runId).orElse(null);
            if (run == null || run.getStatus().isTerminal()) {
                return;
            }
            run.setStatus(AgentRunStatus.RECONCILING);
            run.setReconciliationStartedAt(
                    run.getReconciliationStartedAt() == null ? Instant.now() : run.getReconciliationStartedAt());
            run.setStatusReason("Remote worker unavailable; no replacement dispatched: " + safeReason(reason));
            agentRunRepository.save(run);
        });
    }

    private void failAfterReconciliationTimeout(Long runId) {
        transaction.executeWithoutResult(status -> {
            AgentRunEntity run = agentRunRepository.findById(runId).orElse(null);
            if (run == null || run.getStatus().isTerminal()) {
                return;
            }
            run.setStatus(AgentRunStatus.FAILED);
            run.setFinishedAt(Instant.now());
            run.setErrorSummary("Remote worker remained unavailable through the bounded reconciliation window");
            run.setStatusReason("Explicit operator review required; execution was not reassigned");
            agentRunRepository.save(run);
        });
    }

    private void cancelBeforeAdmission(Long runId) {
        transaction.executeWithoutResult(status -> {
            AgentRunEntity run = agentRunRepository.findById(runId).orElse(null);
            if (run == null || run.getStatus().isTerminal()) {
                return;
            }
            run.setStatus(AgentRunStatus.CANCELLED);
            run.setFinishedAt(Instant.now());
            run.setStatusReason("Cancelled after worker proved the dispatch identity was not admitted");
            agentRunRepository.save(run);
        });
    }

    private AgentRunEntity getRemoteRun(Long runId) {
        AgentRunEntity run = agentRunRepository.findWithSessionById(runId)
                .orElseThrow(() -> new AgentRunNotFoundException(runId));
        if (run.getExecutionTarget() != ExecutionTarget.REMOTE) {
            throw new IllegalArgumentException("AgentRun is not owned by a remote execution target");
        }
        return run;
    }

    private String safeReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "connection failed";
        }
        String normalized = reason.replaceAll("[\\r\\n\\t]+", " ").trim();
        return normalized.substring(0, Math.min(normalized.length(), 300));
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
