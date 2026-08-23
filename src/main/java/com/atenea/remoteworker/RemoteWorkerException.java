package com.atenea.remoteworker;

import com.atenea.persistence.worksession.AgentRunRecoveryNextAction;
import java.util.UUID;

public class RemoteWorkerException extends RuntimeException {

    private final int statusCode;
    private final String failureCode;
    private final RemoteWorkerFailureCategory category;
    private final boolean retryable;
    private final AgentRunRecoveryNextAction nextAction;
    private final UUID blockerSessionId;

    public RemoteWorkerException(String message, int statusCode) {
        super(message);
        this.statusCode = statusCode;
        this.failureCode = null;
        this.category = null;
        this.retryable = false;
        this.nextAction = AgentRunRecoveryNextAction.NONE;
        this.blockerSessionId = null;
    }

    public RemoteWorkerException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = 0;
        this.failureCode = "REMOTE_WORKER_TRANSPORT_FAILURE";
        this.category = RemoteWorkerFailureCategory.TRANSPORT;
        this.retryable = true;
        this.nextAction = AgentRunRecoveryNextAction.REQUEST_RECONCILIATION;
        this.blockerSessionId = null;
    }

    public RemoteWorkerException(
            String message,
            int statusCode,
            String failureCode,
            RemoteWorkerFailureCategory category,
            boolean retryable,
            AgentRunRecoveryNextAction nextAction,
            UUID blockerSessionId
    ) {
        super(message);
        this.statusCode = statusCode;
        this.failureCode = failureCode;
        this.category = category;
        this.retryable = retryable;
        this.nextAction = nextAction;
        this.blockerSessionId = blockerSessionId;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public String getFailureCode() {
        return failureCode;
    }

    public RemoteWorkerFailureCategory getCategory() {
        return category;
    }

    public boolean isRetryable() {
        return retryable;
    }

    public AgentRunRecoveryNextAction getNextAction() {
        return nextAction;
    }

    public UUID getBlockerSessionId() {
        return blockerSessionId;
    }

    public boolean hasTypedFailure() {
        return failureCode != null && category != null;
    }

    public boolean isCompatibleTransportFailure() {
        return hasTypedFailure()
                && category == RemoteWorkerFailureCategory.TRANSPORT
                && retryable
                && nextAction == AgentRunRecoveryNextAction.REQUEST_RECONCILIATION
                && (statusCode == 0 || (statusCode >= 500 && statusCode < 600));
    }

    public boolean isCompatibleCapacityWaitFailure() {
        return hasTypedFailure()
                && statusCode >= 400
                && statusCode < 500
                && category == RemoteWorkerFailureCategory.CAPACITY
                && retryable
                && nextAction == AgentRunRecoveryNextAction.WAIT;
    }

    public boolean isCompatibleDeterministicFailure() {
        if (!hasTypedFailure() || statusCode < 400 || statusCode >= 500) {
            return false;
        }
        if (category == RemoteWorkerFailureCategory.CAPACITY) {
            return isCompatibleCapacityWaitFailure();
        }
        return category != RemoteWorkerFailureCategory.TRANSPORT
                && !retryable
                && blockerSessionId == null
                && (nextAction == AgentRunRecoveryNextAction.NONE
                    || nextAction == AgentRunRecoveryNextAction.CONTACT_PLATFORM_ADMINISTRATOR);
    }
}
