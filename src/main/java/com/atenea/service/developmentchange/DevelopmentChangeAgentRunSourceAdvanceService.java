package com.atenea.service.developmentchange;

import com.atenea.persistence.developmentchange.DevelopmentChangeEntity;
import com.atenea.persistence.developmentchange.DevelopmentChangeProjectionState;
import com.atenea.persistence.developmentchange.DevelopmentChangeRepository;
import com.atenea.persistence.developmentchange.DevelopmentChangeSourceState;
import com.atenea.persistence.developmentchange.DevelopmentChangeStatus;
import com.atenea.persistence.developmentchange.DevelopmentChangeWorkspaceOperationRepository;
import com.atenea.persistence.developmentchange.DevelopmentChangeWorkspaceOperationState;
import com.atenea.persistence.developmentchange.DevelopmentChangeWorkspaceState;
import com.atenea.persistence.worksession.AgentRunEntity;
import com.atenea.persistence.worksession.ExecutionTarget;
import com.atenea.persistence.worksession.WorkSessionEntity;
import com.atenea.persistence.worksession.WorkSessionStatus;
import com.atenea.remoteworker.ProjectCodexIdentity;
import com.atenea.remoteworker.RemoteWorkerClient;
import com.atenea.remoteworker.RemoteWorkerException;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DevelopmentChangeAgentRunSourceAdvanceService {

    private static final Set<DevelopmentChangeWorkspaceOperationState> ACTIVE_WORKSPACE_STATES =
            Set.of(
                    DevelopmentChangeWorkspaceOperationState.REQUESTED,
                    DevelopmentChangeWorkspaceOperationState.DISPATCHED);

    private final DevelopmentChangeRepository changeRepository;
    private final DevelopmentChangeWorkspaceOperationRepository workspaceOperationRepository;

    public DevelopmentChangeAgentRunSourceAdvanceService(
            DevelopmentChangeRepository changeRepository,
            DevelopmentChangeWorkspaceOperationRepository workspaceOperationRepository
    ) {
        this.changeRepository = changeRepository;
        this.workspaceOperationRepository = workspaceOperationRepository;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean advance(
            AgentRunEntity run,
            RemoteWorkerClient.SourceIdentity postRun,
            Instant finishedAt
    ) {
        if (!ProjectCodexIdentity.CHANGE_WORKLOAD_KIND.equals(run.getWorkloadKind())) {
            return false;
        }
        WorkSessionEntity session = run.getSession();
        DevelopmentChangeEntity linked = session == null ? null : session.getDevelopmentChange();
        if (!validResultBinding(run, session, postRun) || linked == null) {
            reject();
        }
        DevelopmentChangeEntity change = changeRepository
                .findByChangeKeyForUpdate(run.getDevelopmentChangeKey())
                .orElseThrow(DevelopmentChangeAgentRunSourceAdvanceService::rejected);
        String exactWorkspace = "remote:" + ProjectCodexIdentity.WORKER_ID
                + ":change:" + run.getDevelopmentChangeKey();
        String exactBranch = "atenea/change-" + run.getDevelopmentChangeKey();
        if (change.getId() == null
                || linked.getId() == null
                || !Objects.equals(change.getId(), linked.getId())
                || change.getProject() == null
                || session.getProject() == null
                || !Objects.equals(change.getProject().getId(), session.getProject().getId())
                || change.getStatus() != DevelopmentChangeStatus.OPEN
                || change.getWorkspaceState() != DevelopmentChangeWorkspaceState.READY
                || change.getSourceState() == DevelopmentChangeSourceState.STALE
                || change.getSourceState() == DevelopmentChangeSourceState.BLOCKED
                || session.getStatus() != WorkSessionStatus.OPEN
                || session.getExecutionTarget() != ExecutionTarget.REMOTE
                || !ProjectCodexIdentity.WORKLOAD_KIND.equals(
                        session.getRemoteWorkloadKind())
                || !Objects.equals(session.getRemoteSessionId(), run.getRemoteSessionId())
                || !Objects.equals(change.getSelectedWorkerId(), ProjectCodexIdentity.WORKER_ID)
                || !Objects.equals(session.getSelectedWorkerId(), change.getSelectedWorkerId())
                || !Objects.equals(run.getSelectedWorkerId(), change.getSelectedWorkerId())
                || !Objects.equals(change.getWorkspaceIdentity(), exactWorkspace)
                || !Objects.equals(session.getWorkspaceIdentity(), exactWorkspace)
                || !Objects.equals(run.getWorkspaceIdentity(), exactWorkspace)
                || !Objects.equals(change.getWorkspaceBranch(), exactBranch)
                || !Objects.equals(session.getWorkspaceBranch(), exactBranch)
                || !Objects.equals(change.getBaseRef(),
                        "refs/heads/" + ProjectCodexIdentity.BRANCH)
                || !Objects.equals(change.getBaseCommit(), run.getChangeBaseCommit())
                || !Objects.equals(run.getRepositoryCommit(),
                        change.getObservedCanonicalCommit())
                || change.getSourceRevision() != run.getChangeSourceRevision()
                || (change.getSourceState() == DevelopmentChangeSourceState.DIRTY
                    && !Objects.equals(change.getSourceFingerprintSha256(),
                            run.getChangeSourceFingerprintSha256()))
                || change.getWorkspaceOperationRevision() < 1
                || change.getWorkspaceUpdatedAt() == null
                || workspaceOperationRepository.existsByDevelopmentChangeIdAndStateIn(
                        change.getId(), ACTIVE_WORKSPACE_STATES)) {
            reject();
        }

        boolean expectedDirty = change.getSourceState() == DevelopmentChangeSourceState.DIRTY;
        boolean sourceChanged = !Objects.equals(run.getRepositoryCommit(), postRun.sourceCommit())
                || postRun.workspaceDirty() != expectedDirty
                || (expectedDirty && !Objects.equals(
                        run.getChangeSourceFingerprintSha256(),
                        postRun.sourceFingerprintSha256()));
        if (!sourceChanged) {
            return false;
        }

        change.setSourceRevision(Math.addExact(change.getSourceRevision(), 1L));
        change.setObservedCanonicalCommit(postRun.sourceCommit());
        if (postRun.workspaceDirty()) {
            change.setSourceFingerprintSha256(postRun.sourceFingerprintSha256());
        }
        change.setSourceState(postRun.workspaceDirty()
                ? DevelopmentChangeSourceState.DIRTY
                : DevelopmentChangeSourceState.CLEAN);
        change.setValidationState(staleIfCurrent(change.getValidationState()));
        change.setReviewState(staleIfCurrent(change.getReviewState()));
        change.setIntegrationState(staleIfCurrent(change.getIntegrationState()));
        change.setReleaseState(staleIfCurrent(change.getReleaseState()));
        change.setWorkspaceUpdatedAt(finishedAt);
        change.setUpdatedAt(finishedAt);
        changeRepository.saveAndFlush(change);
        return true;
    }

    private boolean validResultBinding(
            AgentRunEntity run,
            WorkSessionEntity session,
            RemoteWorkerClient.SourceIdentity postRun
    ) {
        return postRun != null
                && run.getId() != null
                && run.getDevelopmentChangeKey() != null
                && run.getChangeSourceRevision() != null
                && run.getRemoteExecutionId() != null
                && run.getRemoteSessionId() != null
                && session != null
                && session.getId() != null
                && session.getRemoteSessionId() != null
                && Objects.equals(postRun.changeKey(), run.getDevelopmentChangeKey().toString())
                && Objects.equals(postRun.databaseWorkSessionId(), session.getId())
                && Objects.equals(postRun.remoteSessionId(), run.getRemoteSessionId().toString())
                && Objects.equals(postRun.workspaceIdentity(), run.getWorkspaceIdentity())
                && Objects.equals(postRun.executionId(), run.getRemoteExecutionId())
                && gitCommit(postRun.sourceCommit())
                && postRun.workspaceDirty() != null
                && (postRun.workspaceDirty()
                    ? sha256(postRun.sourceFingerprintSha256())
                    : postRun.sourceFingerprintSha256() == null);
    }

    private DevelopmentChangeProjectionState staleIfCurrent(
            DevelopmentChangeProjectionState state
    ) {
        return state == DevelopmentChangeProjectionState.CURRENT
                ? DevelopmentChangeProjectionState.STALE
                : state;
    }

    private boolean sha256(String value) {
        return value != null && value.matches("^[0-9a-f]{64}$");
    }

    private boolean gitCommit(String value) {
        return value != null && value.matches("^[0-9a-f]{40}$");
    }

    private static void reject() {
        throw rejected();
    }

    private static RemoteWorkerException rejected() {
        return new RemoteWorkerException(
                "Remote worker post-run source identity is stale, foreign, or ambiguous",
                409);
    }
}
