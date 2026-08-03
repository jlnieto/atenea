package com.atenea.service.worksession;

import com.atenea.api.worksession.RecoverDraftWorkSessionResponse;
import com.atenea.persistence.worksession.AgentRunRepository;
import com.atenea.persistence.worksession.AgentRunStatus;
import com.atenea.persistence.worksession.ExecutionTarget;
import com.atenea.persistence.worksession.WorkSessionEntity;
import com.atenea.persistence.worksession.WorkSessionPullRequestStatus;
import com.atenea.persistence.worksession.WorkSessionRepository;
import com.atenea.persistence.worksession.WorkSessionStatus;
import com.atenea.remoteworker.CanonicalSourceAdmissionService;
import com.atenea.remoteworker.ProjectCodexIdentity;
import com.atenea.remoteworker.RemoteRoutingSelector;
import com.atenea.remoteworker.RemoteWorkerClient;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RetainedDraftRecoveryService {

    private final WorkSessionRepository workSessionRepository;
    private final AgentRunRepository agentRunRepository;
    private final CanonicalSourceAdmissionService canonicalSourceAdmissionService;
    private final RemoteWorkerClient remoteWorkerClient;
    private final RemoteRoutingSelector remoteRoutingSelector;
    private final SessionBranchService sessionBranchService;

    public RetainedDraftRecoveryService(
            WorkSessionRepository workSessionRepository,
            AgentRunRepository agentRunRepository,
            CanonicalSourceAdmissionService canonicalSourceAdmissionService,
            RemoteWorkerClient remoteWorkerClient,
            RemoteRoutingSelector remoteRoutingSelector,
            SessionBranchService sessionBranchService
    ) {
        this.workSessionRepository = workSessionRepository;
        this.agentRunRepository = agentRunRepository;
        this.canonicalSourceAdmissionService = canonicalSourceAdmissionService;
        this.remoteWorkerClient = remoteWorkerClient;
        this.remoteRoutingSelector = remoteRoutingSelector;
        this.sessionBranchService = sessionBranchService;
    }

    @Transactional
    public RecoverDraftWorkSessionResponse recover(Long sessionId) {
        return recover(sessionId, null, null, null);
    }

    @Transactional
    public RecoverDraftWorkSessionResponse recoverExact(
            Long sessionId,
            UUID expectedRemoteSessionId,
            String expectedRetainedHead,
            String expectedAcceptedCommit
    ) {
        if (expectedRemoteSessionId == null
                || expectedRetainedHead == null
                || expectedAcceptedCommit == null) {
            throw blocked("Exact retained draft recovery authority is incomplete");
        }
        return recover(sessionId, expectedRemoteSessionId, expectedRetainedHead, expectedAcceptedCommit);
    }

    private RecoverDraftWorkSessionResponse recover(
            Long sessionId,
            UUID expectedRemoteSessionId,
            String expectedRetainedHead,
            String expectedAcceptedCommit
    ) {
        WorkSessionEntity retained = workSessionRepository.findLockedWithProjectById(sessionId)
                .orElseThrow(() -> new WorkSessionNotFoundException(sessionId));
        if (expectedRemoteSessionId != null
                && !expectedRemoteSessionId.equals(retained.getRemoteSessionId())) {
            throw blocked("The retained WorkSession remote identity diverged");
        }
        if (retained.getStatus() == WorkSessionStatus.DRAFT_BLOCKED) {
            RecoverDraftWorkSessionResponse persisted = persistedResponse(retained);
            validateExactResult(persisted, expectedRetainedHead, expectedAcceptedCommit);
            return persisted;
        }
        if (retained.getStatus() != WorkSessionStatus.OPEN
                || retained.getExecutionTarget() != ExecutionTarget.REMOTE
                || !ProjectCodexIdentity.matches(retained)
                || retained.getRemoteSessionId() == null
                || agentRunRepository.existsBySessionIdAndStatusIn(
                        sessionId,
                        AgentRunStatus.nonTerminalStatuses())) {
            throw blocked("The WorkSession is not an idle, exactly owned remote Atenea draft");
        }

        canonicalSourceAdmissionService.admitBeforeWrite(retained);
        RemoteWorkerClient.DraftFingerprint fingerprint =
                remoteWorkerClient.fingerprintRetainedDraft(retained);
        validateFingerprint(retained, fingerprint);
        if (expectedRetainedHead != null
                && (!expectedRetainedHead.equals(fingerprint.retainedHead())
                || !expectedAcceptedCommit.equals(fingerprint.acceptedCommit()))) {
            throw blocked("The retained draft fingerprint diverged from exact recovery authority");
        }

        Instant now = Instant.now();
        retained.setStatus(WorkSessionStatus.DRAFT_BLOCKED);
        retained.setDraftFingerprintSha256(fingerprint.fingerprintSha256());
        retained.setDraftRetainedHead(fingerprint.retainedHead());
        retained.setDraftStagedChangeCount(fingerprint.stagedChangeCount());
        retained.setDraftUnstagedChangeCount(fingerprint.unstagedChangeCount());
        retained.setDraftUntrackedChangeCount(fingerprint.untrackedChangeCount());
        retained.setDraftBlockedAt(now);
        retained.setReplacementWorkSessionId(null);
        retained.setLastActivityAt(now);
        retained.setUpdatedAt(now);
        workSessionRepository.saveAndFlush(retained);

        WorkSessionEntity replacement = newReplacement(retained, now);
        replacement = workSessionRepository.save(replacement);
        remoteRoutingSelector.pinNewSession(replacement);
        if (replacement.getExecutionTarget() != ExecutionTarget.REMOTE
                || replacement.getRemoteSessionId() == null
                || !ProjectCodexIdentity.matches(replacement)) {
            throw blocked("A new exactly routed remote Atenea WorkSession could not be created");
        }
        replacement.setWorkspaceBranch(sessionBranchService.prepareWorkspaceBranch(
                replacement,
                retained.getProject().getRepoPath()));
        replacement.setCanonicalSourceRef(retained.getCanonicalSourceRef());
        replacement.setCanonicalSourceCommit(retained.getCanonicalSourceCommit());
        replacement.setCanonicalSourceObservationSha256(retained.getCanonicalSourceObservationSha256());
        replacement.setCanonicalSourceObservedAt(retained.getCanonicalSourceObservedAt());
        replacement.setUpdatedAt(now);
        replacement = workSessionRepository.save(replacement);

        retained.setReplacementWorkSessionId(replacement.getId());
        retained.setUpdatedAt(now);
        workSessionRepository.save(retained);

        RecoverDraftWorkSessionResponse result = response(retained, fingerprint.acceptedCommit());
        validateExactResult(result, expectedRetainedHead, expectedAcceptedCommit);
        return result;
    }

    private void validateExactResult(
            RecoverDraftWorkSessionResponse result,
            String expectedRetainedHead,
            String expectedAcceptedCommit
    ) {
        if (expectedRetainedHead != null
                && (!expectedRetainedHead.equals(result.retainedHead())
                || !expectedAcceptedCommit.equals(result.acceptedCommit())
                || result.valuesExposed())) {
            throw blocked("The sanitized recovery result diverged from exact recovery authority");
        }
    }

    private WorkSessionEntity newReplacement(WorkSessionEntity retained, Instant now) {
        WorkSessionEntity replacement = new WorkSessionEntity();
        replacement.setProject(retained.getProject());
        replacement.setStatus(WorkSessionStatus.OPEN);
        replacement.setTitle(replacementTitle(retained.getTitle()));
        replacement.setBaseBranch(ProjectCodexIdentity.BRANCH);
        replacement.setWorkspaceBranch(null);
        replacement.setExternalThreadId(null);
        replacement.setExecutionTarget(ExecutionTarget.LOCAL);
        replacement.setSelectedWorkerId(null);
        replacement.setWorkspaceIdentity("local:pending");
        replacement.setRemoteSessionId(null);
        replacement.setRemoteWorkloadKind(null);
        replacement.setPullRequestUrl(null);
        replacement.setPullRequestStatus(WorkSessionPullRequestStatus.NOT_CREATED);
        replacement.setFinalCommitSha(null);
        replacement.setOpenedAt(now);
        replacement.setLastActivityAt(now);
        replacement.setPublishedAt(null);
        replacement.setCloseBlockedState(null);
        replacement.setCloseBlockedReason(null);
        replacement.setCloseBlockedAction(null);
        replacement.setCloseRetryable(false);
        replacement.setClosedAt(null);
        replacement.setCreatedAt(now);
        replacement.setUpdatedAt(now);
        return replacement;
    }

    private void validateFingerprint(
            WorkSessionEntity session,
            RemoteWorkerClient.DraftFingerprint fingerprint
    ) {
        int changes = fingerprint.stagedChangeCount()
                + fingerprint.unstagedChangeCount()
                + fingerprint.untrackedChangeCount();
        if (!"draft_blocked_ready".equals(fingerprint.state())
                || !session.getRemoteSessionId().toString().equals(fingerprint.sessionId())
                || !session.getWorkspaceIdentity().equals(fingerprint.workspaceIdentity())
                || !ProjectCodexIdentity.PROJECT_IDENTITY.equals(fingerprint.projectId())
                || !session.getCanonicalSourceCommit().equals(fingerprint.acceptedCommit())
                || fingerprint.retainedHead() == null
                || !fingerprint.retainedHead().matches("^[0-9a-f]{40}$")
                || fingerprint.fingerprintSha256() == null
                || !fingerprint.fingerprintSha256().matches("^[0-9a-f]{64}$")
                || changes <= 0
                || fingerprint.valuesExposed()) {
            throw blocked("The worker did not prove a retained, sanitized stale draft");
        }
    }

    private RecoverDraftWorkSessionResponse persistedResponse(WorkSessionEntity retained) {
        if (retained.getReplacementWorkSessionId() == null
                || retained.getDraftStagedChangeCount() == null
                || retained.getDraftUnstagedChangeCount() == null
                || retained.getDraftUntrackedChangeCount() == null) {
            throw blocked("The retained draft recovery record is incomplete");
        }
        return response(retained, retained.getCanonicalSourceCommit());
    }

    private RecoverDraftWorkSessionResponse response(WorkSessionEntity retained, String acceptedCommit) {
        return new RecoverDraftWorkSessionResponse(
                retained.getId(),
                retained.getReplacementWorkSessionId(),
                retained.getDraftRetainedHead(),
                acceptedCommit,
                retained.getDraftFingerprintSha256(),
                retained.getDraftStagedChangeCount(),
                retained.getDraftUnstagedChangeCount(),
                retained.getDraftUntrackedChangeCount(),
                false);
    }

    private String replacementTitle(String title) {
        String value = title + " (current)";
        return value.length() <= 200 ? value : value.substring(0, 200);
    }

    private WorkSessionOperationBlockedException blocked(String detail) {
        return new WorkSessionOperationBlockedException(
                detail + "; the retained draft was not modified");
    }
}
