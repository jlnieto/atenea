package com.atenea.service.worksession;

import com.atenea.api.worksession.AgentRunResponse;
import com.atenea.persistence.worksession.AgentRunEntity;
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
import com.atenea.persistence.worksession.WorkloadClass;
import com.atenea.mobilepush.MobilePushDispatchService;
import com.atenea.codexoperations.CodexExecutionProfileSnapshotService;
import com.atenea.remoteworker.BeautipsProjectCodexIdentity;
import com.atenea.remoteworker.ProjectCodexIdentity;
import com.atenea.remoteworker.ReviewedInstructionBundleIdentity;
import java.time.Instant;
import java.util.List;
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
        run.setWorkloadKind(attachmentSelection == null
                ? session.getRemoteWorkloadKind()
                : ProjectCodexIdentity.IMAGE_WORKLOAD_KIND);
        applyProjectIdentity(run);
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
                    || ProjectCodexIdentity.IMAGE_WORKLOAD_KIND.equals(run.getWorkloadKind()))
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
                run.getProcessOutcome()
        );
    }
}
