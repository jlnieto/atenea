package com.atenea.remoteworker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.atenea.persistence.project.ProjectEntity;
import com.atenea.persistence.developmentchange.DevelopmentChangeEntity;
import com.atenea.persistence.developmentchange.DevelopmentChangeSourceState;
import com.atenea.persistence.developmentchange.DevelopmentChangeStatus;
import com.atenea.persistence.developmentchange.DevelopmentChangeWorkspaceState;
import com.atenea.persistence.worksession.AgentRunEntity;
import com.atenea.persistence.worksession.AgentRunProcessOutcome;
import com.atenea.persistence.worksession.AgentRunRecoveryNextAction;
import com.atenea.persistence.worksession.AgentRunRepository;
import com.atenea.persistence.worksession.AgentRunStatus;
import com.atenea.persistence.worksession.ExecutionTarget;
import com.atenea.persistence.worksession.SessionTurnActor;
import com.atenea.persistence.worksession.SessionTurnEntity;
import com.atenea.persistence.worksession.SessionTurnRepository;
import com.atenea.persistence.worksession.WorkSessionEntity;
import com.atenea.persistence.worksession.WorkSessionRepository;
import com.atenea.persistence.worksession.WorkSessionStatus;
import com.atenea.persistence.worksession.WorkloadClass;
import com.atenea.persistence.worksession.AgentRunProgressCategory;
import com.atenea.service.worksession.AgentRunProgressService;
import com.atenea.service.developmentchange.DevelopmentChangeAgentRunSourceAdvanceService;
import com.atenea.mobilepush.MobilePushDispatchService;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

class RemoteAgentRunCoordinatorTest {
    private static final String TEST_CANONICAL_COMMIT = "1".repeat(40);

    private AgentRunRepository agentRunRepository;
    private WorkSessionRepository workSessionRepository;
    private SessionTurnRepository sessionTurnRepository;
    private AgentRunProgressService progressService;
    private RemoteWorkerClient client;
    private RemoteWorkerProperties properties;
    private MobilePushDispatchService mobilePushDispatchService;
    private DevelopmentChangeAgentRunSourceAdvanceService sourceAdvanceService;
    private RemoteAgentRunCoordinator coordinator;

    @BeforeEach
    void setUp() {
        agentRunRepository = mock(AgentRunRepository.class);
        workSessionRepository = mock(WorkSessionRepository.class);
        sessionTurnRepository = mock(SessionTurnRepository.class);
        progressService = mock(AgentRunProgressService.class);
        client = mock(RemoteWorkerClient.class);
        properties = new RemoteWorkerProperties();
        mobilePushDispatchService = mock(MobilePushDispatchService.class);
        sourceAdvanceService = mock(DevelopmentChangeAgentRunSourceAdvanceService.class);
        properties.setPollInterval(Duration.ofMillis(10));
        properties.setReconciliationTimeout(Duration.ofMillis(40));
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(mock(TransactionStatus.class));
        when(agentRunRepository.findByIdForUpdate(any())).thenAnswer(invocation ->
                agentRunRepository.findWithSessionById(invocation.getArgument(0)));
        when(client.ensureWorkspace(any(AgentRunEntity.class))).thenAnswer(invocation -> {
            AgentRunEntity run = invocation.getArgument(0);
            return new RemoteWorkerClient.Workspace(
                    "ready",
                    run.getRemoteSessionId().toString(),
                    run.getWorkspaceIdentity(),
                    run.getProjectIdentity(),
                    run.getSession().getWorkspaceBranch(),
                    "slot2",
                    run.getRepositoryCommit(),
                    true,
                    true,
                    false);
        });
        coordinator = new RemoteAgentRunCoordinator(
                agentRunRepository,
                workSessionRepository,
                sessionTurnRepository,
                progressService,
                client,
                properties,
                mobilePushDispatchService,
                sourceAdvanceService,
                transactionManager);
    }

    @AfterEach
    void tearDown() {
        coordinator.shutdown();
    }

