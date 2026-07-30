package com.atenea.remoteworker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.atenea.persistence.project.ProjectEntity;
import com.atenea.persistence.worksession.AgentRunEntity;
import com.atenea.persistence.worksession.AgentRunRepository;
import com.atenea.persistence.worksession.AgentRunStatus;
import com.atenea.persistence.worksession.ExecutionTarget;
import com.atenea.persistence.worksession.SessionTurnActor;
import com.atenea.persistence.worksession.SessionTurnEntity;
import com.atenea.persistence.worksession.SessionTurnRepository;
import com.atenea.persistence.worksession.WorkSessionEntity;
import com.atenea.persistence.worksession.WorkSessionRepository;
import com.atenea.persistence.worksession.WorkloadClass;
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
    private RemoteWorkerClient client;
    private RemoteWorkerProperties properties;
    private RemoteAgentRunCoordinator coordinator;

    @BeforeEach
    void setUp() {
        agentRunRepository = mock(AgentRunRepository.class);
        workSessionRepository = mock(WorkSessionRepository.class);
        sessionTurnRepository = mock(SessionTurnRepository.class);
        client = mock(RemoteWorkerClient.class);
        properties = new RemoteWorkerProperties();
        properties.setPollInterval(Duration.ofMillis(10));
        properties.setReconciliationTimeout(Duration.ofMillis(40));
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(mock(TransactionStatus.class));
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
                client,
                properties,
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
        verify(client, times(1)).ensureWorkspace(run);
        verify(client, times(1)).dispatch(run, "First managed turn");
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
        when(client.cancel(run)).thenReturn(cancelled);

        coordinator.requestCancellation(run.getId());
        waitForTerminal(run);

        assertEquals(AgentRunStatus.CANCELLED, run.getStatus());
        verify(client).cancel(run);
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
        when(client.cancel(run)).thenReturn(cancelled);

        coordinator.requestCancellation(run.getId());
        waitForTerminal(run);

        assertEquals(AgentRunStatus.CANCELLED, run.getStatus());
        verify(client).cancel(run);
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
        when(client.get(run)).thenThrow(new RemoteWorkerException("partition", 503));

        coordinator.dispatchAfterCommit(run.getId());
        waitForTerminal(run);

        assertEquals(AgentRunStatus.FAILED, run.getStatus());
        assertEquals(
                "Explicit operator review required; execution was not reassigned",
                run.getStatusReason());
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
        when(client.get(run)).thenThrow(new RemoteWorkerException("partition", 503));

        coordinator.dispatchAfterCommit(run.getId());
        waitForTerminal(run);

        assertEquals(AgentRunStatus.FAILED, run.getStatus());
        assertEquals(
                "Explicit operator review required; execution was not reassigned",
                run.getStatusReason());
        verify(client, never()).dispatch(any(), any());
    }

    private AgentRunEntity projectRun() {
        UUID remoteSessionId = UUID.fromString("4bb26a65-0a0a-4ae0-b8e0-b41e03a695bf");
        ProjectEntity project = new ProjectEntity();
        project.setName(ProjectCodexIdentity.PROJECT_NAME);
        project.setRepoPath(ProjectCodexIdentity.REPO_PATH);
        WorkSessionEntity session = new WorkSessionEntity();
        session.setId(41L);
        session.setProject(project);
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
        return run;
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
                result);
    }

    private void waitForTerminal(AgentRunEntity run) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (System.nanoTime() < deadline && !run.getStatus().isTerminal()) {
            Thread.sleep(10);
        }
        assertNotNull(run.getFinishedAt());
    }
}
