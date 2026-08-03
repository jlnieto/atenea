package com.atenea.attachments;

import java.util.List;

public record AttachmentCapability(
        State state,
        BlockedReason blockedReason,
        String message,
        String nextAction,
        String policyRevision,
        WorkerCompatibility workerCompatibility,
        List<String> acceptedContentTypes,
        long currentSessionBytes,
        long maxSessionBytes,
        long remainingSessionBytes,
        long maxFileBytes,
        int maxAttachmentsPerTurn,
        long maxAttachmentBytesPerTurn
) {

    public enum State {
        READY,
        BLOCKED
    }

    public enum BlockedReason {
        NONE,
        GLOBAL_DISABLED,
        PROJECT_DISABLED,
        SESSION_NOT_ELIGIBLE,
        OWNERSHIP_INVALID,
        SESSION_QUOTA_EXHAUSTED,
        WORKER_UNAVAILABLE,
        WORKER_UNSUPPORTED
    }

    public enum WorkerCompatibility {
        NOT_CHECKED,
        UNAVAILABLE,
        INCOMPATIBLE,
        COMPATIBLE
    }
}
