package com.atenea.service.mobile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
import com.atenea.remoteworker.RemoteWorkerProperties;
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

    private MobileSessionOperatorStateService service;

    @BeforeEach
    void setUp() {
        service = new MobileSessionOperatorStateService(
                agentRunRepository,
                workSessionRepository,
                agentRunService,
                remoteWorkerProperties);
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
}
