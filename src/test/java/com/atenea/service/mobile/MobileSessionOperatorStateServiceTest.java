package com.atenea.service.mobile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.atenea.api.mobile.MobileSessionOperatorState;
import com.atenea.api.mobile.MobileSessionOperatorStateResponse;
import com.atenea.api.mobile.MobileSessionPrimaryAction;
import com.atenea.api.worksession.SessionOperationalSnapshotResponse;
import com.atenea.api.worksession.WorkSessionConversationViewResponse;
import com.atenea.api.worksession.WorkSessionOperationalState;
import com.atenea.api.worksession.WorkSessionResponse;
import com.atenea.api.worksession.WorkSessionViewLatestRunResponse;
import com.atenea.api.worksession.WorkSessionViewResponse;
import com.atenea.persistence.auth.CodexOperationsRole;
import com.atenea.persistence.project.ProjectEntity;
import com.atenea.persistence.worksession.AgentRunEntity;
import com.atenea.persistence.worksession.AgentRunRecoveryNextAction;
import com.atenea.persistence.worksession.AgentRunRepository;
import com.atenea.persistence.worksession.AgentRunStatus;
import com.atenea.persistence.worksession.ExecutionTarget;
import com.atenea.persistence.worksession.RemoteCloseState;
import com.atenea.persistence.worksession.WorkSessionEntity;
import com.atenea.persistence.worksession.WorkSessionPullRequestStatus;
import com.atenea.persistence.worksession.WorkSessionRepository;
import com.atenea.persistence.worksession.WorkSessionStatus;
import com.atenea.persistence.worksession.WorkloadClass;
import com.atenea.remoteworker.ProjectCodexIdentity;
import com.atenea.remoteworker.RemoteWorkerClient;
import com.atenea.remoteworker.RemoteWorkerProperties;
import com.atenea.remoteworker.ReviewedInstructionBundleIdentity;
import com.atenea.service.worksession.AgentRunService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MobileSessionOperatorStateServiceTest {

    @Mock
    private AgentRunRepository agentRunRepository;

    @Mock
    private WorkSessionRepository workSessionRepository;

    @Mock
    private AgentRunService agentRunService;

    @Mock
    private RemoteWorkerProperties remoteWorkerProperties;

    @Mock
    private RemoteWorkerClient remoteWorkerClient;

    private MobileSessionOperatorStateService service;

    @BeforeEach
    void setUp() {
        service = new MobileSessionOperatorStateService(
                agentRunRepository,
                workSessionRepository,
                agentRunService,
                remoteWorkerProperties,
                remoteWorkerClient);
    }

    @Test
    void remoteCloseReconciliationIsStateFirstAndOffersNoReplacementMutation() {
        when(remoteWorkerProperties.isRemoteCloseReconciliationEnabledFor(
                ProjectCodexIdentity.PROJECT_IDENTITY)).thenReturn(true);

        MobileSessionOperatorStateResponse response = service.build(conversation(
                WorkSessionStatus.CLOSING,
                RemoteCloseState.RECONCILING,
                null,
                false));

        assertTrue(response.surfaceEnabled());
        assertEquals(MobileSessionOperatorState.CLOSING_REMOTE, response.state());
        assertEquals("Cerrando · liberando recursos remotos", response.title());
        assertEquals(MobileSessionPrimaryAction.RECONCILE_REMOTE_CLOSE,
                response.primaryAction());
        assertTrue(response.primaryActionAvailable());
        assertEquals(CodexOperationsRole.ROUTINE_OPERATOR, response.requiredRole());
    }

    @Test
    void legacyCloseActionStaysUnavailableWhileProjectGateIsOff() {
        when(remoteWorkerProperties.isRemoteCloseReconciliationEnabledFor(
                ProjectCodexIdentity.PROJECT_IDENTITY)).thenReturn(false);

        MobileSessionOperatorStateResponse response = service.build(conversation(
                WorkSessionStatus.CLOSED,
                RemoteCloseState.UNVERIFIED_LEGACY,
                null,
                false));

        assertFalse(response.surfaceEnabled());
        assertEquals(MobileSessionOperatorState.LEGACY_CLOSE_REQUIRED, response.state());
        assertEquals(MobileSessionPrimaryAction.RECONCILE_REMOTE_CLOSE,
                response.primaryAction());
        assertFalse(response.primaryActionAvailable());
        assertEquals(CodexOperationsRole.PLATFORM_ADMINISTRATOR,
                response.requiredRole());
    }

    @Test
    void exactClosedOwnerMakesReconciliationTheSingleEnabledAction() {
        WorkSessionViewLatestRunResponse latestRun = closedOwnerRun();
        AgentRunEntity run = blockedRun();
        WorkSessionEntity blocker = blocker(RemoteCloseState.UNVERIFIED_LEGACY);
        when(agentRunRepository.findById(96L)).thenReturn(Optional.of(run));
        when(workSessionRepository.findWithProjectById(16L))
                .thenReturn(Optional.of(blocker));
        when(agentRunService.isRemoteRetryEligible(96L)).thenReturn(false);
        when(remoteWorkerProperties.isRemoteCloseReconciliationEnabledFor(
                ProjectCodexIdentity.PROJECT_IDENTITY)).thenReturn(true);

        MobileSessionOperatorStateResponse response = service.build(conversation(
                WorkSessionStatus.OPEN,
                RemoteCloseState.NOT_STARTED,
                latestRun,
                false));

        assertTrue(response.surfaceEnabled());
        assertEquals(MobileSessionOperatorState.CLOSED_OWNER_BLOCKS_CAPACITY,
                response.state());
        assertEquals("Bloqueada por una sesión cerrada", response.title());
        assertEquals(MobileSessionPrimaryAction.RECONCILE_REMOTE_CLOSE,
                response.primaryAction());
        assertTrue(response.primaryActionAvailable());
        assertEquals(16L, response.targetWorkSessionId());
        assertEquals(96L, response.targetAgentRunId());
    }

    @Test
    void exactReleasedReceiptReplacesReconciliationWithExplicitRetry() {
        WorkSessionViewLatestRunResponse latestRun = closedOwnerRun();
        AgentRunEntity run = blockedRun();
        when(agentRunRepository.findById(96L)).thenReturn(Optional.of(run));
        when(workSessionRepository.findWithProjectById(16L))
                .thenReturn(Optional.of(blocker(RemoteCloseState.RELEASED)));
        when(agentRunService.isRemoteRetryEligible(96L)).thenReturn(true);

        MobileSessionOperatorStateResponse response = service.build(conversation(
                WorkSessionStatus.OPEN,
                RemoteCloseState.NOT_STARTED,
                latestRun,
                false));

        assertEquals(MobileSessionOperatorState.CAPACITY_RELEASED, response.state());
        assertEquals(MobileSessionPrimaryAction.RETRY_AGENT_RUN,
                response.primaryAction());
        assertEquals("Reintentar tarea", response.primaryActionLabel());
        assertTrue(response.primaryActionAvailable());
        assertEquals(CodexOperationsRole.ROUTINE_OPERATOR, response.requiredRole());
    }

    @Test
    void ambiguousOwnershipNeverBecomesWorkerUnavailabilityOrRetry() {
        WorkSessionViewLatestRunResponse latestRun = latestRun(
                "AMBIGUOUS_RUNTIME_OWNERSHIP",
                AgentRunRecoveryNextAction.CONTACT_PLATFORM_ADMINISTRATOR);
        AgentRunEntity run = new AgentRunEntity();
        run.setId(96L);
        run.setStatus(AgentRunStatus.FAILED);
        run.setFailureCode("AMBIGUOUS_RUNTIME_OWNERSHIP");
        run.setRecoveryNextAction(
                AgentRunRecoveryNextAction.CONTACT_PLATFORM_ADMINISTRATOR);
        when(agentRunRepository.findById(96L)).thenReturn(Optional.of(run));

        MobileSessionOperatorStateResponse response = service.build(conversation(
                WorkSessionStatus.OPEN,
                RemoteCloseState.NOT_STARTED,
                latestRun,
                false));

        assertEquals(MobileSessionOperatorState.OWNERSHIP_REVIEW_REQUIRED,
                response.state());
        assertEquals(MobileSessionPrimaryAction.CONTACT_PLATFORM_ADMINISTRATOR,
                response.primaryAction());
        assertFalse(response.primaryActionAvailable());
        assertEquals("La propiedad remota no coincide de forma inequívoca.",
                response.blocker());
    }

    @Test
    void historicalPreV63FailureUsesExactReadOnlyOwnerDiagnosis() {
        WorkSessionEntity current = exactRemoteSession(
                17L,
                "18c00753-6080-42f7-ac05-18c47b236cac",
                WorkSessionStatus.OPEN,
                RemoteCloseState.NOT_STARTED,
                Instant.parse("2026-08-03T10:03:00Z"));
        WorkSessionEntity predecessor = exactRemoteSession(
                16L,
                "7151dce0-69ab-4614-86e4-f93f1af825e4",
                WorkSessionStatus.CLOSED,
                RemoteCloseState.UNVERIFIED_LEGACY,
                Instant.parse("2026-08-03T10:00:00Z"));
        clearCanonicalSourceObservation(predecessor);
        AgentRunEntity run = historicalRun(current);
        when(agentRunRepository.findById(96L)).thenReturn(Optional.of(run));
        when(workSessionRepository.findWithProjectById(17L))
                .thenReturn(Optional.of(current));
        when(workSessionRepository
                .findFirstByProjectIdAndStatusAndCreatedAtBeforeOrderByCreatedAtDesc(
                        1L, WorkSessionStatus.CLOSED, current.getCreatedAt()))
                .thenReturn(Optional.of(predecessor));
        when(workSessionRepository
                .findFirstByProjectIdAndCreatedAtAfterOrderByCreatedAtAscIdAsc(
                        1L, predecessor.getCreatedAt()))
                .thenReturn(Optional.of(current));
        when(remoteWorkerClient.diagnoseWorkspaceCapacityOwner(predecessor, current))
                .thenReturn(new RemoteWorkerClient.WorkspaceCapacityOwner(
                        "project-workspace-capacity-owner-v1",
                        "OWNED",
                        predecessor.getRemoteSessionId().toString(),
                        predecessor.getWorkspaceIdentity(),
                        "atenea",
                        "ax42-01",
                        "1".repeat(64),
                        "2".repeat(64),
                        false));
        when(remoteWorkerProperties.isRemoteCloseReconciliationEnabledFor("atenea"))
                .thenReturn(true);

        MobileSessionOperatorStateResponse response = service.build(conversation(
                WorkSessionStatus.OPEN,
                RemoteCloseState.NOT_STARTED,
                latestRun(null, null),
                false));

        assertEquals(MobileSessionOperatorState.CLOSED_OWNER_BLOCKS_CAPACITY,
                response.state());
        assertEquals(MobileSessionPrimaryAction.RECONCILE_REMOTE_CLOSE,
                response.primaryAction());
        assertEquals(16L, response.targetWorkSessionId());
        assertEquals(96L, response.targetAgentRunId());
    }

    @Test
    void historicalCanonicalWitnessMustBeTheImmediateNextSession() {
        WorkSessionEntity current = exactRemoteSession(
                17L,
                "18c00753-6080-42f7-ac05-18c47b236cac",
                WorkSessionStatus.OPEN,
                RemoteCloseState.NOT_STARTED,
                Instant.parse("2026-08-03T10:03:00Z"));
        WorkSessionEntity predecessor = exactRemoteSession(
                16L,
                "7151dce0-69ab-4614-86e4-f93f1af825e4",
                WorkSessionStatus.CLOSED,
                RemoteCloseState.UNVERIFIED_LEGACY,
                Instant.parse("2026-08-03T10:00:00Z"));
        clearCanonicalSourceObservation(predecessor);
        WorkSessionEntity intermediate = exactRemoteSession(
                99L,
                "0db6b59e-63d3-44c5-8635-2b74353e93b3",
                WorkSessionStatus.OPEN,
                RemoteCloseState.NOT_STARTED,
                Instant.parse("2026-08-03T10:01:00Z"));
        AgentRunEntity run = historicalRun(current);
        when(agentRunRepository.findById(96L)).thenReturn(Optional.of(run));
        when(workSessionRepository.findWithProjectById(17L))
                .thenReturn(Optional.of(current));
        when(workSessionRepository
                .findFirstByProjectIdAndStatusAndCreatedAtBeforeOrderByCreatedAtDesc(
                        1L, WorkSessionStatus.CLOSED, current.getCreatedAt()))
                .thenReturn(Optional.of(predecessor));
        when(workSessionRepository
                .findFirstByProjectIdAndCreatedAtAfterOrderByCreatedAtAscIdAsc(
                        1L, predecessor.getCreatedAt()))
                .thenReturn(Optional.of(intermediate));
        when(remoteWorkerProperties.isRemoteCloseReconciliationEnabledFor("atenea"))
                .thenReturn(true);

        MobileSessionOperatorStateResponse response = service.build(conversation(
                WorkSessionStatus.OPEN,
                RemoteCloseState.NOT_STARTED,
                latestRun(null, null),
                false));

        assertEquals(MobileSessionOperatorState.DEFAULT, response.state());
        assertFalse(response.primaryActionAvailable());
        verifyNoInteractions(remoteWorkerClient);
    }

    @Test
    void historicalCompatibilityRejectsPartialCanonicalHistoryWithoutWorkerIo() {
        WorkSessionEntity current = exactRemoteSession(
                17L,
                "18c00753-6080-42f7-ac05-18c47b236cac",
                WorkSessionStatus.OPEN,
                RemoteCloseState.NOT_STARTED,
                Instant.parse("2026-08-03T10:03:00Z"));
        WorkSessionEntity predecessor = exactRemoteSession(
                16L,
                "7151dce0-69ab-4614-86e4-f93f1af825e4",
                WorkSessionStatus.CLOSED,
                RemoteCloseState.UNVERIFIED_LEGACY,
                Instant.parse("2026-08-03T10:00:00Z"));
        predecessor.setCanonicalSourceObservationSha256(null);
        AgentRunEntity run = historicalRun(current);
        when(agentRunRepository.findById(96L)).thenReturn(Optional.of(run));
        when(workSessionRepository.findWithProjectById(17L))
                .thenReturn(Optional.of(current));
        when(workSessionRepository
                .findFirstByProjectIdAndStatusAndCreatedAtBeforeOrderByCreatedAtDesc(
                        1L, WorkSessionStatus.CLOSED, current.getCreatedAt()))
                .thenReturn(Optional.of(predecessor));
        when(remoteWorkerProperties.isRemoteCloseReconciliationEnabledFor("atenea"))
                .thenReturn(true);

        MobileSessionOperatorStateResponse response = service.build(conversation(
                WorkSessionStatus.OPEN,
                RemoteCloseState.NOT_STARTED,
                latestRun(null, null),
                false));

        assertEquals(MobileSessionOperatorState.DEFAULT, response.state());
        assertFalse(response.primaryActionAvailable());
        verifyNoInteractions(remoteWorkerClient);
    }

    @Test
    void historicalCompatibilityDoesNotInspectOwnershipWhileGateIsOff() {
        WorkSessionEntity current = exactRemoteSession(
                17L,
                "18c00753-6080-42f7-ac05-18c47b236cac",
                WorkSessionStatus.OPEN,
                RemoteCloseState.NOT_STARTED,
                Instant.parse("2026-08-03T10:03:00Z"));
        AgentRunEntity run = historicalRun(current);
        when(agentRunRepository.findById(96L)).thenReturn(Optional.of(run));
        when(remoteWorkerProperties.isRemoteCloseReconciliationEnabledFor("atenea"))
                .thenReturn(false);

        MobileSessionOperatorStateResponse response = service.build(conversation(
                WorkSessionStatus.OPEN,
                RemoteCloseState.NOT_STARTED,
                latestRun(null, null),
                false));

        assertEquals(MobileSessionOperatorState.DEFAULT, response.state());
        assertFalse(response.surfaceEnabled());
        verifyNoInteractions(workSessionRepository, remoteWorkerClient);
    }

    @Test
    void historicalRunOffersRetryOnlyAfterExactReleasedReceipt() {
        WorkSessionEntity current = exactRemoteSession(
                17L,
                "18c00753-6080-42f7-ac05-18c47b236cac",
                WorkSessionStatus.OPEN,
                RemoteCloseState.NOT_STARTED,
                Instant.parse("2026-08-03T10:03:00Z"));
        WorkSessionEntity predecessor = exactRemoteSession(
                16L,
                "7151dce0-69ab-4614-86e4-f93f1af825e4",
                WorkSessionStatus.CLOSED,
                RemoteCloseState.RELEASED,
                Instant.parse("2026-08-03T10:00:00Z"));
        predecessor.setRemoteCloseOperationId(UUID.randomUUID());
        predecessor.setRemoteCloseReceiptSha256("3".repeat(64));
        predecessor.setRemoteCloseReleasedAt(Instant.parse("2026-08-03T10:04:00Z"));
        AgentRunEntity run = historicalRun(current);
        when(agentRunRepository.findById(96L)).thenReturn(Optional.of(run));
        when(workSessionRepository.findWithProjectById(17L))
                .thenReturn(Optional.of(current));
        when(workSessionRepository
                .findFirstByProjectIdAndStatusAndCreatedAtBeforeOrderByCreatedAtDesc(
                        1L, WorkSessionStatus.CLOSED, current.getCreatedAt()))
                .thenReturn(Optional.of(predecessor));
        when(agentRunService.isRemoteRetryEligible(96L)).thenReturn(true);
        when(remoteWorkerProperties.isRemoteCloseReconciliationEnabledFor("atenea"))
                .thenReturn(true);

        MobileSessionOperatorStateResponse response = service.build(conversation(
                WorkSessionStatus.OPEN,
                RemoteCloseState.NOT_STARTED,
                latestRun(null, null),
                false));

        assertEquals(MobileSessionOperatorState.CAPACITY_RELEASED, response.state());
        assertEquals(MobileSessionPrimaryAction.RETRY_AGENT_RUN,
                response.primaryAction());
        assertTrue(response.primaryActionAvailable());
    }

    private static WorkSessionConversationViewResponse conversation(
            WorkSessionStatus status,
            RemoteCloseState closeState,
            WorkSessionViewLatestRunResponse latestRun,
            boolean runInProgress
    ) {
        WorkSessionResponse session = new WorkSessionResponse(
                17L,
                7L,
                status,
                runInProgress ? WorkSessionOperationalState.RUNNING
                        : WorkSessionOperationalState.IDLE,
                "Remote close",
                "main",
                "atenea/session-17",
                null,
                null,
                WorkSessionPullRequestStatus.NOT_CREATED,
                null,
                Instant.parse("2026-08-03T10:00:00Z"),
                Instant.parse("2026-08-03T10:01:00Z"),
                null,
                status == WorkSessionStatus.CLOSED
                        ? Instant.parse("2026-08-03T10:02:00Z") : null,
                null,
                null,
                null,
                false,
                ExecutionTarget.REMOTE,
                "ax42-01",
                "remote:ax42-01:work-session:18c00753-6080-42f7-ac05-18c47b236cac",
                new SessionOperationalSnapshotResponse(true, true, "main", runInProgress),
                closeState,
                closeState == RemoteCloseState.BLOCKED
                        ? "REMOTE_CLOSE_OWNERSHIP_BLOCKED" : null,
                AgentRunRecoveryNextAction.NONE);
        return new WorkSessionConversationViewResponse(
                new WorkSessionViewResponse(
                        session,
                        runInProgress,
                        !runInProgress && status == WorkSessionStatus.OPEN,
                        latestRun,
                        null,
                        null),
                List.of(),
                20,
                false);
    }

    private static WorkSessionViewLatestRunResponse closedOwnerRun() {
        return latestRun(
                "CLOSED_SESSION_OWNS_CAPACITY",
                AgentRunRecoveryNextAction.RECONCILE_REMOTE_CLOSE);
    }

    private static WorkSessionViewLatestRunResponse latestRun(
            String failureCode,
            AgentRunRecoveryNextAction nextAction
    ) {
        return new WorkSessionViewLatestRunResponse(
                96L,
                AgentRunStatus.FAILED,
                101L,
                null,
                null,
                Instant.parse("2026-08-03T10:01:00Z"),
                Instant.parse("2026-08-03T10:01:01Z"),
                null,
                null,
                ExecutionTarget.REMOTE,
                "ax42-01",
                "remote:ax42-01:work-session:18c00753-6080-42f7-ac05-18c47b236cac",
                UUID.fromString("76d7d1c2-9d73-4fcc-bc79-72cb19052e1d"),
                null,
                WorkloadClass.NORMAL,
                1,
                null,
                failureCode,
                nextAction);
    }

    private static AgentRunEntity blockedRun() {
        AgentRunEntity run = new AgentRunEntity();
        run.setId(96L);
        run.setStatus(AgentRunStatus.FAILED);
        run.setFailureCode("CLOSED_SESSION_OWNS_CAPACITY");
        run.setRecoveryNextAction(AgentRunRecoveryNextAction.RECONCILE_REMOTE_CLOSE);
        run.setRecoveryBlockerWorkSessionId(16L);
        return run;
    }

    private static WorkSessionEntity blocker(RemoteCloseState state) {
        WorkSessionEntity blocker = new WorkSessionEntity();
        blocker.setId(16L);
        blocker.setStatus(WorkSessionStatus.CLOSED);
        blocker.setRemoteCloseState(state);
        return blocker;
    }

    private static WorkSessionEntity exactRemoteSession(
            Long id,
            String remoteId,
            WorkSessionStatus status,
            RemoteCloseState closeState,
            Instant createdAt
    ) {
        ProjectEntity project = new ProjectEntity();
        project.setId(1L);
        project.setName(ProjectCodexIdentity.PROJECT_NAME);
        project.setRepoPath(ProjectCodexIdentity.REPO_PATH);
        WorkSessionEntity session = new WorkSessionEntity();
        session.setId(id);
        session.setProject(project);
        session.setStatus(status);
        session.setExecutionTarget(ExecutionTarget.REMOTE);
        session.setSelectedWorkerId(ProjectCodexIdentity.WORKER_ID);
        session.setRemoteWorkloadKind(ProjectCodexIdentity.WORKLOAD_KIND);
        session.setBaseBranch(ProjectCodexIdentity.BRANCH);
        session.setRemoteSessionId(UUID.fromString(remoteId));
        session.setWorkspaceIdentity(
                "remote:ax42-01:work-session:" + remoteId);
        session.setWorkspaceBranch("atenea/session-" + remoteId);
        session.setCanonicalSourceRef("refs/heads/main");
        session.setCanonicalSourceCommit("a".repeat(40));
        session.setCanonicalSourceObservationSha256("b".repeat(64));
        session.setCanonicalSourceObservedAt(createdAt);
        session.setRemoteCloseState(closeState);
        session.setCreatedAt(createdAt);
        return session;
    }

    private static void clearCanonicalSourceObservation(WorkSessionEntity session) {
        session.setCanonicalSourceRef(null);
        session.setCanonicalSourceCommit(null);
        session.setCanonicalSourceObservationSha256(null);
        session.setCanonicalSourceObservedAt(null);
    }

    private static AgentRunEntity historicalRun(WorkSessionEntity current) {
        AgentRunEntity run = new AgentRunEntity();
        run.setId(96L);
        run.setSession(current);
        run.setStatus(AgentRunStatus.FAILED);
        run.setExecutionTarget(ExecutionTarget.REMOTE);
        run.setSelectedWorkerId(ProjectCodexIdentity.WORKER_ID);
        run.setRemoteSessionId(current.getRemoteSessionId());
        run.setWorkspaceIdentity(current.getWorkspaceIdentity());
        run.setWorkloadKind(ProjectCodexIdentity.WORKLOAD_KIND);
        run.setProjectIdentity(ProjectCodexIdentity.PROJECT_IDENTITY);
        run.setRepositoryUrl(ProjectCodexIdentity.REPOSITORY);
        run.setRepositoryBranch(ProjectCodexIdentity.BRANCH);
        run.setRepositoryCommit(current.getCanonicalSourceCommit());
        run.setManifestSha256(ProjectCodexIdentity.MANIFEST_SHA256);
        ReviewedInstructionBundleIdentity.apply(run, ProjectCodexIdentity.PROJECT_IDENTITY);
        return run;
    }
}
