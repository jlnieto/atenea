package com.atenea.api.worksession;

import com.atenea.persistence.worksession.PreviewState;
import com.atenea.persistence.worksession.WorkSessionPreviewEntity;
import java.time.Instant;
import java.util.UUID;

public record WorkSessionPreviewResponse(
        UUID id,
        Long workSessionId,
        Long projectId,
        Long agentRunId,
        PreviewState state,
        long lifecycleRevision,
        String privateUrl,
        boolean localhostCompatible,
        Instant leaseExpiresAt,
        Instant hardExpiresAt,
        Instant auditRetainUntil,
        String failureReason,
        String nextAction,
        String primaryAction
) {
    public static WorkSessionPreviewResponse from(WorkSessionPreviewEntity preview) {
        return new WorkSessionPreviewResponse(
                preview.getId(),
                preview.getWorkSession().getId(),
                preview.getProject().getId(),
                preview.getAgentRun() == null ? null : preview.getAgentRun().getId(),
                preview.getState(),
                preview.getLifecycleRevision(),
                preview.getState() == PreviewState.READY ? preview.getPrivateUrl() : null,
                preview.isLocalhostCompatible(),
                preview.getLeaseExpiresAt(),
                preview.getHardExpiresAt(),
                preview.getAuditRetainUntil(),
                preview.getFailureReason(),
                preview.getNextAction(),
                action(preview.getState()));
    }

    private static String action(PreviewState state) {
        return switch (state) {
            case STARTING, RECONCILING -> "WAIT";
            case READY -> "OPEN";
            case BLOCKED, STOPPED, EXPIRED -> "START";
        };
    }
}
