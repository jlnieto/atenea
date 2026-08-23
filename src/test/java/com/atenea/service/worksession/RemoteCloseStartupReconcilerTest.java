package com.atenea.service.worksession;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.atenea.persistence.project.ProjectEntity;
import com.atenea.persistence.worksession.ExecutionTarget;
import com.atenea.persistence.worksession.RemoteCloseState;
import com.atenea.persistence.worksession.WorkSessionEntity;
import com.atenea.persistence.worksession.WorkSessionRepository;
import com.atenea.persistence.worksession.WorkSessionStatus;
import com.atenea.remoteworker.ProjectCodexIdentity;
import com.atenea.remoteworker.RemoteWorkerProperties;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RemoteCloseStartupReconcilerTest {

    private final WorkSessionRepository repository = mock(WorkSessionRepository.class);
    private final WorkSessionService service = mock(WorkSessionService.class);
    private final RemoteWorkerProperties properties = mock(RemoteWorkerProperties.class);
    private final RemoteCloseStartupReconciler reconciler =
            new RemoteCloseStartupReconciler(repository, service, properties);

    @Test
    void defaultOffPerformsNoQueryOrWorkerReconciliation() {
        when(properties.isRemoteCloseReconciliationEnabledFor(
                ProjectCodexIdentity.PROJECT_IDENTITY)).thenReturn(false);

        reconciler.reconcilePersistedRemoteCloses();

        verify(repository, never()).findByStatusInOrderByLastActivityAtDesc(
                org.mockito.ArgumentMatchers.any());
        verify(service, never()).reconcileRemoteClose(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void startupReconcilesOnlyPersistedRequestedOrReconcilingAteneaOwners() {
        WorkSessionEntity requested = session(41L, RemoteCloseState.REQUESTED);
        WorkSessionEntity reconciling = session(42L, RemoteCloseState.RECONCILING);
        WorkSessionEntity blocked = session(43L, RemoteCloseState.BLOCKED);
        WorkSessionEntity foreign = session(44L, RemoteCloseState.REQUESTED);
        foreign.getProject().setName("Beautips");
        WorkSessionEntity local = session(45L, RemoteCloseState.REQUESTED);
        local.setExecutionTarget(ExecutionTarget.LOCAL);
        when(properties.isRemoteCloseReconciliationEnabledFor(
                ProjectCodexIdentity.PROJECT_IDENTITY)).thenReturn(true);
        when(repository.findByStatusInOrderByLastActivityAtDesc(
                List.of(WorkSessionStatus.CLOSING)))
                .thenReturn(List.of(requested, reconciling, blocked, foreign, local));
        doThrow(new IllegalStateException("response lost"))
                .when(service).reconcileRemoteClose(41L);

        reconciler.reconcilePersistedRemoteCloses();

        verify(service).reconcileRemoteClose(41L);
        verify(service).reconcileRemoteClose(42L);
        verify(service, never()).reconcileRemoteClose(43L);
        verify(service, never()).reconcileRemoteClose(44L);
        verify(service, never()).reconcileRemoteClose(45L);
    }

    private WorkSessionEntity session(Long id, RemoteCloseState state) {
        UUID remoteSessionId = UUID.randomUUID();
        ProjectEntity project = new ProjectEntity();
        project.setName(ProjectCodexIdentity.PROJECT_NAME);
        project.setRepoPath(ProjectCodexIdentity.REPO_PATH);
        WorkSessionEntity session = new WorkSessionEntity();
        session.setId(id);
        session.setProject(project);
        session.setStatus(WorkSessionStatus.CLOSING);
        session.setBaseBranch(ProjectCodexIdentity.BRANCH);
        session.setExecutionTarget(ExecutionTarget.REMOTE);
        session.setRemoteSessionId(remoteSessionId);
        session.setRemoteCloseOperationId(UUID.randomUUID());
        session.setRemoteCloseState(state);
        return session;
    }
}