    @Test
    void projectTerminalResultMapsThreadTurnAndAnswerExactlyOnce() throws Exception {
        AgentRunEntity run = projectRun();
        WorkSessionEntity session = run.getSession();
        AtomicInteger resultTurns = new AtomicInteger();
        when(agentRunRepository.findWithSessionById(run.getId())).thenReturn(Optional.of(run));
        when(agentRunRepository.findById(run.getId())).thenReturn(Optional.of(run));
        when(agentRunRepository.save(any(AgentRunEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(workSessionRepository.findById(session.getId())).thenReturn(Optional.of(session));
        when(workSessionRepository.save(any(WorkSessionEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(sessionTurnRepository.save(any(SessionTurnEntity.class))).thenAnswer(invocation -> {
            SessionTurnEntity turn = invocation.getArgument(0);
            turn.setId(900L + resultTurns.incrementAndGet());
            return turn;
        });
        when(client.dispatch(run, "First managed turn")).thenReturn(succeeded(run));

        coordinator.dispatchAfterCommit(run.getId());
        waitForTerminal(run);

        assertEquals(AgentRunStatus.SUCCEEDED, run.getStatus());
        assertEquals("bd312352-28b8-44d0-835f-e1afc5181cc9", session.getExternalThreadId());
        assertEquals("37f0d8f6-4b7f-44cb-b75c-105f51773283", run.getExternalTurnId());
        assertEquals("Managed answer", run.getResultTurn().getMessageText());
        assertEquals("project-codex-v1 completed", run.getOutputSummary());
        assertEquals(1, resultTurns.get());

        coordinator.dispatchAfterCommit(run.getId());
        Thread.sleep(50);

        assertEquals(1, resultTurns.get());
        verify(progressService, times(1)).appendWorker(
                run.getId(), 1, AgentRunProgressCategory.ACCEPTED);
        verify(progressService, times(1)).appendWorker(
                run.getId(), 2, AgentRunProgressCategory.CODEX_STARTED);
        verify(progressService, times(1)).appendWorker(
                run.getId(), 3, AgentRunProgressCategory.COMPLETED);
        verify(client, times(1)).ensureWorkspace(run);
        verify(client, times(1)).dispatch(run, "First managed turn");
        verify(mobilePushDispatchService, times(1)).notifyRunSucceeded(run);
        verify(sourceAdvanceService, never()).advance(any(), any(), any());
    }

    @Test
    void changeBoundRunDispatchesV4WithoutEnsuringAnotherWorkspace() throws Exception {
        AgentRunEntity run = changeBoundRun();
        WorkSessionEntity session = run.getSession();
        java.util.concurrent.atomic.AtomicReference<AgentRunStatus> statusAtSourceAdvance =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<AgentRunProcessOutcome> outcomeAtSourceAdvance =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<Instant> finishedAtSourceAdvance =
                new java.util.concurrent.atomic.AtomicReference<>();
        when(agentRunRepository.findWithSessionById(run.getId())).thenReturn(Optional.of(run));
        when(agentRunRepository.findById(run.getId())).thenReturn(Optional.of(run));
        when(agentRunRepository.save(any(AgentRunEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(workSessionRepository.findById(session.getId())).thenReturn(Optional.of(session));
        when(workSessionRepository.save(any(WorkSessionEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(sessionTurnRepository.save(any(SessionTurnEntity.class))).thenAnswer(invocation -> {
            SessionTurnEntity turn = invocation.getArgument(0);
            turn.setId(901L);
            return turn;
        });
        when(sourceAdvanceService.advance(any(), any(), any())).thenAnswer(invocation -> {
            AgentRunEntity observed = invocation.getArgument(0);
            statusAtSourceAdvance.set(observed.getStatus());
            outcomeAtSourceAdvance.set(observed.getProcessOutcome());
            finishedAtSourceAdvance.set(observed.getFinishedAt());
            return true;
        });
        when(client.dispatch(run, "First managed turn")).thenReturn(succeededV4(run));

        coordinator.dispatchAfterCommit(run.getId());
        waitForTerminal(run);

        assertEquals(AgentRunStatus.SUCCEEDED, run.getStatus());
        assertEquals(AgentRunStatus.SUCCEEDED, statusAtSourceAdvance.get());
        assertEquals(AgentRunProcessOutcome.SUCCEEDED, outcomeAtSourceAdvance.get());
        assertEquals(Instant.parse("2026-07-29T06:00:00Z"), finishedAtSourceAdvance.get());
        assertEquals("project-codex-v4 completed", run.getOutputSummary());
        verify(client, never()).ensureWorkspace(any());
        org.mockito.InOrder workerThenSource = inOrder(client, sourceAdvanceService);
        workerThenSource.verify(client).dispatch(run, "First managed turn");
        workerThenSource.verify(sourceAdvanceService).advance(
                run, succeededV4(run).result().sourceIdentity(),
                Instant.parse("2026-07-29T06:00:00Z"));

        coordinator.dispatchAfterCommit(run.getId());
        Thread.sleep(50);
        verify(sourceAdvanceService, times(1)).advance(any(), any(), any());
    }

    @Test
    void failedAndCancelledChangeRunsNeverAdvanceSource() throws Exception {
        AgentRunEntity failed = changeBoundRun();
        when(agentRunRepository.findWithSessionById(failed.getId())).thenReturn(Optional.of(failed));
        when(agentRunRepository.findById(failed.getId())).thenReturn(Optional.of(failed));
        when(agentRunRepository.save(any(AgentRunEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(client.dispatch(failed, "First managed turn"))
                .thenReturn(execution(failed, "FAILED", null));

        coordinator.dispatchAfterCommit(failed.getId());
        waitForTerminal(failed);
        assertEquals(AgentRunStatus.FAILED, failed.getStatus());
        assertEquals(AgentRunProcessOutcome.FAILED, failed.getProcessOutcome());
        assertEquals(Instant.parse("2026-07-29T06:00:00Z"), failed.getFinishedAt());
        verify(sourceAdvanceService, never()).advance(any(), any(), any());

        AgentRunEntity cancelled = changeBoundRun();
        cancelled.setId(56L);
        cancelled.setRemoteExecutionId("5ee2d311-b9da-4307-89b6-dd3110ef2057");
        cancelled.setStatus(AgentRunStatus.RUNNING);
        when(agentRunRepository.findWithSessionById(cancelled.getId()))
                .thenReturn(Optional.of(cancelled));
        when(agentRunRepository.findById(cancelled.getId())).thenReturn(Optional.of(cancelled));
        when(agentRunRepository.save(any(AgentRunEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(client.cancelExact(cancelled))
                .thenReturn(execution(cancelled, "CANCELLED", null));

        coordinator.requestCancellation(cancelled.getId());
        waitForTerminal(cancelled);
        assertEquals(AgentRunStatus.CANCELLED, cancelled.getStatus());
        assertEquals(AgentRunProcessOutcome.CANCELLED, cancelled.getProcessOutcome());
        assertEquals(Instant.parse("2026-07-29T06:00:00Z"), cancelled.getFinishedAt());
        verify(sourceAdvanceService, never()).advance(any(), any(), any());
    }

    @Test
    void nonTerminalWorkerObservationKeepsFinishedAtNull() throws Exception {
        AgentRunEntity run = changeBoundRun();
        AgentRunEntity terminalSentinel = changeBoundRun();
        terminalSentinel.setStatus(AgentRunStatus.FAILED);
        terminalSentinel.setFinishedAt(Instant.parse("2026-07-29T06:00:01Z"));
        when(agentRunRepository.findWithSessionById(run.getId())).thenReturn(Optional.of(run));
        when(agentRunRepository.findById(run.getId())).thenReturn(Optional.of(terminalSentinel));
        when(agentRunRepository.save(any(AgentRunEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(client.dispatch(run, "First managed turn"))
                .thenReturn(execution(run, "RUNNING", null));

        coordinator.dispatchAfterCommit(run.getId());
        waitForRemoteExecution(run);

        assertEquals(AgentRunStatus.RUNNING, run.getStatus());
        assertNull(run.getProcessOutcome());
        assertNull(run.getFinishedAt());
        verify(agentRunRepository).save(run);
        verify(sourceAdvanceService, never()).advance(any(), any(), any());
    }

    @Test
    void beautipsTerminalResultReusesThreadTurnAndAnswerMappingExactlyOnce() throws Exception {
        AgentRunEntity run = beautipsRun();
        WorkSessionEntity session = run.getSession();
        AtomicInteger resultTurns = new AtomicInteger();
        when(agentRunRepository.findWithSessionById(run.getId())).thenReturn(Optional.of(run));
        when(agentRunRepository.findById(run.getId())).thenReturn(Optional.of(run));
        when(agentRunRepository.save(any(AgentRunEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(workSessionRepository.findById(session.getId())).thenReturn(Optional.of(session));
        when(workSessionRepository.save(any(WorkSessionEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(sessionTurnRepository.save(any(SessionTurnEntity.class))).thenAnswer(invocation -> {
            SessionTurnEntity turn = invocation.getArgument(0);
            turn.setId(900L + resultTurns.incrementAndGet());
            return turn;
        });
        when(client.dispatch(run, "First managed turn")).thenReturn(succeeded(run));

        coordinator.dispatchAfterCommit(run.getId());
        waitForTerminal(run);

        assertEquals(AgentRunStatus.SUCCEEDED, run.getStatus());
        assertEquals("bd312352-28b8-44d0-835f-e1afc5181cc9", session.getExternalThreadId());
        assertEquals("37f0d8f6-4b7f-44cb-b75c-105f51773283", run.getExternalTurnId());
        assertEquals("Managed answer", run.getResultTurn().getMessageText());
        assertEquals("project-codex-v1 completed", run.getOutputSummary());
        assertEquals(1, resultTurns.get());

        coordinator.dispatchAfterCommit(run.getId());
        Thread.sleep(50);

        assertEquals(1, resultTurns.get());
        verify(client, times(1)).ensureWorkspace(run);
        verify(client, times(1)).dispatch(run, "First managed turn");
    }

    @Test
    void startupReconciliationWithPersistedExecutionPollsWithoutRedispatch() throws Exception {
        AgentRunEntity run = projectRun();
        run.setRemoteExecutionId("4ee2d311-b9da-4307-89b6-dd3110ef2057");
        run.setStatus(AgentRunStatus.RUNNING);
        when(agentRunRepository.findByExecutionTargetAndStatusInOrderByCreatedAtAsc(
                ExecutionTarget.REMOTE,
                AgentRunStatus.nonTerminalStatuses())).thenReturn(java.util.List.of(run));
        when(agentRunRepository.findById(run.getId())).thenReturn(Optional.of(run));
        when(agentRunRepository.findWithSessionById(run.getId())).thenReturn(Optional.of(run));
        when(agentRunRepository.save(any(AgentRunEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(workSessionRepository.findById(run.getSession().getId()))
                .thenReturn(Optional.of(run.getSession()));
        when(sessionTurnRepository.save(any(SessionTurnEntity.class))).thenAnswer(invocation -> {
            SessionTurnEntity turn = invocation.getArgument(0);
            turn.setId(901L);
            return turn;
        });
        when(client.get(run)).thenReturn(succeeded(run));

        assertEquals(1, coordinator.reconcileAfterStartup());
        waitForTerminal(run);

        assertEquals(AgentRunStatus.SUCCEEDED, run.getStatus());
        verify(progressService).append(run.getId(), AgentRunProgressCategory.RECONCILING);
        verify(client, never()).dispatch(any(), any());
        verify(client).get(run);
    }

    @Test
    void beautipsStartupReconciliationPollsPersistedExecutionWithoutRedispatch() throws Exception {
        AgentRunEntity run = beautipsRun();
        run.setRemoteExecutionId("4ee2d311-b9da-4307-89b6-dd3110ef2057");
        run.setStatus(AgentRunStatus.RUNNING);
        when(agentRunRepository.findByExecutionTargetAndStatusInOrderByCreatedAtAsc(
                ExecutionTarget.REMOTE,
                AgentRunStatus.nonTerminalStatuses())).thenReturn(java.util.List.of(run));
        when(agentRunRepository.findById(run.getId())).thenReturn(Optional.of(run));
        when(agentRunRepository.findWithSessionById(run.getId())).thenReturn(Optional.of(run));
        when(agentRunRepository.save(any(AgentRunEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(workSessionRepository.findById(run.getSession().getId()))
                .thenReturn(Optional.of(run.getSession()));
        when(sessionTurnRepository.save(any(SessionTurnEntity.class))).thenAnswer(invocation -> {
            SessionTurnEntity turn = invocation.getArgument(0);
            turn.setId(901L);
            return turn;
        });
        when(client.get(run)).thenReturn(succeeded(run));

        assertEquals(1, coordinator.reconcileAfterStartup());
        waitForTerminal(run);

        assertEquals(AgentRunStatus.SUCCEEDED, run.getStatus());
        verify(progressService).append(run.getId(), AgentRunProgressCategory.RECONCILING);
        verify(client, never()).dispatch(any(), any());
        verify(client).get(run);
    }

    @Test
    void exactProjectCancellationUsesPersistedExecutionAndDoesNotRedispatch() throws Exception {
        AgentRunEntity run = projectRun();
        run.setRemoteExecutionId("4ee2d311-b9da-4307-89b6-dd3110ef2057");
        run.setStatus(AgentRunStatus.RUNNING);
        when(agentRunRepository.findWithSessionById(run.getId())).thenReturn(Optional.of(run));
        when(agentRunRepository.findById(run.getId())).thenReturn(Optional.of(run));
        when(agentRunRepository.save(any(AgentRunEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        RemoteWorkerClient.Execution cancelled = execution(run, "CANCELLED", null);
        when(client.cancelExact(run)).thenReturn(cancelled);

        coordinator.requestCancellation(run.getId());
        waitForTerminal(run);

        assertEquals(AgentRunStatus.CANCELLED, run.getStatus());
        verify(client).cancelExact(run);
        verify(client, never()).dispatch(any(), any());
    }

    @Test
    void exactBeautipsCancellationUsesPersistedExecutionAndDoesNotRedispatch() throws Exception {
        AgentRunEntity run = beautipsRun();
        run.setRemoteExecutionId("4ee2d311-b9da-4307-89b6-dd3110ef2057");
        run.setStatus(AgentRunStatus.RUNNING);
        when(agentRunRepository.findWithSessionById(run.getId())).thenReturn(Optional.of(run));
        when(agentRunRepository.findById(run.getId())).thenReturn(Optional.of(run));
        when(agentRunRepository.save(any(AgentRunEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        RemoteWorkerClient.Execution cancelled = execution(run, "CANCELLED", null);
        when(client.cancelExact(run)).thenReturn(cancelled);

        coordinator.requestCancellation(run.getId());
        waitForTerminal(run);

        assertEquals(AgentRunStatus.CANCELLED, run.getStatus());
        verify(client).cancelExact(run);
        verify(client, never()).dispatch(any(), any());
    }

    @Test
    void boundedPartitionFailsPersistedProjectRunWithoutReplacementDispatch() throws Exception {
        AgentRunEntity run = projectRun();
        run.setRemoteExecutionId("4ee2d311-b9da-4307-89b6-dd3110ef2057");
        run.setStatus(AgentRunStatus.RUNNING);
        when(agentRunRepository.findWithSessionById(run.getId())).thenReturn(Optional.of(run));
        when(agentRunRepository.findById(run.getId())).thenReturn(Optional.of(run));
        when(agentRunRepository.save(any(AgentRunEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(client.get(run)).thenThrow(transportFailure(503));

        coordinator.dispatchAfterCommit(run.getId());
        waitForTerminal(run);

        assertEquals(AgentRunStatus.FAILED, run.getStatus());
        assertEquals(
                "Explicit operator review required; execution was not reassigned",
                run.getStatusReason());
        verify(mobilePushDispatchService, times(1)).notifyRunActionRequired(run);
        verify(mobilePushDispatchService, times(1)).notifyRunFailed(run);
        verify(progressService, times(1)).append(
                run.getId(), AgentRunProgressCategory.RECONCILING);
        verify(progressService, times(1)).append(
                run.getId(), AgentRunProgressCategory.FAILED);
        verify(client, never()).dispatch(any(), any());
    }

    @Test
    void boundedPartitionFailsPersistedBeautipsRunWithoutReplacementDispatch() throws Exception {
        AgentRunEntity run = beautipsRun();
        run.setRemoteExecutionId("4ee2d311-b9da-4307-89b6-dd3110ef2057");
        run.setStatus(AgentRunStatus.RUNNING);
        when(agentRunRepository.findWithSessionById(run.getId())).thenReturn(Optional.of(run));
        when(agentRunRepository.findById(run.getId())).thenReturn(Optional.of(run));
        when(agentRunRepository.save(any(AgentRunEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(client.get(run)).thenThrow(transportFailure(503));

        coordinator.dispatchAfterCommit(run.getId());
        waitForTerminal(run);

        assertEquals(AgentRunStatus.FAILED, run.getStatus());
        assertEquals(
                "Explicit operator review required; execution was not reassigned",
                run.getStatusReason());
        verify(client, never()).dispatch(any(), any());
    }

    @Test
    void ioFailureUsesBoundedReconciliationWithoutReplacementDispatch() throws Exception {
        AgentRunEntity run = projectRun();
        run.setRemoteExecutionId("4ee2d311-b9da-4307-89b6-dd3110ef2057");
        run.setStatus(AgentRunStatus.RUNNING);
        when(agentRunRepository.findWithSessionById(run.getId())).thenReturn(Optional.of(run));
        when(agentRunRepository.findById(run.getId())).thenReturn(Optional.of(run));
        when(agentRunRepository.save(any(AgentRunEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(client.get(run)).thenThrow(new RemoteWorkerException(
                "worker request failed", new java.io.IOException("connection unavailable")));

        coordinator.dispatchAfterCommit(run.getId());
        waitForTerminal(run);

        assertEquals(AgentRunStatus.FAILED, run.getStatus());
        verify(progressService, times(1)).append(
                run.getId(), AgentRunProgressCategory.RECONCILING);
        verify(client, never()).dispatch(any(), any());
    }

    @Test
    void failedPreAdmissionRetryIsProvenAbsentWithoutDispatch() {
        AgentRunEntity run = projectRun();
        run.setStatus(AgentRunStatus.FAILED);
        when(agentRunRepository.findWithSessionById(run.getId())).thenReturn(Optional.of(run));
        when(client.get(run)).thenThrow(new RemoteWorkerException("absent", 404));

        assertEquals(RemoteAgentRunCoordinator.RetryProof.ABSENT,
                coordinator.proveTerminalOrAbsent(run.getId()));
        verify(client, never()).dispatch(any(), any());
    }

    @Test
    void successfulRemoteResultCannotBeRetriedAsFailedWork() {
        AgentRunEntity run = projectRun();
        run.setStatus(AgentRunStatus.FAILED);
        run.setRemoteExecutionId("4ee2d311-b9da-4307-89b6-dd3110ef2057");
        when(agentRunRepository.findWithSessionById(run.getId())).thenReturn(Optional.of(run));
        when(client.inspectReconciliation(run)).thenReturn(succeeded(run));

        assertEquals(RemoteAgentRunCoordinator.RetryProof.STILL_LIVE,
                coordinator.proveTerminalOrAbsent(run.getId()));
        verify(client, never()).dispatch(any(), any());
    }

    @Test
    void exactReconciliationAppliesTerminalStateWithoutReplacementDispatch() {
        AgentRunEntity run = projectRun();
        run.setStatus(AgentRunStatus.RECONCILING);
        run.setRemoteExecutionId("4ee2d311-b9da-4307-89b6-dd3110ef2057");
        when(agentRunRepository.findWithSessionById(run.getId())).thenReturn(Optional.of(run));
        when(agentRunRepository.findById(run.getId())).thenReturn(Optional.of(run));
        when(agentRunRepository.save(any(AgentRunEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(client.inspectReconciliation(run)).thenReturn(execution(run, "CANCELLED", null));

        assertEquals(AgentRunStatus.CANCELLED, coordinator.requestReconciliation(run.getId()));
        verify(client).inspectReconciliation(run);
        verify(client, never()).dispatch(any(), any());
    }

    @Test
    void deterministicFourHundredFailureStopsWithoutWorkerUnavailableWindow() throws Exception {
        AgentRunEntity run = projectRun();
        properties.setPollInterval(Duration.ofMillis(1));
        when(agentRunRepository.findWithSessionById(run.getId())).thenReturn(Optional.of(run));
        when(agentRunRepository.findById(run.getId())).thenReturn(Optional.of(run));
        when(agentRunRepository.save(any(AgentRunEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(client.ensureWorkspace(run)).thenThrow(new RemoteWorkerException(
                "safe typed rejection",
                409,
                "ATENEA_WORKSPACE_ACTIVATION_REJECTED",
                RemoteWorkerFailureCategory.VALIDATION,
                false,
                AgentRunRecoveryNextAction.CONTACT_PLATFORM_ADMINISTRATOR,
                null));

        coordinator.dispatchAfterCommit(run.getId());
        waitForTerminal(run);

        assertEquals(AgentRunStatus.FAILED, run.getStatus());
        assertEquals("ATENEA_WORKSPACE_ACTIVATION_REJECTED", run.getFailureCode());
        assertEquals(AgentRunRecoveryNextAction.CONTACT_PLATFORM_ADMINISTRATOR,
                run.getRecoveryNextAction());
        assertEquals("Remote worker rejected the persisted admission request", run.getStatusReason());
        Thread.sleep(120);
        assertNull(run.getRemoteExecutionId());
        assertNull(run.getLeaseExpiresAt());
        assertNull(run.getRetryOfRun());
        verify(client, times(1)).ensureWorkspace(run);
        verify(progressService, never()).append(run.getId(), AgentRunProgressCategory.RECONCILING);
        verify(mobilePushDispatchService, never()).notifyRunActionRequired(run);
        verify(client, never()).dispatch(any(), any());
    }

    @Test
    void fourHundredTransportClaimFailsClosedWithoutWorkerUnavailableWindow() throws Exception {
        AgentRunEntity run = projectRun();
        properties.setPollInterval(Duration.ofMillis(1));
        when(agentRunRepository.findWithSessionById(run.getId())).thenReturn(Optional.of(run));
        when(agentRunRepository.findById(run.getId())).thenReturn(Optional.of(run));
        when(agentRunRepository.save(any(AgentRunEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(client.ensureWorkspace(run)).thenThrow(new RemoteWorkerException(
                "incoherent deterministic transport claim",
                403,
                "WORKSPACE_ACTIVATION_UNAVAILABLE",
                RemoteWorkerFailureCategory.TRANSPORT,
                true,
                AgentRunRecoveryNextAction.REQUEST_RECONCILIATION,
                null));

        coordinator.dispatchAfterCommit(run.getId());
        waitForTerminal(run);
        Thread.sleep(120);

        assertEquals(AgentRunStatus.FAILED, run.getStatus());
        assertEquals("REMOTE_WORKER_PROTOCOL_FAILURE", run.getFailureCode());
        assertEquals(AgentRunRecoveryNextAction.CONTACT_PLATFORM_ADMINISTRATOR,
                run.getRecoveryNextAction());
        assertEquals("Remote worker rejected the persisted admission request", run.getStatusReason());
        assertNull(run.getRemoteExecutionId());
        assertNull(run.getLeaseExpiresAt());
        assertNull(run.getRetryOfRun());
        verify(client, times(1)).ensureWorkspace(run);
        verify(progressService, never()).append(run.getId(), AgentRunProgressCategory.RECONCILING);
        verify(mobilePushDispatchService, never()).notifyRunActionRequired(run);
        verify(client, never()).dispatch(any(), any());
    }

    @Test
    void malformedWorkerErrorRequiresAdministratorReviewWithoutPolling() throws Exception {
        AgentRunEntity run = projectRun();
        when(agentRunRepository.findWithSessionById(run.getId())).thenReturn(Optional.of(run));
        when(agentRunRepository.findById(run.getId())).thenReturn(Optional.of(run));
        when(agentRunRepository.save(any(AgentRunEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(client.ensureWorkspace(run)).thenThrow(new RemoteWorkerException(
                "invalid worker error response",
                409,
                "REMOTE_WORKER_PROTOCOL_FAILURE",
                RemoteWorkerFailureCategory.PROTOCOL,
                false,
                AgentRunRecoveryNextAction.CONTACT_PLATFORM_ADMINISTRATOR,
                null));

        coordinator.dispatchAfterCommit(run.getId());
        waitForTerminal(run);
        Thread.sleep(120);

        assertEquals("REMOTE_WORKER_PROTOCOL_FAILURE", run.getFailureCode());
        assertEquals(AgentRunRecoveryNextAction.CONTACT_PLATFORM_ADMINISTRATOR,
                run.getRecoveryNextAction());
        assertNull(run.getRemoteExecutionId());
        assertNull(run.getLeaseExpiresAt());
        assertNull(run.getRetryOfRun());
        verify(client, times(1)).ensureWorkspace(run);
        verify(client, never()).dispatch(any(), any());
        verify(progressService, never()).append(run.getId(), AgentRunProgressCategory.RECONCILING);
    }

    @Test
    void exactOpenCapacityOwnerKeepsRunQueuedWithoutDispatch() throws Exception {
        AgentRunEntity run = projectRun();
        WorkSessionEntity blocker = capacityOwner(run, 42L, WorkSessionStatus.OPEN);
        RemoteWorkerException capacity = capacityFailure(blocker.getRemoteSessionId());
        when(agentRunRepository.findWithSessionById(run.getId())).thenReturn(Optional.of(run));
        when(agentRunRepository.findById(run.getId())).thenReturn(Optional.of(run));
        when(agentRunRepository.save(any(AgentRunEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(workSessionRepository.findByRemoteSessionId(blocker.getRemoteSessionId()))
                .thenReturn(Optional.of(blocker));
        when(client.ensureWorkspace(run)).thenThrow(capacity);

        coordinator.dispatchAfterCommit(run.getId());
        waitForStatusReason(run, "Waiting for exact active WorkSession capacity owner");

        assertEquals(AgentRunStatus.QUEUED, run.getStatus());
        assertNull(run.getFinishedAt());
        assertNull(run.getFailureCode());
        assertNull(run.getRecoveryNextAction());
        assertNull(run.getRecoveryBlockerWorkSessionId());
        verify(progressService, times(1)).append(run.getId(), AgentRunProgressCategory.QUEUED);
        verify(progressService, never()).append(run.getId(), AgentRunProgressCategory.RECONCILING);
        verify(client, never()).dispatch(any(), any());
    }

    @Test
    void exactClosedCapacityOwnerRequiresCloseReconciliationWithoutRedispatch() throws Exception {
        AgentRunEntity run = projectRun();
        WorkSessionEntity blocker = capacityOwner(run, 42L, WorkSessionStatus.CLOSED);
        when(agentRunRepository.findWithSessionById(run.getId())).thenReturn(Optional.of(run));
        when(agentRunRepository.findById(run.getId())).thenReturn(Optional.of(run));
        when(agentRunRepository.save(any(AgentRunEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(workSessionRepository.findByRemoteSessionId(blocker.getRemoteSessionId()))
                .thenReturn(Optional.of(blocker));
        when(agentRunRepository.existsBySessionIdAndStatusIn(
                blocker.getId(), AgentRunStatus.nonTerminalStatuses())).thenReturn(false);
        when(client.ensureWorkspace(run)).thenThrow(capacityFailure(blocker.getRemoteSessionId()));

        coordinator.dispatchAfterCommit(run.getId());
        waitForTerminal(run);

        assertEquals("CLOSED_SESSION_OWNS_CAPACITY", run.getFailureCode());
        assertEquals(AgentRunRecoveryNextAction.RECONCILE_REMOTE_CLOSE,
                run.getRecoveryNextAction());
        assertEquals(blocker.getId(), run.getRecoveryBlockerWorkSessionId());
        verify(progressService, never()).append(run.getId(), AgentRunProgressCategory.RECONCILING);
        verify(client, never()).dispatch(any(), any());
    }

    @Test
    void foreignCapacityOwnerFailsClosedForAdministratorReview() throws Exception {
        AgentRunEntity run = projectRun();
        WorkSessionEntity blocker = capacityOwner(run, 42L, WorkSessionStatus.CLOSED);
        blocker.getProject().setId(999L);
        when(agentRunRepository.findWithSessionById(run.getId())).thenReturn(Optional.of(run));
        when(agentRunRepository.findById(run.getId())).thenReturn(Optional.of(run));
        when(agentRunRepository.save(any(AgentRunEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(workSessionRepository.findByRemoteSessionId(blocker.getRemoteSessionId()))
                .thenReturn(Optional.of(blocker));
        when(client.ensureWorkspace(run)).thenThrow(capacityFailure(blocker.getRemoteSessionId()));

        coordinator.dispatchAfterCommit(run.getId());
        waitForTerminal(run);

        assertEquals("CAPACITY_OWNER_UNVERIFIED", run.getFailureCode());
        assertEquals(AgentRunRecoveryNextAction.CONTACT_PLATFORM_ADMINISTRATOR,
                run.getRecoveryNextAction());
        assertNull(run.getRecoveryBlockerWorkSessionId());
        verify(agentRunRepository, never()).existsBySessionIdAndStatusIn(any(), any());
        verify(client, never()).dispatch(any(), any());
    }

    @Test
    void unknownCapacityOwnerRequiresAdministratorReviewWithoutDispatch() throws Exception {
        AgentRunEntity run = projectRun();
        UUID unknown = UUID.fromString("fe5f567d-3f2b-4a88-9389-89fe113aba74");
        when(agentRunRepository.findWithSessionById(run.getId())).thenReturn(Optional.of(run));
        when(agentRunRepository.findById(run.getId())).thenReturn(Optional.of(run));
        when(agentRunRepository.save(any(AgentRunEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(workSessionRepository.findByRemoteSessionId(unknown)).thenReturn(Optional.empty());
        when(client.ensureWorkspace(run)).thenThrow(capacityFailure(unknown));

        coordinator.dispatchAfterCommit(run.getId());
        waitForTerminal(run);

        assertEquals("CAPACITY_OWNER_UNVERIFIED", run.getFailureCode());
        assertEquals(AgentRunRecoveryNextAction.CONTACT_PLATFORM_ADMINISTRATOR,
                run.getRecoveryNextAction());
        assertNull(run.getRemoteExecutionId());
        assertNull(run.getLeaseExpiresAt());
        assertNull(run.getRetryOfRun());
        verify(client, times(1)).ensureWorkspace(run);
        verify(client, never()).dispatch(any(), any());
    }

    @Test
    void mismatchedWorkerOwnershipResponseFailsForAdministratorReview() throws Exception {
        AgentRunEntity run = projectRun();
        RemoteWorkerClient.Execution accepted = execution(run, "RUNNING", null);
        RemoteWorkerClient.Execution mismatched = new RemoteWorkerClient.Execution(
                accepted.dispatchId(),
                accepted.executionId(),
                accepted.sessionId(),
                "remote:foreign:work-session:" + run.getRemoteSessionId(),
                accepted.workloadClass(),
                accepted.leaseGeneration(),
                accepted.status(),
                accepted.statusReason(),
                accepted.revision(),
                accepted.progress(),
                accepted.createdAt(),
                accepted.startedAt(),
                accepted.finishedAt(),
                accepted.updatedAt(),
                accepted.result(),
                accepted.progressEvents());
        when(agentRunRepository.findWithSessionById(run.getId())).thenReturn(Optional.of(run));
        when(agentRunRepository.findById(run.getId())).thenReturn(Optional.of(run));
        when(agentRunRepository.save(any(AgentRunEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(client.dispatch(run, "First managed turn")).thenReturn(mismatched);

        coordinator.dispatchAfterCommit(run.getId());
        waitForTerminal(run);

        assertEquals("REMOTE_WORKER_PROTOCOL_FAILURE", run.getFailureCode());
        assertEquals(AgentRunRecoveryNextAction.CONTACT_PLATFORM_ADMINISTRATOR,
                run.getRecoveryNextAction());
        assertNull(run.getRemoteExecutionId());
        assertNull(run.getLeaseExpiresAt());
        assertNull(run.getRetryOfRun());
        verify(client, times(1)).dispatch(run, "First managed turn");
        verify(progressService, never()).append(run.getId(), AgentRunProgressCategory.RECONCILING);
    }

    private AgentRunEntity projectRun() {
        UUID remoteSessionId = UUID.fromString("4bb26a65-0a0a-4ae0-b8e0-b41e03a695bf");
        ProjectEntity project = new ProjectEntity();
        project.setId(7L);
        project.setName(ProjectCodexIdentity.PROJECT_NAME);
        project.setRepoPath(ProjectCodexIdentity.REPO_PATH);
        WorkSessionEntity session = new WorkSessionEntity();
        session.setId(41L);
        session.setProject(project);
        session.setStatus(WorkSessionStatus.OPEN);
        session.setExecutionTarget(ExecutionTarget.REMOTE);
        session.setSelectedWorkerId("ax42-01");
        session.setBaseBranch(ProjectCodexIdentity.BRANCH);
        session.setRemoteSessionId(remoteSessionId);
        session.setWorkspaceIdentity("remote:ax42-01:work-session:" + remoteSessionId);
        session.setCanonicalSourceRef("refs/heads/" + ProjectCodexIdentity.BRANCH);
        session.setCanonicalSourceCommit(TEST_CANONICAL_COMMIT);
        session.setCanonicalSourceObservationSha256("2".repeat(64));
        session.setCanonicalSourceObservedAt(Instant.now());
        SessionTurnEntity origin = new SessionTurnEntity();
        origin.setId(101L);
        origin.setSession(session);
        origin.setActor(SessionTurnActor.OPERATOR);
        origin.setMessageText("First managed turn");
        AgentRunEntity run = new AgentRunEntity();
        run.setId(55L);
        run.setSession(session);
        run.setOriginTurn(origin);
        run.setStatus(AgentRunStatus.QUEUED);
        run.setExecutionTarget(ExecutionTarget.REMOTE);
        run.setSelectedWorkerId("ax42-01");
        run.setRemoteSessionId(remoteSessionId);
        run.setWorkspaceIdentity(session.getWorkspaceIdentity());
        run.setDispatchId(UUID.fromString("3bb4ab61-6439-452d-a1cc-90e2eb9d9310"));
        run.setWorkloadClass(WorkloadClass.NORMAL);
        run.setLeaseGeneration(1);
        run.setLifecycleRevision(0);
        run.setWorkloadKind(ProjectCodexIdentity.WORKLOAD_KIND);
        run.setProjectIdentity(ProjectCodexIdentity.PROJECT_IDENTITY);
        run.setRepositoryUrl(ProjectCodexIdentity.REPOSITORY);
        run.setRepositoryBranch(ProjectCodexIdentity.BRANCH);
        run.setRepositoryCommit(TEST_CANONICAL_COMMIT);
        run.setManifestSha256(ProjectCodexIdentity.MANIFEST_SHA256);
        ReviewedInstructionBundleIdentity.apply(
                run, ProjectCodexIdentity.PROJECT_IDENTITY);
        return run;
    }

    private AgentRunEntity changeBoundRun() {
        AgentRunEntity run = projectRun();
        UUID changeKey = UUID.fromString("df99f1a1-1f14-4ca8-a405-58cd5b91bf2f");
        String workspaceIdentity = "remote:ax42-01:change:" + changeKey;
        run.getSession().setWorkspaceIdentity(workspaceIdentity);
        run.getSession().setWorkspaceBranch("atenea/change-" + changeKey);
        run.getSession().setRemoteWorkloadKind(ProjectCodexIdentity.WORKLOAD_KIND);
        DevelopmentChangeEntity change = new DevelopmentChangeEntity();
        change.setId(91L);
        change.setChangeKey(changeKey);
        change.setProject(run.getSession().getProject());
        change.setStatus(DevelopmentChangeStatus.OPEN);
        change.setBaseRef("refs/heads/main");
        change.setBaseCommit(TEST_CANONICAL_COMMIT);
        change.setObservedCanonicalCommit(TEST_CANONICAL_COMMIT);
        change.setWorkspaceBranch("atenea/change-" + changeKey);
        change.setWorkspaceIdentity(workspaceIdentity);
        change.setSelectedWorkerId(ProjectCodexIdentity.WORKER_ID);
        change.setSourceRevision(3);
        change.setSourceFingerprintSha256("3".repeat(64));
        change.setSourceState(DevelopmentChangeSourceState.DIRTY);
        change.setWorkspaceState(DevelopmentChangeWorkspaceState.READY);
        change.setWorkspaceOperationRevision(2);
        change.setWorkspaceObservationSha256("4".repeat(64));
        change.setWorkspaceOwnershipFingerprintSha256("5".repeat(64));
        change.setWorkspaceUpdatedAt(Instant.parse("2026-08-23T12:00:00Z"));
        run.getSession().setDevelopmentChange(change);
        run.setWorkspaceIdentity(workspaceIdentity);
        run.setWorkloadKind(ProjectCodexIdentity.CHANGE_WORKLOAD_KIND);
        run.setDevelopmentChangeKey(changeKey);
        run.setChangeBaseCommit(TEST_CANONICAL_COMMIT);
        run.setChangeExpectedCanonicalCommit(TEST_CANONICAL_COMMIT);
        run.setChangeSourceRevision(3L);
        run.setChangeSourceFingerprintSha256("3".repeat(64));
        run.setChangeWorkspaceOwnershipFingerprintSha256("5".repeat(64));
        run.setCodexModelId("gpt-5.6-sol");
        run.setCodexReasoningEffort(
                com.atenea.persistence.worksession.CodexReasoningEffort.HIGH);
        run.setCodexCatalogRevision("6".repeat(64));
        run.setCodexVersion("0.145.0");
        return run;
    }

    private WorkSessionEntity capacityOwner(
            AgentRunEntity run,
            Long id,
            WorkSessionStatus status
    ) {
        UUID remoteSessionId = UUID.fromString("fe5f567d-3f2b-4a88-9389-89fe113aba74");
        ProjectEntity project = new ProjectEntity();
        project.setId(run.getSession().getProject().getId());
        project.setName(run.getSession().getProject().getName());
        project.setRepoPath(run.getSession().getProject().getRepoPath());
        WorkSessionEntity blocker = new WorkSessionEntity();
        blocker.setId(id);
        blocker.setProject(project);
        blocker.setStatus(status);
        blocker.setExecutionTarget(ExecutionTarget.REMOTE);
        blocker.setSelectedWorkerId(run.getSelectedWorkerId());
        blocker.setRemoteSessionId(remoteSessionId);
        blocker.setWorkspaceIdentity("remote:" + run.getSelectedWorkerId()
                + ":work-session:" + remoteSessionId);
        return blocker;
    }

    private RemoteWorkerException capacityFailure(UUID blockerSessionId) {
        return new RemoteWorkerException(
                "safe capacity rejection",
                409,
                "NORMAL_CAPACITY_EXHAUSTED",
                RemoteWorkerFailureCategory.CAPACITY,
                true,
                AgentRunRecoveryNextAction.WAIT,
                blockerSessionId);
    }

    private RemoteWorkerException transportFailure(int statusCode) {
        return new RemoteWorkerException(
                "safe transport failure",
                statusCode,
                "WORKSPACE_ACTIVATION_UNAVAILABLE",
                RemoteWorkerFailureCategory.TRANSPORT,
                true,
                AgentRunRecoveryNextAction.REQUEST_RECONCILIATION,
                null);
    }

    private AgentRunEntity beautipsRun() {
        AgentRunEntity run = projectRun();
        ProjectEntity project = run.getSession().getProject();
        project.setName(BeautipsProjectCodexIdentity.PROJECT_NAME);
        project.setRepoPath(BeautipsProjectCodexIdentity.REPO_PATH);
        project.setDefaultBaseBranch(BeautipsProjectCodexIdentity.BRANCH);
        run.getSession().setBaseBranch(BeautipsProjectCodexIdentity.BRANCH);
        run.setProjectIdentity(BeautipsProjectCodexIdentity.PROJECT_IDENTITY);
        run.setRepositoryUrl(BeautipsProjectCodexIdentity.REPOSITORY);
        run.setRepositoryBranch(BeautipsProjectCodexIdentity.BRANCH);
        run.setRepositoryCommit(BeautipsProjectCodexIdentity.COMMIT);
        run.setManifestSha256(BeautipsProjectCodexIdentity.MANIFEST_SHA256);
        ReviewedInstructionBundleIdentity.apply(
                run, BeautipsProjectCodexIdentity.PROJECT_IDENTITY);
        return run;
    }

    private RemoteWorkerClient.Execution succeeded(AgentRunEntity run) {
        return execution(
                run,
                "SUCCEEDED",
                new RemoteWorkerClient.Result(
                        "bd312352-28b8-44d0-835f-e1afc5181cc9",
                        "37f0d8f6-4b7f-44cb-b75c-105f51773283",
                        "Managed answer",
                        "project-codex-v1 completed"));
    }

    private RemoteWorkerClient.Execution succeededV4(AgentRunEntity run) {
        return execution(
                run,
                "SUCCEEDED",
                new RemoteWorkerClient.Result(
                        "bd312352-28b8-44d0-835f-e1afc5181cc9",
                        "37f0d8f6-4b7f-44cb-b75c-105f51773283",
                        "Managed answer",
                        "project-codex-v4 completed",
                        run.getCodexModelId(),
                        run.getCodexReasoningEffort().canonicalValue(),
                        run.getCodexCatalogRevision(),
                        run.getCodexVersion(),
                        new RemoteWorkerClient.SourceIdentity(
                                run.getDevelopmentChangeKey().toString(),
                                run.getSession().getId(),
                                run.getRemoteSessionId().toString(),
                                run.getWorkspaceIdentity(),
                                "4ee2d311-b9da-4307-89b6-dd3110ef2057",
                                "6".repeat(64),
                                "7".repeat(64),
                                true)));
    }

    private RemoteWorkerClient.Execution execution(
            AgentRunEntity run,
            String status,
            RemoteWorkerClient.Result result
    ) {
        Instant now = Instant.parse("2026-07-29T06:00:00Z");
        return new RemoteWorkerClient.Execution(
                run.getDispatchId().toString(),
                run.getRemoteExecutionId() == null
                        ? "4ee2d311-b9da-4307-89b6-dd3110ef2057"
                        : run.getRemoteExecutionId(),
                run.getRemoteSessionId().toString(),
                run.getWorkspaceIdentity(),
                "NORMAL",
                1,
                status,
                "SUCCEEDED".equals(status)
                        ? "Exact project Codex execution completed"
                        : "Exact execution cancelled",
                4,
                100,
                now,
                now,
                now,
                now,
                result,
                progress(run, status));
    }

    private java.util.List<RemoteWorkerClient.ProgressEvent> progress(
            AgentRunEntity run,
            String status
    ) {
        String executionId = run.getRemoteExecutionId() == null
                ? "4ee2d311-b9da-4307-89b6-dd3110ef2057"
                : run.getRemoteExecutionId();
        Instant now = Instant.parse("2026-07-29T06:00:00Z");
        AgentRunProgressCategory terminal = switch (status) {
            case "SUCCEEDED" -> AgentRunProgressCategory.COMPLETED;
            case "FAILED" -> AgentRunProgressCategory.FAILED;
            case "CANCELLED" -> AgentRunProgressCategory.CANCELLED;
            default -> null;
        };
        java.util.List<RemoteWorkerClient.ProgressEvent> events = new java.util.ArrayList<>();
        events.add(progressEvent(run, executionId, 1, AgentRunProgressCategory.ACCEPTED, now));
        events.add(progressEvent(run, executionId, 2, AgentRunProgressCategory.CODEX_STARTED, now));
        if (terminal != null) {
            events.add(progressEvent(run, executionId, 3, terminal, now));
        }
        return java.util.List.copyOf(events);
    }

    private RemoteWorkerClient.ProgressEvent progressEvent(
            AgentRunEntity run,
            String executionId,
            long sequence,
            AgentRunProgressCategory category,
            Instant occurredAt
    ) {
        String message = switch (category) {
            case ACCEPTED -> "Execution request accepted.";
            case CODEX_STARTED -> "Codex started the accepted turn.";
            case COMPLETED -> "Execution completed.";
            case FAILED -> "Execution failed.";
            case CANCELLED -> "Execution cancelled.";
            default -> throw new IllegalArgumentException(category.name());
        };
        return new RemoteWorkerClient.ProgressEvent(
                run.getDispatchId().toString(), executionId, sequence,
                category.name(), occurredAt, message);
    }

    private void waitForTerminal(AgentRunEntity run) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (System.nanoTime() < deadline && !run.getStatus().isTerminal()) {
            Thread.sleep(10);
        }
        assertNotNull(run.getFinishedAt());
    }

    private void waitForRemoteExecution(AgentRunEntity run) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (System.nanoTime() < deadline && run.getRemoteExecutionId() == null) {
            Thread.sleep(10);
        }
        assertNotNull(run.getRemoteExecutionId());
    }

    private void waitForStatusReason(AgentRunEntity run, String expected) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (System.nanoTime() < deadline && !expected.equals(run.getStatusReason())) {
            Thread.sleep(10);
        }
        assertEquals(expected, run.getStatusReason());
    }
}
