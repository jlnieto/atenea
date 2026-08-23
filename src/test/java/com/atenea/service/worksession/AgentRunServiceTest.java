package com.atenea.service.worksession;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.atenea.api.worksession.AgentRunResponse;
import com.atenea.codexoperations.CodexExecutionProfileSnapshotService;
import com.atenea.mobilepush.MobilePushDispatchService;
import com.atenea.persistence.project.ProjectEntity;
import com.atenea.persistence.worksession.AgentRunEntity;
import com.atenea.persistence.worksession.AgentRunRepository;
import com.atenea.persistence.worksession.AgentRunProcessOutcome;
import com.atenea.persistence.worksession.AgentRunRecoveryNextAction;
import com.atenea.persistence.worksession.AgentRunStatus;
import com.atenea.persistence.worksession.CodexReasoningEffort;
import com.atenea.persistence.worksession.SessionTurnActor;
import com.atenea.persistence.worksession.SessionTurnEntity;
import com.atenea.persistence.worksession.SessionTurnRepository;
import com.atenea.persistence.worksession.SessionTurnAttachmentRepository;
import com.atenea.persistence.worksession.WorkSessionEntity;
import com.atenea.persistence.worksession.WorkSessionRepository;
import com.atenea.persistence.worksession.ExecutionTarget;
import com.atenea.persistence.worksession.ExecutionProfileSource;
import com.atenea.persistence.worksession.RemoteCloseState;
import com.atenea.persistence.worksession.SessionTurnAttachmentEntity;
import com.atenea.persistence.worksession.WorkSessionStatus;
import com.atenea.persistence.worksession.WorkloadClass;
import com.atenea.remoteworker.BeautipsProjectCodexIdentity;
import com.atenea.remoteworker.ProjectCodexIdentity;
import com.atenea.remoteworker.ReviewedInstructionBundleIdentity;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AgentRunServiceTest {
    private static final String TEST_CANONICAL_COMMIT = "1".repeat(40);

    @Mock
    private WorkSessionRepository workSessionRepository;

    @Mock
    private AgentRunRepository agentRunRepository;

    @Mock
    private SessionTurnRepository sessionTurnRepository;

    @Mock
    private SessionTurnAttachmentRepository sessionTurnAttachmentRepository;

    @Mock
    private TurnAttachmentSelectionValidator turnAttachmentSelectionValidator;

    @Mock
    private com.atenea.codexappserver.CodexAppServerProperties codexAppServerProperties;

    @Mock
    private MobilePushDispatchService mobilePushDispatchService;

    @Mock
    private WorkSessionAcceptanceService workSessionAcceptanceService;

    @Mock
    private CodexExecutionProfileSnapshotService codexExecutionProfileSnapshotService;

    @Mock
    private JdbcTemplate jdbcTemplate;

    private AgentRunService agentRunService;
    private AgentRunReconciliationService agentRunReconciliationService;

    @BeforeEach
    void setUp() {
        agentRunReconciliationService = new AgentRunReconciliationService(
                agentRunRepository, codexAppServerProperties, mobilePushDispatchService);
        agentRunService = new AgentRunService(
                workSessionRepository,
                agentRunRepository,
                sessionTurnRepository,
                sessionTurnAttachmentRepository,
                turnAttachmentSelectionValidator,
                new AgentRunProgressService(),
                mobilePushDispatchService,
                workSessionAcceptanceService,
                codexExecutionProfileSnapshotService,
                jdbcTemplate
        );
    }

    @Test
    void createRunningRunCreatesRunForExistingSession() {
        WorkSessionEntity session = buildSession(12L, 7L, "/workspace/repos/internal/atenea");

        when(workSessionRepository.findWithProjectById(12L)).thenReturn(Optional.of(session));
        when(agentRunRepository.existsBySessionIdAndStatus(12L, AgentRunStatus.RUNNING)).thenReturn(false);
        when(sessionTurnRepository.save(any(SessionTurnEntity.class))).thenAnswer(invocation -> {
            SessionTurnEntity turn = invocation.getArgument(0);
            turn.setId(101L);
            return turn;
        });
        when(agentRunRepository.save(any(AgentRunEntity.class))).thenAnswer(invocation -> {
            AgentRunEntity run = invocation.getArgument(0);
            run.setId(55L);
            return run;
        });

        AgentRunEntity run = agentRunService.createRunningRun(12L);

        assertEquals(55L, run.getId());
        assertEquals(AgentRunStatus.RUNNING, run.getStatus());
        assertNull(run.getProcessOutcome());
        assertEquals("/workspace/repos/internal/atenea", run.getTargetRepoPath());
        assertNull(run.getExternalTurnId());
        assertNotNull(run.getStartedAt());
        assertNull(run.getFinishedAt());
        assertEquals(101L, run.getOriginTurn().getId());

        ArgumentCaptor<SessionTurnEntity> turnCaptor = ArgumentCaptor.forClass(SessionTurnEntity.class);
        verify(sessionTurnRepository).save(turnCaptor.capture());
        assertEquals(SessionTurnActor.ATENEA, turnCaptor.getValue().getActor());
        assertEquals("Internal AgentRun origin", turnCaptor.getValue().getMessageText());
        assertEquals(true, turnCaptor.getValue().isInternal());
    }

    @Test
    void markSucceededTransitionsRunningRunAndStoresExternalTurnId() {
        AgentRunEntity run = buildRun(55L, AgentRunStatus.RUNNING);

        when(agentRunRepository.findWithSessionById(55L)).thenReturn(Optional.of(run));
        when(agentRunRepository.save(any(AgentRunEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AgentRunEntity updated = agentRunService.markSucceeded(55L, " turn_123 ", "Completed successfully");

        assertEquals(AgentRunStatus.SUCCEEDED, updated.getStatus());
        assertEquals(AgentRunProcessOutcome.SUCCEEDED, updated.getProcessOutcome());
        assertEquals("turn_123", updated.getExternalTurnId());
        assertEquals("Completed successfully", updated.getOutputSummary());
        assertNull(updated.getErrorSummary());
        assertNotNull(updated.getFinishedAt());
        verify(mobilePushDispatchService).notifyRunSucceeded(updated);
    }

    @Test
    void markFailedTransitionsRunningRunAndStoresErrorSummary() {
        AgentRunEntity run = buildRun(55L, AgentRunStatus.RUNNING);

        when(agentRunRepository.findWithSessionById(55L)).thenReturn(Optional.of(run));
        when(agentRunRepository.save(any(AgentRunEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AgentRunEntity updated = agentRunService.markFailed(55L, "turn_456", "Codex execution failed");

        assertEquals(AgentRunStatus.FAILED, updated.getStatus());
        assertEquals(AgentRunProcessOutcome.FAILED, updated.getProcessOutcome());
        assertEquals("turn_456", updated.getExternalTurnId());
        assertEquals("Codex execution failed", updated.getErrorSummary());
        assertNull(updated.getOutputSummary());
        assertNotNull(updated.getFinishedAt());
        verify(mobilePushDispatchService).notifyRunFailed(updated);
    }

    @Test
    void responseProjectsActionSpecificFailureWithoutChangingLegacySummary() {
        AgentRunEntity run = buildRun(55L, AgentRunStatus.FAILED);
        run.setProcessOutcome(AgentRunProcessOutcome.FAILED);
        run.setErrorSummary("Remote close requires operator reconciliation");
        run.setFailureCode("REMOTE_CLOSE_RELEASE_UNCONFIRMED");
        run.setRecoveryNextAction(AgentRunRecoveryNextAction.REQUEST_RECONCILIATION);

        AgentRunResponse response = agentRunService.toResponse(run);

        assertEquals("Remote close requires operator reconciliation", response.errorSummary());
        assertEquals("REMOTE_CLOSE_RELEASE_UNCONFIRMED", response.failureCode());
        assertEquals(AgentRunRecoveryNextAction.REQUEST_RECONCILIATION, response.recoveryNextAction());
    }

    @Test
    void forceMarkFailedIfRunningUsesConditionalRepositoryUpdate() {
        AgentRunEntity run = buildRun(55L, AgentRunStatus.FAILED);
        when(agentRunRepository.forceMarkFailedIfRunning(eq(55L), eq("turn_456"), eq("Codex execution failed"), any()))
                .thenReturn(1);
        when(agentRunRepository.findWithSessionById(55L)).thenReturn(Optional.of(run));

        boolean updated = agentRunService.forceMarkFailedIfRunning(55L, " turn_456 ", " Codex execution failed ");

        assertTrue(updated);
        verify(mobilePushDispatchService).notifyRunFailed(run);
    }

    @Test
    void createRunningRunFailsWhenSessionAlreadyHasRunningRun() {
        WorkSessionEntity session = buildSession(12L, 7L, "/workspace/repos/internal/atenea");

        when(workSessionRepository.findWithProjectById(12L)).thenReturn(Optional.of(session));
        when(agentRunRepository.existsBySessionIdAndStatus(12L, AgentRunStatus.RUNNING)).thenReturn(true);

        assertThrows(AgentRunAlreadyRunningException.class, () -> agentRunService.createRunningRun(12L));
    }

    @Test
    void createRemoteQueuedRunPersistsAffinityAndDispatchBeforeExecution() {
        WorkSessionEntity session = buildSession(12L, 7L, "/workspace/repos/internal/atenea");
        session.setExecutionTarget(ExecutionTarget.REMOTE);
        session.setSelectedWorkerId("ax42-01");
        UUID remoteSessionId = UUID.fromString("a1c3af50-af6e-4cc2-85d6-a491c50cddcc");
        session.setRemoteSessionId(remoteSessionId);
        session.setRemoteWorkloadKind("synthetic-routing-v1");
        session.setWorkspaceIdentity("remote:ax42-01:work-session:" + remoteSessionId);
        SessionTurnEntity originTurn = new SessionTurnEntity();
        originTurn.setId(101L);
        originTurn.setSession(session);
        originTurn.setActor(SessionTurnActor.OPERATOR);
        originTurn.setMessageText("synthetic turn");
        originTurn.setCreatedAt(Instant.now());
        when(agentRunRepository.existsBySessionIdAndStatus(12L, AgentRunStatus.RUNNING)).thenReturn(false);
        when(agentRunRepository.existsBySessionIdAndStatusIn(
                12L,
                AgentRunStatus.nonTerminalStatuses())).thenReturn(false);
        when(agentRunRepository.save(any(AgentRunEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AgentRunEntity run = agentRunService.createRemoteQueuedRun(session, originTurn, WorkloadClass.HEAVY);

        verify(codexExecutionProfileSnapshotService).applyCurrentProfile(run);

        assertEquals(AgentRunStatus.QUEUED, run.getStatus());
        assertEquals(ExecutionTarget.REMOTE, run.getExecutionTarget());
        assertEquals("ax42-01", run.getSelectedWorkerId());
        assertEquals("remote:ax42-01:work-session:" + remoteSessionId, run.getWorkspaceIdentity());
        assertEquals(remoteSessionId, run.getRemoteSessionId());
        assertEquals("synthetic-routing-v1", run.getWorkloadKind());
        assertNotNull(run.getDispatchId());
        assertNull(run.getRemoteExecutionId());
        assertEquals(1, run.getLeaseGeneration());
        assertEquals(WorkloadClass.HEAVY, run.getWorkloadClass());
    }

    @Test
    void createRemoteRetryPersistsImmutableLineageBeforeFirstSave() {
        WorkSessionEntity session = buildSession(12L, 7L, "/workspace/repos/internal/atenea");
        session.setBaseBranch(ProjectCodexIdentity.BRANCH);
        session.setExecutionTarget(ExecutionTarget.REMOTE);
        session.setSelectedWorkerId("ax42-01");
        UUID remoteSessionId = UUID.fromString("a1c3af50-af6e-4cc2-85d6-a491c50cddcc");
        session.setRemoteSessionId(remoteSessionId);
        session.setRemoteWorkloadKind(ProjectCodexIdentity.WORKLOAD_KIND);
        session.setCanonicalSourceRef("refs/heads/" + ProjectCodexIdentity.BRANCH);
        session.setCanonicalSourceCommit(TEST_CANONICAL_COMMIT);
        session.setCanonicalSourceObservationSha256("2".repeat(64));
        session.setCanonicalSourceObservedAt(Instant.now());
        session.setWorkspaceIdentity("remote:ax42-01:work-session:" + remoteSessionId);
        AgentRunEntity source = buildRun(81L, AgentRunStatus.FAILED);
        source.setSession(session);
        source.setExecutionTarget(ExecutionTarget.REMOTE);
        source.setWorkloadClass(WorkloadClass.NORMAL);
        source.setCodexModelId("gpt-5.6-sol");
        source.setCodexReasoningEffort(CodexReasoningEffort.HIGH);
        source.setFailureCode("DETERMINISTIC_BLOCKER_CLEARED");
        source.setRecoveryNextAction(AgentRunRecoveryNextAction.RETRY);
        when(agentRunRepository.findByIdForUpdate(81L)).thenReturn(Optional.of(source));
        when(agentRunRepository.findFirstByRetryOfRunIdOrderByCreatedAtAsc(81L))
                .thenReturn(Optional.empty());
        when(agentRunRepository.existsBySessionIdAndStatusIn(
                12L, AgentRunStatus.nonTerminalStatuses())).thenReturn(false);
        when(agentRunRepository.save(any(AgentRunEntity.class))).thenAnswer(invocation -> {
            AgentRunEntity saved = invocation.getArgument(0);
            assertEquals(source, saved.getRetryOfRun());
            saved.setId(82L);
            return saved;
        });

        AgentRunEntity retry = agentRunService.createRemoteRetryRun(81L);

        assertEquals(82L, retry.getId());
        assertEquals(source, retry.getRetryOfRun());
        assertEquals("gpt-5.6-sol", retry.getCodexModelId());
        assertEquals(CodexReasoningEffort.HIGH, retry.getCodexReasoningEffort());
        assertEquals("DETERMINISTIC_BLOCKER_CLEARED", source.getFailureCode());
        assertEquals(AgentRunRecoveryNextAction.RETRY, source.getRecoveryNextAction());
        assertEquals(AgentRunStatus.FAILED, source.getStatus());
        verify(agentRunRepository).save(any(AgentRunEntity.class));
        verify(codexExecutionProfileSnapshotService, never()).applyCurrentProfile(retry);
    }

    @Test
    void createRemoteImageRetryReusesExactTurnManifestAndProfileWithoutRebinding() {
        WorkSessionEntity session = buildSession(12L, 7L, ProjectCodexIdentity.REPO_PATH);
        session.setBaseBranch(ProjectCodexIdentity.BRANCH);
        session.setExecutionTarget(ExecutionTarget.REMOTE);
        session.setSelectedWorkerId("ax42-01");
        UUID remoteSessionId = UUID.fromString("a1c3af50-af6e-4cc2-85d6-a491c50cddcc");
        session.setRemoteSessionId(remoteSessionId);
        session.setRemoteWorkloadKind(ProjectCodexIdentity.WORKLOAD_KIND);
        session.setCanonicalSourceRef("refs/heads/" + ProjectCodexIdentity.BRANCH);
        session.setCanonicalSourceCommit(TEST_CANONICAL_COMMIT);
        session.setCanonicalSourceObservationSha256("2".repeat(64));
        session.setCanonicalSourceObservedAt(Instant.now());
        session.setWorkspaceIdentity("remote:ax42-01:work-session:" + remoteSessionId);
        SessionTurnEntity originTurn = new SessionTurnEntity();
        originTurn.setId(101L);
        originTurn.setSession(session);
        UUID firstId = UUID.fromString("4e8f351e-e05a-41b6-99e5-3eb72d770002");
        UUID secondId = UUID.fromString("9aa2c7e5-1fd9-48ec-aa10-03dfdfb8ca7d");
        TurnAttachmentSelectionValidator.ValidatedSelection selection =
                new TurnAttachmentSelectionValidator.ValidatedSelection(
                        List.of(
                                new TurnAttachmentSelectionValidator.ValidatedAttachment(
                                        firstId, "image/png", 1024L, "a".repeat(64)),
                                new TurnAttachmentSelectionValidator.ValidatedAttachment(
                                        secondId, "image/webp", 2048L, "b".repeat(64))),
                        3072L,
                        "c".repeat(64));
        AgentRunEntity source = buildRun(81L, AgentRunStatus.FAILED);
        source.setSession(session);
        source.setOriginTurn(originTurn);
        source.setExecutionTarget(ExecutionTarget.REMOTE);
        source.setWorkloadKind(ProjectCodexIdentity.IMAGE_WORKLOAD_KIND);
        source.setWorkloadClass(WorkloadClass.NORMAL);
        source.setAttachmentCount(2);
        source.setAttachmentBytes(3072L);
        source.setAttachmentManifestSha256("c".repeat(64));
        source.setCodexModelId("gpt-5.6-sol");
        source.setCodexModelSource(ExecutionProfileSource.WORK_SESSION);
        source.setCodexReasoningEffort(CodexReasoningEffort.HIGH);
        source.setCodexEffortSource(ExecutionProfileSource.NEXT_TURN);
        source.setCodexCatalogRevision("d".repeat(64));
        source.setCodexVersion("0.145.0");
        SessionTurnAttachmentEntity firstBinding = mock(SessionTurnAttachmentEntity.class);
        SessionTurnAttachmentEntity secondBinding = mock(SessionTurnAttachmentEntity.class);
        when(firstBinding.getPosition()).thenReturn((short) 0);
        when(firstBinding.getAttachmentId()).thenReturn(firstId);
        when(secondBinding.getPosition()).thenReturn((short) 1);
        when(secondBinding.getAttachmentId()).thenReturn(secondId);
        when(agentRunRepository.findByIdForUpdate(81L)).thenReturn(Optional.of(source));
        when(agentRunRepository.findFirstByRetryOfRunIdOrderByCreatedAtAsc(81L))
                .thenReturn(Optional.empty());
        when(sessionTurnAttachmentRepository
                .findByWorkSessionIdAndSessionTurnIdOrderByPositionAsc(12L, 101L))
                .thenReturn(List.of(firstBinding, secondBinding));
        when(turnAttachmentSelectionValidator.validateBoundRetry(
                session, List.of(firstId, secondId))).thenReturn(selection);
        when(agentRunRepository.existsBySessionIdAndStatusIn(
                12L, AgentRunStatus.nonTerminalStatuses())).thenReturn(false);
        when(agentRunRepository.save(any(AgentRunEntity.class))).thenAnswer(invocation -> {
            AgentRunEntity saved = invocation.getArgument(0);
            saved.setId(82L);
            return saved;
        });

        AgentRunEntity retry = agentRunService.createRemoteRetryRun(81L);

        assertEquals(82L, retry.getId());
        assertEquals(source, retry.getRetryOfRun());
        assertEquals(originTurn, retry.getOriginTurn());
        assertEquals(ProjectCodexIdentity.IMAGE_WORKLOAD_KIND, retry.getWorkloadKind());
        assertEquals(2, retry.getAttachmentCount());
        assertEquals(3072L, retry.getAttachmentBytes());
        assertEquals("c".repeat(64), retry.getAttachmentManifestSha256());
        assertEquals("gpt-5.6-sol", retry.getCodexModelId());
        assertEquals(ExecutionProfileSource.WORK_SESSION, retry.getCodexModelSource());
        assertEquals(CodexReasoningEffort.HIGH, retry.getCodexReasoningEffort());
        assertEquals(ExecutionProfileSource.NEXT_TURN, retry.getCodexEffortSource());
        assertEquals("d".repeat(64), retry.getCodexCatalogRevision());
        assertEquals("0.145.0", retry.getCodexVersion());
        assertEquals(AgentRunStatus.FAILED, source.getStatus());
        assertEquals(originTurn, source.getOriginTurn());
        assertEquals(2, source.getAttachmentCount());
        assertEquals(3072L, source.getAttachmentBytes());
        assertEquals("c".repeat(64), source.getAttachmentManifestSha256());
        assertEquals("gpt-5.6-sol", source.getCodexModelId());
        assertEquals(CodexReasoningEffort.HIGH, source.getCodexReasoningEffort());
        assertEquals("d".repeat(64), source.getCodexCatalogRevision());
        assertEquals("0.145.0", source.getCodexVersion());
        verify(sessionTurnAttachmentRepository, never()).insert(any(), any(), any(), anyShort());
        verify(sessionTurnRepository, never()).save(any(SessionTurnEntity.class));
        verify(codexExecutionProfileSnapshotService, never()).applyCurrentProfile(retry);
    }

    @Test
    void createRemoteRetryRejectsUnclearedDeterministicBlockerWithoutMutation() {
        WorkSessionEntity session = buildSession(12L, 7L, ProjectCodexIdentity.REPO_PATH);
        SessionTurnEntity originTurn = new SessionTurnEntity();
        originTurn.setId(101L);
        originTurn.setSession(session);
        AgentRunEntity source = buildRun(81L, AgentRunStatus.FAILED);
        source.setSession(session);
        source.setOriginTurn(originTurn);
        source.setExecutionTarget(ExecutionTarget.REMOTE);
        source.setWorkloadKind(ProjectCodexIdentity.IMAGE_WORKLOAD_KIND);
        source.setAttachmentCount(1);
        source.setAttachmentBytes(1024L);
        source.setAttachmentManifestSha256("a".repeat(64));
        source.setCodexModelId("gpt-5.6-sol");
        source.setCodexReasoningEffort(CodexReasoningEffort.HIGH);
        source.setFailureCode("CLOSED_SESSION_OWNS_CAPACITY");
        source.setRecoveryNextAction(AgentRunRecoveryNextAction.RECONCILE_REMOTE_CLOSE);
        when(agentRunRepository.findByIdForUpdate(81L)).thenReturn(Optional.of(source));

        assertThrows(
                AgentRunRecoveryConflictException.class,
                () -> agentRunService.createRemoteRetryRun(81L));

        assertEquals(AgentRunStatus.FAILED, source.getStatus());
        assertEquals(originTurn, source.getOriginTurn());
        assertEquals("CLOSED_SESSION_OWNS_CAPACITY", source.getFailureCode());
        assertEquals(AgentRunRecoveryNextAction.RECONCILE_REMOTE_CLOSE,
                source.getRecoveryNextAction());
        assertEquals(1, source.getAttachmentCount());
        assertEquals(1024L, source.getAttachmentBytes());
        assertEquals("a".repeat(64), source.getAttachmentManifestSha256());
        assertEquals("gpt-5.6-sol", source.getCodexModelId());
        assertEquals(CodexReasoningEffort.HIGH, source.getCodexReasoningEffort());
        verify(agentRunRepository, never()).findFirstByRetryOfRunIdOrderByCreatedAtAsc(81L);
        verify(agentRunRepository, never()).save(any(AgentRunEntity.class));
        verify(sessionTurnAttachmentRepository, never())
                .findByWorkSessionIdAndSessionTurnIdOrderByPositionAsc(any(), any());
        verify(sessionTurnRepository, never()).save(any(SessionTurnEntity.class));
        verify(codexExecutionProfileSnapshotService, never()).applyCurrentProfile(any());
    }

    @Test
    void exactReleasedBlockerReceiptAllowsRetryEligibilityForLaterTerminalProof() {
        AgentRunEntity source = closedOwnerBlockedRun();
        WorkSessionEntity blocker = releasedBlocker();
        when(workSessionRepository.findWithProjectById(16L))
                .thenReturn(Optional.of(blocker));
        when(jdbcTemplate.queryForObject(any(String.class), eq(Boolean.class),
                any(), any(), any())).thenReturn(true);

        assertDoesNotThrow(() -> agentRunService.requireRemoteRetryEligible(source));
    }

    @Test
    void readModelEligibilityRequiresTerminalSourceAndExactReleasedReceipt() {
        AgentRunEntity source = closedOwnerBlockedRun();
        WorkSessionEntity blocker = releasedBlocker();
        when(agentRunRepository.findById(81L)).thenReturn(Optional.of(source));
        when(workSessionRepository.findWithProjectById(16L))
                .thenReturn(Optional.of(blocker));
        when(jdbcTemplate.queryForObject(any(String.class), eq(Boolean.class),
                any(), any(), any())).thenReturn(true);

        assertTrue(agentRunService.isRemoteRetryEligible(81L));

        source.setStatus(AgentRunStatus.RECONCILING);
        assertFalse(agentRunService.isRemoteRetryEligible(81L));
    }

    @Test
    void missingMatchingReceiptKeepsClosedOwnerRetryUnavailable() {
        AgentRunEntity source = closedOwnerBlockedRun();
        WorkSessionEntity blocker = releasedBlocker();
        blocker.setRemoteCloseReceiptSha256(null);
        when(workSessionRepository.findWithProjectById(16L))
                .thenReturn(Optional.of(blocker));

        assertThrows(AgentRunRecoveryConflictException.class,
                () -> agentRunService.requireRemoteRetryEligible(source));
    }

    @Test
    void projectedReceiptWithoutExactReleasedOperationKeepsRetryUnavailable() {
        AgentRunEntity source = closedOwnerBlockedRun();
        WorkSessionEntity blocker = releasedBlocker();
        when(workSessionRepository.findWithProjectById(16L))
                .thenReturn(Optional.of(blocker));
        when(jdbcTemplate.queryForObject(any(String.class), eq(Boolean.class),
                any(), any(), any())).thenReturn(false);

        assertThrows(AgentRunRecoveryConflictException.class,
                () -> agentRunService.requireRemoteRetryEligible(source));
    }

    @Test
    void createRemoteImageRetryFailsClosedWhenRetainedManifestChanges() {
        WorkSessionEntity session = buildSession(12L, 7L, ProjectCodexIdentity.REPO_PATH);
        session.setExecutionTarget(ExecutionTarget.REMOTE);
        UUID attachmentId = UUID.fromString("4e8f351e-e05a-41b6-99e5-3eb72d770002");
        SessionTurnEntity originTurn = new SessionTurnEntity();
        originTurn.setId(101L);
        originTurn.setSession(session);
        AgentRunEntity source = buildRun(81L, AgentRunStatus.FAILED);
        source.setSession(session);
        source.setOriginTurn(originTurn);
        source.setExecutionTarget(ExecutionTarget.REMOTE);
        source.setWorkloadKind(ProjectCodexIdentity.IMAGE_WORKLOAD_KIND);
        source.setAttachmentCount(1);
        source.setAttachmentBytes(1024L);
        source.setAttachmentManifestSha256("a".repeat(64));
        SessionTurnAttachmentEntity binding = mock(SessionTurnAttachmentEntity.class);
        when(binding.getPosition()).thenReturn((short) 0);
        when(binding.getAttachmentId()).thenReturn(attachmentId);
        when(agentRunRepository.findByIdForUpdate(81L)).thenReturn(Optional.of(source));
        when(agentRunRepository.findFirstByRetryOfRunIdOrderByCreatedAtAsc(81L))
                .thenReturn(Optional.empty());
        when(sessionTurnAttachmentRepository
                .findByWorkSessionIdAndSessionTurnIdOrderByPositionAsc(12L, 101L))
                .thenReturn(List.of(binding));
        when(turnAttachmentSelectionValidator.validateBoundRetry(
                session, List.of(attachmentId))).thenReturn(
                        new TurnAttachmentSelectionValidator.ValidatedSelection(
                                List.of(new TurnAttachmentSelectionValidator.ValidatedAttachment(
                                        attachmentId, "image/png", 1024L, "b".repeat(64))),
                                1024L,
                                "b".repeat(64)));

        assertThrows(
                AgentRunRecoveryConflictException.class,
                () -> agentRunService.createRemoteRetryRun(81L));

        verify(agentRunRepository, never()).save(any(AgentRunEntity.class));
        verify(sessionTurnAttachmentRepository, never()).insert(any(), any(), any(), anyShort());
        verify(sessionTurnRepository, never()).save(any(SessionTurnEntity.class));
    }

    @Test
    void createRemoteImageRetryFailsClosedWhenOriginBindingIsMissing() {
        WorkSessionEntity session = buildSession(12L, 7L, ProjectCodexIdentity.REPO_PATH);
        session.setExecutionTarget(ExecutionTarget.REMOTE);
        SessionTurnEntity originTurn = new SessionTurnEntity();
        originTurn.setId(101L);
        originTurn.setSession(session);
        AgentRunEntity source = buildRun(81L, AgentRunStatus.FAILED);
        source.setSession(session);
        source.setOriginTurn(originTurn);
        source.setExecutionTarget(ExecutionTarget.REMOTE);
        source.setWorkloadKind(ProjectCodexIdentity.IMAGE_WORKLOAD_KIND);
        source.setAttachmentCount(1);
        source.setAttachmentBytes(1024L);
        source.setAttachmentManifestSha256("a".repeat(64));
        when(agentRunRepository.findByIdForUpdate(81L)).thenReturn(Optional.of(source));
        when(agentRunRepository.findFirstByRetryOfRunIdOrderByCreatedAtAsc(81L))
                .thenReturn(Optional.empty());
        when(sessionTurnAttachmentRepository
                .findByWorkSessionIdAndSessionTurnIdOrderByPositionAsc(12L, 101L))
                .thenReturn(List.of());

        assertThrows(
                AgentRunRecoveryConflictException.class,
                () -> agentRunService.createRemoteRetryRun(81L));

        verify(turnAttachmentSelectionValidator, never()).validateBoundRetry(any(), any());
        verify(agentRunRepository, never()).save(any(AgentRunEntity.class));
        verify(sessionTurnAttachmentRepository, never()).insert(any(), any(), any(), anyShort());
    }

    @Test
    void createRemoteProjectRunPersistsExactImmutableWorkloadIdentity() {
        WorkSessionEntity session = buildSession(12L, 7L, "/workspace/repos/internal/atenea");
        session.setBaseBranch(ProjectCodexIdentity.BRANCH);
        session.setExecutionTarget(ExecutionTarget.REMOTE);
        session.setSelectedWorkerId("ax42-01");
        UUID remoteSessionId = UUID.fromString("a1c3af50-af6e-4cc2-85d6-a491c50cddcc");
        session.setRemoteSessionId(remoteSessionId);
        session.setRemoteWorkloadKind(ProjectCodexIdentity.WORKLOAD_KIND);
        session.setCanonicalSourceRef("refs/heads/" + ProjectCodexIdentity.BRANCH);
        session.setCanonicalSourceCommit(TEST_CANONICAL_COMMIT);
        session.setCanonicalSourceObservationSha256("2".repeat(64));
        session.setCanonicalSourceObservedAt(Instant.now());
        session.setWorkspaceIdentity("remote:ax42-01:work-session:" + remoteSessionId);
        SessionTurnEntity originTurn = new SessionTurnEntity();
        originTurn.setId(101L);
        originTurn.setSession(session);
        originTurn.setActor(SessionTurnActor.OPERATOR);
        originTurn.setMessageText("project turn");
        originTurn.setCreatedAt(Instant.now());
        when(agentRunRepository.existsBySessionIdAndStatus(12L, AgentRunStatus.RUNNING)).thenReturn(false);
        when(agentRunRepository.existsBySessionIdAndStatusIn(
                12L,
                AgentRunStatus.nonTerminalStatuses())).thenReturn(false);
        when(agentRunRepository.save(any(AgentRunEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AgentRunEntity run = agentRunService.createRemoteQueuedRun(session, originTurn, WorkloadClass.NORMAL);

        assertEquals(ProjectCodexIdentity.WORKLOAD_KIND, run.getWorkloadKind());
        assertEquals(ProjectCodexIdentity.PROJECT_IDENTITY, run.getProjectIdentity());
        assertEquals(ProjectCodexIdentity.REPOSITORY, run.getRepositoryUrl());
        assertEquals(ProjectCodexIdentity.BRANCH, run.getRepositoryBranch());
        assertEquals(TEST_CANONICAL_COMMIT, run.getRepositoryCommit());
        assertEquals(ProjectCodexIdentity.MANIFEST_SHA256, run.getManifestSha256());
        assertEquals(ReviewedInstructionBundleIdentity.REVISION,
                run.getInstructionBundleRevision());
        assertEquals(ReviewedInstructionBundleIdentity.ATENEA_BUNDLE_SHA256,
                run.getInstructionBundleSha256());
        assertEquals(ReviewedInstructionBundleIdentity.PLATFORM_SHA256,
                run.getPlatformInstructionSha256());
        assertEquals(ReviewedInstructionBundleIdentity.PROJECT_PATH,
                run.getProjectInstructionPath());
        assertEquals(ReviewedInstructionBundleIdentity.ATENEA_PROJECT_SHA256,
                run.getProjectInstructionSha256());
    }

    @Test
    void createRemoteImageRunPersistsV3AttachmentSnapshotBeforeFirstSave() {
        WorkSessionEntity session = buildSession(12L, 7L, ProjectCodexIdentity.REPO_PATH);
        session.setBaseBranch(ProjectCodexIdentity.BRANCH);
        session.setExecutionTarget(ExecutionTarget.REMOTE);
        session.setSelectedWorkerId("ax42-01");
        UUID remoteSessionId = UUID.fromString("a1c3af50-af6e-4cc2-85d6-a491c50cddcc");
        session.setRemoteSessionId(remoteSessionId);
        session.setRemoteWorkloadKind(ProjectCodexIdentity.WORKLOAD_KIND);
        session.setCanonicalSourceRef("refs/heads/" + ProjectCodexIdentity.BRANCH);
        session.setCanonicalSourceCommit(TEST_CANONICAL_COMMIT);
        session.setCanonicalSourceObservationSha256("2".repeat(64));
        session.setCanonicalSourceObservedAt(Instant.now());
        session.setWorkspaceIdentity("remote:ax42-01:work-session:" + remoteSessionId);
        SessionTurnEntity originTurn = new SessionTurnEntity();
        originTurn.setId(101L);
        originTurn.setSession(session);
        originTurn.setActor(SessionTurnActor.OPERATOR);
        originTurn.setMessageText("image turn");
        originTurn.setCreatedAt(Instant.now());
        TurnAttachmentSelectionValidator.ValidatedSelection selection =
                new TurnAttachmentSelectionValidator.ValidatedSelection(
                        List.of(
                                new TurnAttachmentSelectionValidator.ValidatedAttachment(
                                        UUID.fromString("4e8f351e-e05a-41b6-99e5-3eb72d770002"),
                                        "image/png",
                                        1024L,
                                        "a".repeat(64)),
                                new TurnAttachmentSelectionValidator.ValidatedAttachment(
                                        UUID.fromString("9aa2c7e5-1fd9-48ec-aa10-03dfdfb8ca7d"),
                                        "image/webp",
                                        2048L,
                                        "b".repeat(64))),
                        3072L,
                        "c".repeat(64));
        when(agentRunRepository.existsBySessionIdAndStatusIn(
                12L,
                AgentRunStatus.nonTerminalStatuses())).thenReturn(false);
        when(agentRunRepository.save(any(AgentRunEntity.class))).thenAnswer(invocation -> {
            AgentRunEntity saved = invocation.getArgument(0);
            assertEquals(ProjectCodexIdentity.IMAGE_WORKLOAD_KIND, saved.getWorkloadKind());
            assertEquals(2, saved.getAttachmentCount());
            assertEquals(3072L, saved.getAttachmentBytes());
            assertEquals("c".repeat(64), saved.getAttachmentManifestSha256());
            return saved;
        });

        AgentRunEntity run = agentRunService.createRemoteQueuedRun(
                session,
                originTurn,
                WorkloadClass.NORMAL,
                selection);

        assertEquals(ProjectCodexIdentity.IMAGE_WORKLOAD_KIND, run.getWorkloadKind());
        assertEquals(ProjectCodexIdentity.PROJECT_IDENTITY, run.getProjectIdentity());
        assertEquals(2, run.getAttachmentCount());
        assertEquals(3072L, run.getAttachmentBytes());
        assertEquals("c".repeat(64), run.getAttachmentManifestSha256());
        verify(codexExecutionProfileSnapshotService).applyCurrentProfile(run);
    }

    @Test
    void createRemoteBeautipsRunPersistsExactImmutableIdentityBeforeDispatch() {
        WorkSessionEntity session = buildSession(
                12L,
                8L,
                BeautipsProjectCodexIdentity.REPO_PATH);
        session.getProject().setName(BeautipsProjectCodexIdentity.PROJECT_NAME);
        session.getProject().setDefaultBaseBranch(BeautipsProjectCodexIdentity.BRANCH);
        session.setBaseBranch(BeautipsProjectCodexIdentity.BRANCH);
        session.setExecutionTarget(ExecutionTarget.REMOTE);
        session.setSelectedWorkerId("ax42-01");
        UUID remoteSessionId = UUID.fromString("a1c3af50-af6e-4cc2-85d6-a491c50cddcc");
        session.setRemoteSessionId(remoteSessionId);
        session.setRemoteWorkloadKind(ProjectCodexIdentity.WORKLOAD_KIND);
        session.setWorkspaceIdentity("remote:ax42-01:work-session:" + remoteSessionId);
        SessionTurnEntity originTurn = new SessionTurnEntity();
        originTurn.setId(101L);
        originTurn.setSession(session);
        originTurn.setActor(SessionTurnActor.OPERATOR);
        originTurn.setMessageText("beautips project turn");
        originTurn.setCreatedAt(Instant.now());
        when(agentRunRepository.existsBySessionIdAndStatus(12L, AgentRunStatus.RUNNING)).thenReturn(false);
        when(agentRunRepository.existsBySessionIdAndStatusIn(
                12L,
                AgentRunStatus.nonTerminalStatuses())).thenReturn(false);
        when(agentRunRepository.save(any(AgentRunEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AgentRunEntity run = agentRunService.createRemoteQueuedRun(session, originTurn, WorkloadClass.NORMAL);

        assertEquals(AgentRunStatus.QUEUED, run.getStatus());
        assertEquals(BeautipsProjectCodexIdentity.REPO_PATH, run.getTargetRepoPath());
        assertEquals("ax42-01", run.getSelectedWorkerId());
        assertEquals("remote:ax42-01:work-session:" + remoteSessionId, run.getWorkspaceIdentity());
        assertEquals(remoteSessionId, run.getRemoteSessionId());
        assertEquals(ProjectCodexIdentity.WORKLOAD_KIND, run.getWorkloadKind());
        assertEquals(BeautipsProjectCodexIdentity.PROJECT_IDENTITY, run.getProjectIdentity());
        assertEquals(BeautipsProjectCodexIdentity.REPOSITORY, run.getRepositoryUrl());
        assertEquals(BeautipsProjectCodexIdentity.BRANCH, run.getRepositoryBranch());
        assertEquals(BeautipsProjectCodexIdentity.COMMIT, run.getRepositoryCommit());
        assertEquals(BeautipsProjectCodexIdentity.MANIFEST_SHA256, run.getManifestSha256());
        assertEquals(ReviewedInstructionBundleIdentity.BEAUTIPS_BUNDLE_SHA256,
                run.getInstructionBundleSha256());
        assertEquals(ReviewedInstructionBundleIdentity.BEAUTIPS_PROJECT_SHA256,
                run.getProjectInstructionSha256());
        assertNotNull(run.getDispatchId());
        assertNull(run.getRemoteExecutionId());
        assertTrue(BeautipsProjectCodexIdentity.matches(run));
    }

    @Test
    void createRemoteBeautipsRunRejectsPartialIdentityBeforePersistence() {
        WorkSessionEntity session = buildSession(
                12L,
                8L,
                BeautipsProjectCodexIdentity.REPO_PATH);
        session.getProject().setName(BeautipsProjectCodexIdentity.PROJECT_NAME);
        session.getProject().setDefaultBaseBranch(BeautipsProjectCodexIdentity.BRANCH);
        session.setBaseBranch(BeautipsProjectCodexIdentity.BRANCH);
        session.setExecutionTarget(ExecutionTarget.REMOTE);
        session.setSelectedWorkerId(BeautipsProjectCodexIdentity.WORKER_ID);
        UUID remoteSessionId = UUID.fromString("a1c3af50-af6e-4cc2-85d6-a491c50cddcc");
        session.setRemoteSessionId(remoteSessionId);
        session.setRemoteWorkloadKind(ProjectCodexIdentity.WORKLOAD_KIND);
        session.setWorkspaceIdentity("remote:foreign:work-session:" + remoteSessionId);

        assertThrows(
                IllegalStateException.class,
                () -> agentRunService.createRemoteQueuedRun(
                        session,
                        new SessionTurnEntity(),
                        WorkloadClass.NORMAL));
        verify(agentRunRepository, never()).save(any(AgentRunEntity.class));
    }

    @Test
    void reconcileSessionMarksRunningRunFailedWhenItExceededTimeoutWindow() {
        AgentRunEntity run = buildRun(55L, AgentRunStatus.RUNNING);
        run.setStartedAt(Instant.now().minus(Duration.ofMinutes(7)));

        when(codexAppServerProperties.getStaleTimeout()).thenReturn(Duration.ofMinutes(5));
        when(agentRunRepository.findBySessionIdAndStatusOrderByCreatedAtAsc(12L, AgentRunStatus.RUNNING))
                .thenReturn(java.util.List.of(run));
        when(agentRunRepository.saveAndFlush(any(AgentRunEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        boolean reconciled = agentRunReconciliationService.reconcileSession(12L);

        assertEquals(true, reconciled);
        assertEquals(AgentRunStatus.FAILED, run.getStatus());
        assertEquals(
                "Marked FAILED during reconciliation because the run stayed RUNNING past the stale timeout window",
                run.getErrorSummary());
        assertNotNull(run.getFinishedAt());
        assertNull(run.getOutputSummary());
        verify(mobilePushDispatchService).notifyRunFailed(run);
    }

    @Test
    void reconcileSessionKeepsRecentRunningRunUntouched() {
        AgentRunEntity run = buildRun(55L, AgentRunStatus.RUNNING);
        run.setStartedAt(Instant.now().minus(Duration.ofMinutes(2)));

        when(codexAppServerProperties.getStaleTimeout()).thenReturn(Duration.ofMinutes(5));
        when(agentRunRepository.findBySessionIdAndStatusOrderByCreatedAtAsc(12L, AgentRunStatus.RUNNING))
                .thenReturn(java.util.List.of(run));

        boolean reconciled = agentRunReconciliationService.reconcileSession(12L);

        assertEquals(false, reconciled);
        assertEquals(AgentRunStatus.RUNNING, run.getStatus());
        assertNull(run.getFinishedAt());
        assertNull(run.getErrorSummary());
    }

    @Test
    void reconcileRunningRunsAfterStartupMarksAllPersistedRunningRunsFailed() {
        AgentRunEntity firstRun = buildRun(55L, AgentRunStatus.RUNNING);
        AgentRunEntity secondRun = buildRun(56L, AgentRunStatus.RUNNING);

        when(agentRunRepository.findByStatusOrderByCreatedAtAsc(AgentRunStatus.RUNNING))
                .thenReturn(java.util.List.of(firstRun, secondRun));
        when(agentRunRepository.saveAndFlush(any(AgentRunEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        int reconciledCount = agentRunReconciliationService.reconcileRunningRunsAfterStartup();

        assertEquals(2, reconciledCount);
        assertEquals(AgentRunStatus.FAILED, firstRun.getStatus());
        assertEquals(AgentRunStatus.FAILED, secondRun.getStatus());
        assertEquals(
                "Marked FAILED during startup reconciliation because Atenea restarted while the run was still RUNNING",
                firstRun.getErrorSummary());
        assertNotNull(firstRun.getFinishedAt());
        assertNull(firstRun.getOutputSummary());
        verify(mobilePushDispatchService).notifyRunFailed(firstRun);
        verify(mobilePushDispatchService).notifyRunFailed(secondRun);
    }

    @Test
    void localStartupReconciliationDoesNotFailRemoteRunningRun() {
        AgentRunEntity remote = buildRun(55L, AgentRunStatus.RUNNING);
        remote.setExecutionTarget(ExecutionTarget.REMOTE);
        when(agentRunRepository.findByStatusOrderByCreatedAtAsc(AgentRunStatus.RUNNING))
                .thenReturn(java.util.List.of(remote));

        int reconciledCount = agentRunReconciliationService.reconcileRunningRunsAfterStartup();

        assertEquals(0, reconciledCount);
        assertEquals(AgentRunStatus.RUNNING, remote.getStatus());
        assertNull(remote.getFinishedAt());
    }

    private static WorkSessionEntity buildSession(Long sessionId, Long projectId, String repoPath) {
        ProjectEntity project = new ProjectEntity();
        project.setId(projectId);
        project.setName("Atenea");
        project.setRepoPath(repoPath);
        project.setCreatedAt(Instant.parse("2026-03-25T10:00:00Z"));
        project.setUpdatedAt(Instant.parse("2026-03-25T10:00:00Z"));

        WorkSessionEntity session = new WorkSessionEntity();
        session.setId(sessionId);
        session.setProject(project);
        session.setStatus(WorkSessionStatus.OPEN);
        session.setTitle("Inspect project status");
        session.setBaseBranch("main");
        session.setOpenedAt(Instant.parse("2026-03-25T10:05:00Z"));
        session.setLastActivityAt(Instant.parse("2026-03-25T10:05:00Z"));
        session.setCreatedAt(Instant.parse("2026-03-25T10:05:00Z"));
        session.setUpdatedAt(Instant.parse("2026-03-25T10:05:00Z"));
        return session;
    }

    private static AgentRunEntity buildRun(Long runId, AgentRunStatus status) {
        WorkSessionEntity session = buildSession(12L, 7L, "/workspace/repos/internal/atenea");

        SessionTurnEntity originTurn = new SessionTurnEntity();
        originTurn.setId(101L);
        originTurn.setSession(session);
        originTurn.setActor(SessionTurnActor.ATENEA);
        originTurn.setMessageText("Internal AgentRun origin");
        originTurn.setInternal(true);
        originTurn.setCreatedAt(Instant.parse("2026-03-25T10:06:00Z"));

        AgentRunEntity run = new AgentRunEntity();
        run.setId(runId);
        run.setSession(session);
        run.setOriginTurn(originTurn);
        run.setStatus(status);
        run.setTargetRepoPath("/workspace/repos/internal/atenea");
        run.setStartedAt(Instant.parse("2026-03-25T10:06:00Z"));
        run.setCreatedAt(Instant.parse("2026-03-25T10:06:00Z"));
        return run;
    }

    private static AgentRunEntity closedOwnerBlockedRun() {
        WorkSessionEntity session = buildSession(
                17L, 7L, ProjectCodexIdentity.REPO_PATH);
        UUID sessionId = UUID.fromString("18c00753-6080-42f7-ac05-18c47b236cac");
        session.setExecutionTarget(ExecutionTarget.REMOTE);
        session.setSelectedWorkerId(ProjectCodexIdentity.WORKER_ID);
        session.setRemoteSessionId(sessionId);
        session.setWorkspaceIdentity("remote:ax42-01:work-session:" + sessionId);
        AgentRunEntity source = buildRun(96L, AgentRunStatus.FAILED);
        source.setSession(session);
        source.setExecutionTarget(ExecutionTarget.REMOTE);
        source.setSelectedWorkerId(ProjectCodexIdentity.WORKER_ID);
        source.setRemoteSessionId(sessionId);
        source.setFailureCode("CLOSED_SESSION_OWNS_CAPACITY");
        source.setRecoveryNextAction(AgentRunRecoveryNextAction.RECONCILE_REMOTE_CLOSE);
        source.setRecoveryBlockerWorkSessionId(16L);
        return source;
    }

    private static WorkSessionEntity releasedBlocker() {
        WorkSessionEntity blocker = buildSession(
                16L, 7L, ProjectCodexIdentity.REPO_PATH);
        UUID remoteId = UUID.fromString("7151dce0-69ab-4614-86e4-f93f1af825e4");
        blocker.setStatus(WorkSessionStatus.CLOSED);
        blocker.setExecutionTarget(ExecutionTarget.REMOTE);
        blocker.setSelectedWorkerId(ProjectCodexIdentity.WORKER_ID);
        blocker.setRemoteSessionId(remoteId);
        blocker.setWorkspaceIdentity("remote:ax42-01:work-session:" + remoteId);
        blocker.setCanonicalSourceRef("refs/heads/main");
        blocker.setCanonicalSourceCommit("a".repeat(40));
        blocker.setCanonicalSourceObservationSha256("b".repeat(64));
        blocker.setCanonicalSourceObservedAt(Instant.parse("2026-08-03T10:00:00Z"));
        blocker.setRemoteCloseState(RemoteCloseState.RELEASED);
        blocker.setRemoteCloseOperationId(
                UUID.fromString("12c9de9d-6079-4f47-978e-ff52a440ba40"));
        blocker.setRemoteCloseReceiptSha256("c".repeat(64));
        blocker.setRemoteCloseReleasedAt(Instant.parse("2026-08-03T11:00:00Z"));
        return blocker;
    }
}
