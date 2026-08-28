package com.atenea.service.worksession;

import com.atenea.api.worksession.AgentRunResponse;
import com.atenea.persistence.worksession.AgentRunEntity;
import com.atenea.persistence.worksession.AgentRunRecoveryNextAction;
import com.atenea.persistence.worksession.AgentRunRepository;
import com.atenea.persistence.worksession.AgentRunStatus;
import com.atenea.persistence.worksession.SessionTurnActor;
import com.atenea.persistence.worksession.SessionTurnEntity;
import com.atenea.persistence.worksession.SessionTurnRepository;
import com.atenea.persistence.worksession.SessionTurnAttachmentEntity;
import com.atenea.persistence.worksession.SessionTurnAttachmentRepository;
import com.atenea.persistence.worksession.WorkSessionEntity;
import com.atenea.persistence.worksession.WorkSessionRepository;
import com.atenea.persistence.worksession.ExecutionTarget;
import com.atenea.persistence.worksession.RemoteCloseState;
import com.atenea.persistence.worksession.WorkSessionStatus;
import com.atenea.persistence.worksession.WorkloadClass;
import com.atenea.persistence.developmentchange.DevelopmentChangeEntity;
import com.atenea.persistence.developmentchange.DevelopmentChangeRepository;
import com.atenea.persistence.developmentchange.DevelopmentChangeSourceState;
import com.atenea.persistence.developmentchange.DevelopmentChangeStatus;
import com.atenea.persistence.developmentchange.DevelopmentChangeWorkspaceOperationRepository;
import com.atenea.persistence.developmentchange.DevelopmentChangeWorkspaceOperationState;
import com.atenea.persistence.developmentchange.DevelopmentChangeWorkspaceState;
import com.atenea.mobilepush.MobilePushDispatchService;
import com.atenea.codexoperations.CodexExecutionProfileSnapshotService;
import com.atenea.remoteworker.BeautipsProjectCodexIdentity;
import com.atenea.remoteworker.ProjectCodexIdentity;
import com.atenea.remoteworker.RemoteRoutingSelector;
import com.atenea.remoteworker.ReviewedInstructionBundleIdentity;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgentRunService {

    private static final String INTERNAL_ORIGIN_TURN_MESSAGE = "Internal AgentRun origin";

    private final WorkSessionRepository workSessionRepository;
    private final AgentRunRepository agentRunRepository;
    private final SessionTurnRepository sessionTurnRepository;
    private final SessionTurnAttachmentRepository sessionTurnAttachmentRepository;
    private final TurnAttachmentSelectionValidator turnAttachmentSelectionValidator;
    private final AgentRunProgressService agentRunProgressService;
    private final MobilePushDispatchService mobilePushDispatchService;
    private final WorkSessionAcceptanceService workSessionAcceptanceService;
    private final CodexExecutionProfileSnapshotService codexExecutionProfileSnapshotService;
    private final DevelopmentChangeRepository developmentChangeRepository;
    private final DevelopmentChangeWorkspaceOperationRepository changeWorkspaceOperationRepository;
    private final RemoteRoutingSelector remoteRoutingSelector;
    private final JdbcTemplate jdbcTemplate;

    public AgentRunService(
            WorkSessionRepository workSessionRepository,
            AgentRunRepository agentRunRepository,
            SessionTurnRepository sessionTurnRepository,
            SessionTurnAttachmentRepository sessionTurnAttachmentRepository,
            TurnAttachmentSelectionValidator turnAttachmentSelectionValidator,
            AgentRunProgressService agentRunProgressService,
            MobilePushDispatchService mobilePushDispatchService,
            WorkSessionAcceptanceService workSessionAcceptanceService,
            CodexExecutionProfileSnapshotService codexExecutionProfileSnapshotService,
            DevelopmentChangeRepository developmentChangeRepository,
            DevelopmentChangeWorkspaceOperationRepository changeWorkspaceOperationRepository,
            RemoteRoutingSelector remoteRoutingSelector,
            JdbcTemplate jdbcTemplate
    ) {
        this.workSessionRepository = workSessionRepository;
        this.agentRunRepository = agentRunRepository;
        this.sessionTurnRepository = sessionTurnRepository;
        this.sessionTurnAttachmentRepository = sessionTurnAttachmentRepository;
        this.turnAttachmentSelectionValidator = turnAttachmentSelectionValidator;
        this.agentRunProgressService = agentRunProgressService;
        this.mobilePushDispatchService = mobilePushDispatchService;
        this.workSessionAcceptanceService = workSessionAcceptanceService;
        this.codexExecutionProfileSnapshotService = codexExecutionProfileSnapshotService;
        this.developmentChangeRepository = developmentChangeRepository;
        this.changeWorkspaceOperationRepository = changeWorkspaceOperationRepository;
        this.remoteRoutingSelector = remoteRoutingSelector;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public AgentRunEntity createRunningRun(Long sessionId) {
        WorkSessionEntity session = workSessionRepository.findWithProjectById(sessionId)
                .orElseThrow(() -> new WorkSessionNotFoundException(sessionId));
        Instant now = Instant.now();
        SessionTurnEntity originTurn = createInternalOriginTurn(session, now);
        return createRunningRun(session, originTurn, now);
    }

    @Transactional
    public AgentRunEntity createRunningRun(WorkSessionEntity session, SessionTurnEntity originTurn) {
        return createRunningRun(session, originTurn, Instant.now());
    }

    @Transactional
    public AgentRunEntity createRemoteQueuedRun(
            WorkSessionEntity session,
            SessionTurnEntity originTurn,
            WorkloadClass workloadClass
    ) {
        return createRemoteQueuedRun(session, originTurn, workloadClass, null, null);
    }

    @Transactional
    public AgentRunEntity createRemoteQueuedRun(
            WorkSessionEntity session,
            SessionTurnEntity originTurn,
            WorkloadClass workloadClass,
            TurnAttachmentSelectionValidator.ValidatedSelection attachmentSelection
    ) {
        if (attachmentSelection == null || attachmentSelection.attachments().isEmpty()) {
            throw new IllegalArgumentException(
                    "An image-bearing AgentRun requires a validated attachment selection");
        }
        return createRemoteQueuedRun(
                session,
                originTurn,
                workloadClass,
                null,
                attachmentSelection);
    }

    private AgentRunEntity createRemoteQueuedRun(
            WorkSessionEntity session,
            SessionTurnEntity originTurn,
            WorkloadClass workloadClass,
            AgentRunEntity retryOfRun,
            TurnAttachmentSelectionValidator.ValidatedSelection attachmentSelection
    ) {
        Instant now = Instant.now();
        lockCodexActivation(session.getSelectedWorkerId());
        ensureNoNonTerminalRun(session.getId());
        workSessionAcceptanceService.invalidateForNewRun(session);
        ChangeBinding changeBinding = changeBinding(session);
        requireCompatibleRetryBinding(retryOfRun, changeBinding);
        if (session.getRemoteSessionId() == null
                || (!"synthetic-routing-v1".equals(session.getRemoteWorkloadKind())
                    && !ProjectCodexIdentity.WORKLOAD_KIND.equals(session.getRemoteWorkloadKind()))
                || (ProjectCodexIdentity.WORKLOAD_KIND.equals(session.getRemoteWorkloadKind())
                    && !matchesProjectWorkload(session))) {
            throw new IllegalStateException("Remote WorkSession workload ownership is incomplete or incompatible");
        }

        AgentRunEntity run = new AgentRunEntity();
        run.setSession(session);
        run.setOriginTurn(originTurn);
        run.setResultTurn(null);
        run.setStatus(AgentRunStatus.QUEUED);
        run.setTargetRepoPath(session.getProject().getRepoPath());
        run.setExternalTurnId(null);
        run.setExecutionTarget(ExecutionTarget.REMOTE);
        run.setSelectedWorkerId(session.getSelectedWorkerId());
        run.setWorkspaceIdentity(session.getWorkspaceIdentity());
        run.setRemoteSessionId(session.getRemoteSessionId());
        run.setWorkloadKind(changeBinding == null
                ? attachmentSelection == null
                        ? session.getRemoteWorkloadKind()
                        : ProjectCodexIdentity.IMAGE_WORKLOAD_KIND
                : ProjectCodexIdentity.CHANGE_WORKLOAD_KIND);
        applyProjectIdentity(run);
        applyChangeBinding(run, changeBinding);
        if (attachmentSelection == null) {
            run.setAttachmentCount(0);
            run.setAttachmentBytes(0L);
            run.setAttachmentManifestSha256(null);
        } else {
            run.setAttachmentCount(attachmentSelection.attachments().size());
            run.setAttachmentBytes(attachmentSelection.totalBytes());
            run.setAttachmentManifestSha256(attachmentSelection.manifestSha256());
        }
        run.setDispatchId(UUID.randomUUID());
        run.setRemoteExecutionId(null);
        run.setWorkloadClass(workloadClass);
        run.setLeaseGeneration(1);
        run.setLeaseExpiresAt(now.plusSeconds(90));
        run.setLastHeartbeatAt(null);
        run.setLifecycleRevision(0);
        run.setQueuedAt(now);
        run.setStatusReason("Awaiting worker admission");
        run.setStartedAt(now);
        run.setFinishedAt(null);
        run.setOutputSummary(null);
        run.setErrorSummary(null);
        run.setCreatedAt(now);
        run.setRetryOfRun(retryOfRun);
        if (retryOfRun == null) {
            codexExecutionProfileSnapshotService.applyCurrentProfile(run);
        } else {
            run.setCodexModelId(retryOfRun.getCodexModelId());
            run.setCodexModelSource(retryOfRun.getCodexModelSource());
            run.setCodexReasoningEffort(retryOfRun.getCodexReasoningEffort());
            run.setCodexEffortSource(retryOfRun.getCodexEffortSource());
            run.setCodexCatalogRevision(retryOfRun.getCodexCatalogRevision());
            run.setCodexVersion(retryOfRun.getCodexVersion());
        }
        return agentRunRepository.save(run);
    }

    @Transactional
    public AgentRunEntity createRemoteRetryRun(Long sourceRunId) {
        AgentRunEntity source = agentRunRepository.findByIdForUpdate(sourceRunId)
                .orElseThrow(() -> new AgentRunNotFoundException(sourceRunId));
        if (source.getStatus() != AgentRunStatus.FAILED
                || source.getExecutionTarget() != ExecutionTarget.REMOTE) {
            throw new AgentRunRecoveryConflictException(
                    "Only an exact failed remote AgentRun may be retried");
        }
        requireRemoteRetryEligible(source);
        AgentRunEntity existing = agentRunRepository
                .findFirstByRetryOfRunIdOrderByCreatedAtAsc(sourceRunId)
                .orElse(null);
        if (existing != null) {
            return existing;
        }

        TurnAttachmentSelectionValidator.ValidatedSelection attachmentSelection =
                retryAttachmentSelection(source);
        return createRemoteQueuedRun(
                source.getSession(),
                source.getOriginTurn(),
                source.getWorkloadClass(),
                source,
                attachmentSelection);
    }

    public void requireRemoteRetryEligible(AgentRunEntity source) {
        if (source == null) {
            throw new AgentRunRecoveryConflictException("Retry source is required");
        }
        if (source.getFailureCode() == null && source.getRecoveryNextAction() == null) {
            return;
        }
        if (source.getFailureCode() != null
                && source.getRecoveryNextAction() == AgentRunRecoveryNextAction.RETRY) {
            return;
        }
        if ("CLOSED_SESSION_OWNS_CAPACITY".equals(source.getFailureCode())
                && source.getRecoveryNextAction()
                        == AgentRunRecoveryNextAction.RECONCILE_REMOTE_CLOSE
                && matchingBlockerHasReleasedReceipt(source)) {
            return;
        }
        throw new AgentRunRecoveryConflictException(
                "The deterministic AgentRun blocker has not been cleared");
    }

    @Transactional(readOnly = true)
    public boolean isRemoteRetryEligible(Long runId) {
        AgentRunEntity source = agentRunRepository.findById(runId).orElse(null);
        if (source == null || !source.getStatus().isTerminal()) {
            return false;
        }
        try {
            requireRemoteRetryEligible(source);
            return true;
        } catch (AgentRunRecoveryConflictException exception) {
            return false;
        }
    }

    private boolean matchingBlockerHasReleasedReceipt(AgentRunEntity source) {
        if (source.getRecoveryBlockerWorkSessionId() == null
                || source.getSession() == null
                || source.getSession().getProject() == null
                || source.getSelectedWorkerId() == null) {
            return false;
        }
        WorkSessionEntity blocker = workSessionRepository.findWithProjectById(
                source.getRecoveryBlockerWorkSessionId()).orElse(null);
        if (blocker == null
                || blocker.getProject() == null
                || !java.util.Objects.equals(
                        blocker.getProject().getId(), source.getSession().getProject().getId())
                || blocker.getStatus() != WorkSessionStatus.CLOSED
                || blocker.getExecutionTarget() != ExecutionTarget.REMOTE
                || blocker.getRemoteCloseState() != RemoteCloseState.RELEASED
                || blocker.getRemoteCloseOperationId() == null
                || blocker.getRemoteCloseReceiptSha256() == null
                || !blocker.getRemoteCloseReceiptSha256().matches("^[0-9a-f]{64}$")
                || blocker.getRemoteCloseReleasedAt() == null
                || !source.getSelectedWorkerId().equals(blocker.getSelectedWorkerId())
                || !ProjectCodexIdentity.hasCanonicalSourceObservation(blocker)) {
            return false;
        }
        String remoteId = blocker.getRemoteSessionId() == null
                ? null : blocker.getRemoteSessionId().toString();
        boolean exactOwnership = remoteId != null
                && !blocker.getRemoteSessionId().equals(source.getRemoteSessionId())
                && ("remote:" + blocker.getSelectedWorkerId()
                    + ":work-session:" + remoteId).equals(blocker.getWorkspaceIdentity());
        if (!exactOwnership) {
            return false;
        }
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                      FROM remote_close_legacy_operation
                     WHERE operation_id = ?
                       AND work_session_id = ?
                       AND state = 'RELEASED'
                       AND receipt_sha256 = ?
                       AND released_at IS NOT NULL)
                """, Boolean.class, blocker.getRemoteCloseOperationId(), blocker.getId(),
                blocker.getRemoteCloseReceiptSha256()));
    }

    private TurnAttachmentSelectionValidator.ValidatedSelection retryAttachmentSelection(
            AgentRunEntity source
    ) {
        if (!ProjectCodexIdentity.IMAGE_WORKLOAD_KIND.equals(source.getWorkloadKind())) {
            if (source.getAttachmentCount() != 0
                    || source.getAttachmentBytes() != 0
                    || source.getAttachmentManifestSha256() != null) {
                throw new AgentRunRecoveryConflictException(
                        "The failed AgentRun attachment snapshot is inconsistent");
            }
            return null;
        }
        if (source.getOriginTurn() == null
                || source.getAttachmentCount() < 1
                || source.getAttachmentCount() > 4
                || source.getAttachmentBytes() < 1
                || source.getAttachmentManifestSha256() == null) {
            throw new AgentRunRecoveryConflictException(
                    "The failed image AgentRun has no complete immutable attachment snapshot");
        }
        List<SessionTurnAttachmentEntity> bindings = sessionTurnAttachmentRepository
                .findByWorkSessionIdAndSessionTurnIdOrderByPositionAsc(
                        source.getSession().getId(),
                        source.getOriginTurn().getId());
        if (bindings.size() != source.getAttachmentCount()) {
            throw new AgentRunRecoveryConflictException(
                    "The failed image AgentRun binding count no longer matches its snapshot");
        }
        for (int index = 0; index < bindings.size(); index++) {
            if (bindings.get(index).getPosition() != index) {
                throw new AgentRunRecoveryConflictException(
                        "The failed image AgentRun binding order is incomplete");
            }
        }
        TurnAttachmentSelectionValidator.ValidatedSelection selection =
                turnAttachmentSelectionValidator.validateBoundRetry(
                        source.getSession(),
                        bindings.stream()
                                .map(SessionTurnAttachmentEntity::getAttachmentId)
                                .toList());
        if (selection.attachments().size() != source.getAttachmentCount()
                || selection.totalBytes() != source.getAttachmentBytes()
                || !source.getAttachmentManifestSha256().equals(selection.manifestSha256())) {
            throw new AgentRunRecoveryConflictException(
                    "The retained image manifest no longer matches the failed AgentRun snapshot");
        }
        return selection;
    }

    private void lockCodexActivation(String workerId) {
        if (workerId == null || workerId.isBlank()) {
            throw new IllegalStateException("Remote worker ownership is incomplete");
        }
        jdbcTemplate.update("""
                INSERT INTO worker_codex_activation_barrier (worker_id)
                VALUES (?) ON CONFLICT (worker_id) DO NOTHING
                """, workerId);
        jdbcTemplate.queryForObject("""
                SELECT worker_id FROM worker_codex_activation_barrier
                 WHERE worker_id = ? FOR UPDATE
                """, String.class, workerId);
    }

    @Transactional
    public AgentRunEntity markSucceeded(Long runId, String externalTurnId, String outputSummary) {
        return markSucceeded(runId, externalTurnId, outputSummary, null);
    }

    @Transactional
    public AgentRunEntity markSucceeded(
            Long runId,
            String externalTurnId,
            String outputSummary,
            SessionTurnEntity resultTurn
    ) {
        AgentRunEntity run = getRun(runId);
        ensureRunning(run, AgentRunStatus.SUCCEEDED);

        agentRunProgressService.applyExternalTurnId(run, externalTurnId);
        run.setStatus(AgentRunStatus.SUCCEEDED);
        run.setFinishedAt(Instant.now());
        run.setOutputSummary(outputSummary);
        run.setErrorSummary(null);
        run.setResultTurn(resultTurn);
        AgentRunEntity savedRun = agentRunRepository.save(run);
        mobilePushDispatchService.notifyRunSucceeded(savedRun);
        return savedRun;
    }

    @Transactional
    public AgentRunEntity markFailed(Long runId, String externalTurnId, String errorSummary) {
        AgentRunEntity run = getRun(runId);
        ensureRunning(run, AgentRunStatus.FAILED);

        agentRunProgressService.applyExternalTurnId(run, externalTurnId);
        run.setStatus(AgentRunStatus.FAILED);
        run.setFinishedAt(Instant.now());
        run.setOutputSummary(null);
        run.setErrorSummary(errorSummary);
        AgentRunEntity savedRun = agentRunRepository.save(run);
        mobilePushDispatchService.notifyRunFailed(savedRun);
        return savedRun;
    }

    @Transactional
    public boolean forceMarkFailedIfRunning(Long runId, String externalTurnId, String errorSummary) {
        String normalizedTurnId = normalizeNullableText(externalTurnId);
        String normalizedErrorSummary = normalizeNullableText(errorSummary);
        boolean changed = agentRunRepository.forceMarkFailedIfRunning(
                runId,
                normalizedTurnId,
                normalizedErrorSummary,
                Instant.now()) > 0;
        if (changed) {
            agentRunRepository.findWithSessionById(runId)
                    .ifPresent(mobilePushDispatchService::notifyRunFailed);
        }
        return changed;
    }

    @Transactional(readOnly = true)
    public List<AgentRunResponse> getRuns(Long sessionId) {
        if (!workSessionRepository.existsById(sessionId)) {
            throw new WorkSessionNotFoundException(sessionId);
        }

        return agentRunRepository.findBySessionIdOrderByCreatedAtAsc(sessionId)
                .stream()
                .map(this::toResponseInternal)
                .toList();
    }

    public AgentRunResponse toResponse(AgentRunEntity run) {
        return toResponseInternal(run);
    }

    private AgentRunEntity getRun(Long runId) {
        return agentRunRepository.findWithSessionById(runId)
                .orElseThrow(() -> new AgentRunNotFoundException(runId));
    }

    private String normalizeNullableText(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private void ensureRunning(AgentRunEntity run, AgentRunStatus targetStatus) {
        if (run.getStatus().isTerminal()) {
            throw new AgentRunTransitionNotAllowedException(run.getId(), run.getStatus(), targetStatus);
        }
    }

    private AgentRunEntity createRunningRun(WorkSessionEntity session, SessionTurnEntity originTurn, Instant now) {
        Long sessionId = session.getId();
        ensureNoNonTerminalRun(sessionId);
        workSessionAcceptanceService.invalidateForNewRun(session);

        AgentRunEntity run = new AgentRunEntity();
        run.setSession(session);
        run.setOriginTurn(originTurn);
        run.setResultTurn(null);
        run.setStatus(AgentRunStatus.RUNNING);
        run.setTargetRepoPath(session.getProject().getRepoPath());
        run.setExternalTurnId(null);
        run.setExecutionTarget(ExecutionTarget.LOCAL);
        run.setSelectedWorkerId(null);
        run.setWorkspaceIdentity(session.getWorkspaceIdentity());
        run.setRemoteSessionId(null);
        run.setWorkloadKind(null);
        clearProjectIdentity(run);
        run.setDispatchId(null);
        run.setRemoteExecutionId(null);
        run.setWorkloadClass(WorkloadClass.NORMAL);
        run.setLeaseGeneration(0);
        run.setLifecycleRevision(0);
        run.setStartedAt(now);
        run.setFinishedAt(null);
        run.setOutputSummary(null);
        run.setErrorSummary(null);
        run.setCreatedAt(now);

        return agentRunRepository.save(run);
    }

    private void applyProjectIdentity(AgentRunEntity run) {
        if (ProjectCodexIdentity.WORKLOAD_KIND.equals(run.getWorkloadKind())
                && BeautipsProjectCodexIdentity.matchesPinnedSession(run.getSession())) {
            run.setProjectIdentity(BeautipsProjectCodexIdentity.PROJECT_IDENTITY);
            run.setRepositoryUrl(BeautipsProjectCodexIdentity.REPOSITORY);
            run.setRepositoryBranch(BeautipsProjectCodexIdentity.BRANCH);
            run.setRepositoryCommit(BeautipsProjectCodexIdentity.COMMIT);
            run.setManifestSha256(BeautipsProjectCodexIdentity.MANIFEST_SHA256);
            ReviewedInstructionBundleIdentity.apply(
                    run, BeautipsProjectCodexIdentity.PROJECT_IDENTITY);
        } else if ((ProjectCodexIdentity.WORKLOAD_KIND.equals(run.getWorkloadKind())
                    || ProjectCodexIdentity.IMAGE_WORKLOAD_KIND.equals(run.getWorkloadKind())
                    || ProjectCodexIdentity.CHANGE_WORKLOAD_KIND.equals(run.getWorkloadKind()))
                && ProjectCodexIdentity.hasCanonicalSourceObservation(run.getSession())) {
            run.setProjectIdentity(ProjectCodexIdentity.PROJECT_IDENTITY);
            run.setRepositoryUrl(ProjectCodexIdentity.REPOSITORY);
            run.setRepositoryBranch(ProjectCodexIdentity.BRANCH);
            run.setRepositoryCommit(run.getSession().getCanonicalSourceCommit());
            run.setManifestSha256(ProjectCodexIdentity.MANIFEST_SHA256);
            ReviewedInstructionBundleIdentity.apply(
                    run, ProjectCodexIdentity.PROJECT_IDENTITY);
        } else {
            clearProjectIdentity(run);
        }
    }

    private boolean matchesProjectWorkload(WorkSessionEntity session) {
        return ProjectCodexIdentity.hasCanonicalSourceObservation(session)
                || BeautipsProjectCodexIdentity.matchesPinnedSession(session);
    }

    private ChangeBinding changeBinding(WorkSessionEntity session) {
        DevelopmentChangeEntity linked = session.getDevelopmentChange();
        if (linked == null) {
            return null;
        }
        if (linked.getChangeKey() == null) {
            throw invalidChangeBinding();
        }
        DevelopmentChangeEntity change = developmentChangeRepository
                .findByChangeKeyForUpdate(linked.getChangeKey())
                .orElseThrow(this::invalidChangeBinding);
        List<WorkSessionEntity> linkedSessions = workSessionRepository
                .findAllByDevelopmentChangeIdOrderByOpenedAtAscIdAsc(change.getId());
        boolean activeWorkspaceOperation = changeWorkspaceOperationRepository
                .existsByDevelopmentChangeIdAndStateIn(
                        change.getId(),
                        Set.of(
                                DevelopmentChangeWorkspaceOperationState.REQUESTED,
                                DevelopmentChangeWorkspaceOperationState.DISPATCHED));
        boolean workerAdmitted = remoteRoutingSelector.refreshKnownWorker(
                change.getSelectedWorkerId(), ProjectCodexIdentity.CHANGE_WORKLOAD_KIND);
        String expectedWorkspace = "remote:" + change.getSelectedWorkerId()
                + ":change:" + change.getChangeKey();
        String expectedWorkspaceBranch = "atenea/change-" + change.getChangeKey();
        if (session.getId() == null
                || session.getProject() == null
                || session.getProject().getId() == null
                || change.getId() == null
                || change.getProject() == null
                || !Objects.equals(change.getId(), linked.getId())
                || !Objects.equals(change.getProject().getId(), session.getProject().getId())
                || change.getStatus() != DevelopmentChangeStatus.OPEN
                || change.getWorkspaceState() != DevelopmentChangeWorkspaceState.READY
                || change.getSourceState() == DevelopmentChangeSourceState.STALE
                || change.getSourceState() == DevelopmentChangeSourceState.BLOCKED
                || change.getWorkspaceOperationRevision() < 1
                || change.getWorkspaceUpdatedAt() == null
                || activeWorkspaceOperation
                || session.getPublishedChangeKey() != null
                || !workerAdmitted
                || linkedSessions.size() != 1
                || !Objects.equals(linkedSessions.getFirst().getId(), session.getId())
                || session.getExecutionTarget() != ExecutionTarget.REMOTE
                || !Objects.equals(change.getSelectedWorkerId(), session.getSelectedWorkerId())
                || !Objects.equals(change.getWorkspaceIdentity(), expectedWorkspace)
                || !Objects.equals(session.getWorkspaceIdentity(), expectedWorkspace)
                || !Objects.equals(change.getWorkspaceBranch(), expectedWorkspaceBranch)
                || !Objects.equals(session.getWorkspaceBranch(), expectedWorkspaceBranch)
                || !Objects.equals(change.getBaseRef(), "refs/heads/" + ProjectCodexIdentity.BRANCH)
                || !Objects.equals(session.getCanonicalSourceRef(), change.getBaseRef())
                || !Objects.equals(session.getCanonicalSourceCommit(), change.getBaseCommit())
                || !ProjectCodexIdentity.WORKLOAD_KIND.equals(session.getRemoteWorkloadKind())
                || !gitCommit(change.getBaseCommit())
                || !gitCommit(change.getObservedCanonicalCommit())
                || change.getSourceRevision() < 0
                || !sha256(change.getSourceFingerprintSha256())
                || !sha256(change.getWorkspaceOwnershipFingerprintSha256())) {
            throw invalidChangeBinding();
        }
        return new ChangeBinding(
                change.getChangeKey(),
                change.getBaseCommit(),
                change.getObservedCanonicalCommit(),
                change.getSourceRevision(),
                change.getSourceFingerprintSha256(),
                change.getWorkspaceOwnershipFingerprintSha256());
    }

    private void applyChangeBinding(AgentRunEntity run, ChangeBinding binding) {
        if (binding == null) {
            run.setDevelopmentChangeKey(null);
            run.setChangeBaseCommit(null);
            run.setChangeExpectedCanonicalCommit(null);
            run.setChangeSourceRevision(null);
            run.setChangeSourceFingerprintSha256(null);
            run.setChangeWorkspaceOwnershipFingerprintSha256(null);
            return;
        }
        run.setDevelopmentChangeKey(binding.changeKey());
        run.setChangeBaseCommit(binding.baseCommit());
        run.setChangeExpectedCanonicalCommit(binding.expectedCanonicalCommit());
        run.setChangeSourceRevision(binding.sourceRevision());
        run.setChangeSourceFingerprintSha256(binding.sourceFingerprintSha256());
        run.setChangeWorkspaceOwnershipFingerprintSha256(
                binding.workspaceOwnershipFingerprintSha256());
        run.setRepositoryCommit(binding.expectedCanonicalCommit());
    }

    private void requireCompatibleRetryBinding(
            AgentRunEntity retryOfRun,
            ChangeBinding currentBinding
    ) {
        if (retryOfRun == null) {
            return;
        }
        boolean changeRetry = ProjectCodexIdentity.CHANGE_WORKLOAD_KIND.equals(
                retryOfRun.getWorkloadKind());
        if (changeRetry != (currentBinding != null)
                || (changeRetry
                    && (!Objects.equals(retryOfRun.getDevelopmentChangeKey(),
                            currentBinding.changeKey())
                        || !Objects.equals(retryOfRun.getChangeBaseCommit(),
                            currentBinding.baseCommit())
                        || !Objects.equals(retryOfRun.getChangeExpectedCanonicalCommit(),
                            currentBinding.expectedCanonicalCommit())
                        || !Objects.equals(retryOfRun.getChangeSourceRevision(),
                            currentBinding.sourceRevision())
                        || !Objects.equals(retryOfRun.getChangeSourceFingerprintSha256(),
                            currentBinding.sourceFingerprintSha256())
                        || !Objects.equals(
                            retryOfRun.getChangeWorkspaceOwnershipFingerprintSha256(),
                            currentBinding.workspaceOwnershipFingerprintSha256())))) {
            throw new AgentRunRecoveryConflictException(
                    "The change-bound AgentRun retry no longer matches durable ownership");
        }
    }

    private IllegalStateException invalidChangeBinding() {
        return new IllegalStateException(
                "DevelopmentChange AgentRun ownership is incomplete, stale, or incompatible");
    }

    private static boolean gitCommit(String value) {
        return value != null && value.matches("^[0-9a-f]{40}$");
    }

    private static boolean sha256(String value) {
        return value != null && value.matches("^[0-9a-f]{64}$");
    }

    private record ChangeBinding(
            UUID changeKey,
            String baseCommit,
            String expectedCanonicalCommit,
            long sourceRevision,
            String sourceFingerprintSha256,
            String workspaceOwnershipFingerprintSha256) {
    }

    private void clearProjectIdentity(AgentRunEntity run) {
        run.setProjectIdentity(null);
        run.setRepositoryUrl(null);
        run.setRepositoryBranch(null);
        run.setRepositoryCommit(null);
        run.setManifestSha256(null);
        run.setInstructionBundleRevision(null);
        run.setInstructionBundleSha256(null);
        run.setPlatformInstructionSha256(null);
        run.setProjectInstructionPath(null);
        run.setProjectInstructionSha256(null);
        run.setWorkerMirrorCommit(null);
    }

    private void ensureNoNonTerminalRun(Long sessionId) {
        if (hasNonTerminalRun(sessionId)) {
            throw new AgentRunAlreadyRunningException(sessionId);
        }
    }

    private boolean hasNonTerminalRun(Long sessionId) {
        if (agentRunRepository.existsBySessionIdAndStatus(sessionId, AgentRunStatus.RUNNING)) {
            return true;
        }
        return agentRunRepository.existsBySessionIdAndStatusIn(sessionId, AgentRunStatus.nonTerminalStatuses());
    }

    private SessionTurnEntity createInternalOriginTurn(WorkSessionEntity session, Instant now) {
        SessionTurnEntity turn = new SessionTurnEntity();
        turn.setSession(session);
        turn.setActor(SessionTurnActor.ATENEA);
        turn.setMessageText(INTERNAL_ORIGIN_TURN_MESSAGE);
        turn.setInternal(true);
        turn.setCreatedAt(now);
        return sessionTurnRepository.save(turn);
    }

    private AgentRunResponse toResponseInternal(AgentRunEntity run) {
        return new AgentRunResponse(
                run.getId(),
                run.getSession().getId(),
                run.getOriginTurn() == null ? null : run.getOriginTurn().getId(),
                run.getResultTurn() == null ? null : run.getResultTurn().getId(),
                run.getStatus(),
                run.getTargetRepoPath(),
                run.getExternalTurnId(),
                run.getStartedAt(),
                run.getFinishedAt(),
                run.getOutputSummary(),
                run.getErrorSummary(),
                run.getCreatedAt(),
                run.getExecutionTarget(),
                run.getSelectedWorkerId(),
                run.getWorkspaceIdentity(),
                run.getDispatchId(),
                run.getRemoteExecutionId(),
                run.getWorkloadClass(),
                run.getLeaseGeneration(),
                run.getLeaseExpiresAt(),
                run.getLastHeartbeatAt(),
                run.getLifecycleRevision(),
                run.getStatusReason(),
                run.getProcessOutcome(),
                run.getFailureCode(),
                run.getRecoveryNextAction()
        );
    }
}
