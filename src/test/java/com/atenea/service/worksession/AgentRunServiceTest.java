package com.atenea.service.worksession;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.atenea.mobilepush.MobilePushDispatchService;
import com.atenea.codexoperations.CodexExecutionProfileSnapshotService;
import com.atenea.persistence.project.ProjectEntity;
import com.atenea.persistence.worksession.AgentRunEntity;
import com.atenea.persistence.worksession.AgentRunRepository;
import com.atenea.persistence.worksession.AgentRunProcessOutcome;
import com.atenea.persistence.worksession.AgentRunStatus;
import com.atenea.persistence.worksession.SessionTurnActor;
import com.atenea.persistence.worksession.SessionTurnEntity;
import com.atenea.persistence.worksession.SessionTurnRepository;
import com.atenea.persistence.worksession.WorkSessionEntity;
import com.atenea.persistence.worksession.WorkSessionRepository;
import com.atenea.persistence.worksession.WorkSessionStatus;
import com.atenea.persistence.worksession.ExecutionTarget;
import com.atenea.persistence.worksession.WorkloadClass;
import com.atenea.remoteworker.BeautipsProjectCodexIdentity;
import com.atenea.remoteworker.ProjectCodexIdentity;
import com.atenea.remoteworker.ReviewedInstructionBundleIdentity;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
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
    private com.atenea.codexappserver.CodexAppServerProperties codexAppServerProperties;

    @Mock
    private MobilePushDispatchService mobilePushDispatchService;

    @Mock
    private WorkSessionAcceptanceService workSessionAcceptanceService;

    @Mock
    private CodexExecutionProfileSnapshotService codexExecutionProfileSnapshotService;

    private AgentRunService agentRunService;
    private AgentRunReconciliationService agentRunReconciliationService;

    @BeforeEach
    void setUp() {
        agentRunReconciliationService = new AgentRunReconciliationService(agentRunRepository, codexAppServerProperties);
        agentRunService = new AgentRunService(
                workSessionRepository,
                agentRunRepository,
                sessionTurnRepository,
                new AgentRunProgressService(),
                mobilePushDispatchService,
                workSessionAcceptanceService,
                codexExecutionProfileSnapshotService
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
    }

    @Test
    void forceMarkFailedIfRunningUsesConditionalRepositoryUpdate() {
        when(agentRunRepository.forceMarkFailedIfRunning(eq(55L), eq("turn_456"), eq("Codex execution failed"), any()))
                .thenReturn(1);

        boolean updated = agentRunService.forceMarkFailedIfRunning(55L, " turn_456 ", " Codex execution failed ");

        assertTrue(updated);
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
}
