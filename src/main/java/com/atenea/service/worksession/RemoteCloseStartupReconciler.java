package com.atenea.service.worksession;

import com.atenea.persistence.worksession.ExecutionTarget;
import com.atenea.persistence.worksession.RemoteCloseState;
import com.atenea.persistence.worksession.WorkSessionEntity;
import com.atenea.persistence.worksession.WorkSessionRepository;
import com.atenea.persistence.worksession.WorkSessionStatus;
import com.atenea.remoteworker.ProjectCodexIdentity;
import com.atenea.remoteworker.RemoteWorkerProperties;
import java.util.List;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class RemoteCloseStartupReconciler {

    private final WorkSessionRepository workSessionRepository;
    private final WorkSessionService workSessionService;
    private final RemoteWorkerProperties remoteWorkerProperties;

    public RemoteCloseStartupReconciler(
            WorkSessionRepository workSessionRepository,
            WorkSessionService workSessionService,
            RemoteWorkerProperties remoteWorkerProperties
    ) {
        this.workSessionRepository = workSessionRepository;
        this.workSessionService = workSessionService;
        this.remoteWorkerProperties = remoteWorkerProperties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void reconcilePersistedRemoteCloses() {
        if (!remoteWorkerProperties.isRemoteCloseReconciliationEnabledFor(
                ProjectCodexIdentity.PROJECT_IDENTITY)) {
            return;
        }
        List<WorkSessionEntity> closing = workSessionRepository
                .findByStatusInOrderByLastActivityAtDesc(List.of(WorkSessionStatus.CLOSING));
        for (WorkSessionEntity session : closing) {
            if (isAutomaticallyReconcilable(session)) {
                try {
                    workSessionService.reconcileRemoteClose(session.getId());
                } catch (RuntimeException ignored) {
                    // The durable projection remains the only retry authority.
                }
            }
        }
    }

    private boolean isAutomaticallyReconcilable(WorkSessionEntity session) {
        return session.getExecutionTarget() == ExecutionTarget.REMOTE
                && ProjectCodexIdentity.matches(session)
                && session.getRemoteCloseOperationId() != null
                && (session.getRemoteCloseState() == RemoteCloseState.REQUESTED
                    || session.getRemoteCloseState() == RemoteCloseState.RECONCILING);
    }
}
