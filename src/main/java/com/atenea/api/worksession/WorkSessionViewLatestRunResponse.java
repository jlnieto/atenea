package com.atenea.api.worksession;

import com.atenea.persistence.worksession.AgentRunStatus;
import com.atenea.persistence.worksession.AgentRunRecoveryNextAction;
import com.atenea.persistence.worksession.ExecutionTarget;
import com.atenea.persistence.worksession.WorkloadClass;
import java.time.Instant;
import java.util.UUID;

public record WorkSessionViewLatestRunResponse(
        Long id,
        AgentRunStatus status,
        Long originTurnId,
        Long resultTurnId,
        String externalTurnId,
        Instant startedAt,
        Instant finishedAt,
        String outputSummary,
        String errorSummary,
        ExecutionTarget executionTarget,
        String selectedWorkerId,
        String workspaceIdentity,
        UUID dispatchId,
        String remoteExecutionId,
        WorkloadClass workloadClass,
        long lifecycleRevision,
        String statusReason,
        String failureCode,
        AgentRunRecoveryNextAction recoveryNextAction
) {
    public WorkSessionViewLatestRunResponse(
            Long id,
            AgentRunStatus status,
            Long originTurnId,
            Long resultTurnId,
            String externalTurnId,
            Instant startedAt,
            Instant finishedAt,
            String outputSummary,
            String errorSummary,
            ExecutionTarget executionTarget,
            String selectedWorkerId,
            String workspaceIdentity,
            UUID dispatchId,
            String remoteExecutionId,
            WorkloadClass workloadClass,
            long lifecycleRevision,
            String statusReason
    ) {
        this(
                id, status, originTurnId, resultTurnId, externalTurnId,
                startedAt, finishedAt, outputSummary, errorSummary,
                executionTarget, selectedWorkerId, workspaceIdentity,
                dispatchId, remoteExecutionId, workloadClass,
                lifecycleRevision, statusReason, null, null);
    }

    public WorkSessionViewLatestRunResponse(
            Long id,
            AgentRunStatus status,
            Long originTurnId,
            Long resultTurnId,
            String externalTurnId,
            Instant startedAt,
            Instant finishedAt,
            String outputSummary,
            String errorSummary
    ) {
        this(
                id, status, originTurnId, resultTurnId, externalTurnId, startedAt,
                finishedAt, outputSummary, errorSummary, ExecutionTarget.LOCAL,
                null, null, null, null, WorkloadClass.NORMAL, 0, null, null, null);
    }
}
