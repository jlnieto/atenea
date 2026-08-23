package com.atenea.api.worksession;

import com.atenea.persistence.worksession.AgentRunStatus;
import com.atenea.persistence.worksession.AgentRunProcessOutcome;
import com.atenea.persistence.worksession.AgentRunRecoveryNextAction;
import com.atenea.persistence.worksession.ExecutionTarget;
import com.atenea.persistence.worksession.WorkloadClass;
import java.time.Instant;
import java.util.UUID;

public record AgentRunResponse(
        Long id,
        Long sessionId,
        Long originTurnId,
        Long resultTurnId,
        AgentRunStatus status,
        String targetRepoPath,
        String externalTurnId,
        Instant startedAt,
        Instant finishedAt,
        String outputSummary,
        String errorSummary,
        Instant createdAt,
        ExecutionTarget executionTarget,
        String selectedWorkerId,
        String workspaceIdentity,
        UUID dispatchId,
        String remoteExecutionId,
        WorkloadClass workloadClass,
        long leaseGeneration,
        Instant leaseExpiresAt,
        Instant lastHeartbeatAt,
        long lifecycleRevision,
        String statusReason,
        AgentRunProcessOutcome processOutcome,
        String failureCode,
        AgentRunRecoveryNextAction recoveryNextAction
) {
    public AgentRunResponse(
            Long id,
            Long sessionId,
            Long originTurnId,
            Long resultTurnId,
            AgentRunStatus status,
            String targetRepoPath,
            String externalTurnId,
            Instant startedAt,
            Instant finishedAt,
            String outputSummary,
            String errorSummary,
            Instant createdAt,
            ExecutionTarget executionTarget,
            String selectedWorkerId,
            String workspaceIdentity,
            UUID dispatchId,
            String remoteExecutionId,
            WorkloadClass workloadClass,
            long leaseGeneration,
            Instant leaseExpiresAt,
            Instant lastHeartbeatAt,
            long lifecycleRevision,
            String statusReason,
            AgentRunProcessOutcome processOutcome
    ) {
        this(
                id, sessionId, originTurnId, resultTurnId, status,
                targetRepoPath, externalTurnId, startedAt, finishedAt,
                outputSummary, errorSummary, createdAt, executionTarget,
                selectedWorkerId, workspaceIdentity, dispatchId,
                remoteExecutionId, workloadClass, leaseGeneration,
                leaseExpiresAt, lastHeartbeatAt, lifecycleRevision,
                statusReason, processOutcome, null, null);
    }

    public AgentRunResponse(
            Long id,
            Long sessionId,
            Long originTurnId,
            Long resultTurnId,
            AgentRunStatus status,
            String targetRepoPath,
            String externalTurnId,
            Instant startedAt,
            Instant finishedAt,
            String outputSummary,
            String errorSummary,
            Instant createdAt
    ) {
        this(
                id, sessionId, originTurnId, resultTurnId, status, targetRepoPath,
                externalTurnId, startedAt, finishedAt, outputSummary, errorSummary,
                createdAt, ExecutionTarget.LOCAL, null,
                sessionId == null ? null : "local:work-session:" + sessionId,
                null, null, WorkloadClass.NORMAL, 0, null, null, 0, null,
                AgentRunProcessOutcome.fromStatus(status), null, null);
    }
}
