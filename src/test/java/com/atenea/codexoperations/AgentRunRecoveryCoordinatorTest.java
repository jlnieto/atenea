package com.atenea.codexoperations;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.atenea.persistence.worksession.AgentRunEntity;
import com.atenea.persistence.worksession.AgentRunRecoveryAction;
import com.atenea.persistence.worksession.AgentRunRecoveryNextAction;
import com.atenea.persistence.worksession.AgentRunRecoveryOperationEntity;
import com.atenea.persistence.worksession.AgentRunRecoveryOperationRepository;
import com.atenea.persistence.worksession.AgentRunRecoveryOutcome;
import com.atenea.persistence.worksession.AgentRunRepository;
import com.atenea.persistence.worksession.AgentRunStatus;
import com.atenea.persistence.worksession.WorkSessionEntity;
import com.atenea.remoteworker.RemoteAgentRunCoordinator;
import com.atenea.remoteworker.CanonicalSourceAdmissionService;
import com.atenea.service.worksession.AgentRunRecoveryOperationService;
import com.atenea.service.worksession.AgentRunRecoveryConflictException;
import com.atenea.service.worksession.AgentRunService;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AgentRunRecoveryCoordinatorTest {

    private AgentRunRecoveryOperationService operationService;
    private AgentRunRecoveryOperationRepository operationRepository;
    private AgentRunRepository runRepository;
    private AgentRunService runService;
    private RemoteAgentRunCoordinator remoteCoordinator;
    private CanonicalSourceAdmissionService canonicalSourceAdmissionService;
    private AgentRunRecoveryCoordinator coordinator;

    @BeforeEach
    void setUp() {
        operationService = mock(AgentRunRecoveryOperationService.class);
        operationRepository = mock(AgentRunRecoveryOperationRepository.class);
        runRepository = mock(AgentRunRepository.class);
        runService = mock(AgentRunService.class);
        remoteCoordinator = mock(RemoteAgentRunCoordinator.class);
        canonicalSourceAdmissionService = mock(CanonicalSourceAdmissionService.class);
        coordinator = new AgentRunRecoveryCoordinator(
                operationService, operationRepository, runRepository,
                runService, remoteCoordinator, canonicalSourceAdmissionService);
    }

    @AfterEach
    void tearDown() {
        coordinator.shutdown();
    }

    @Test
    void safelyAbsentFailedRunCreatesOneLinkedRetryAndDispatchesIt() {
        UUID operationId = UUID.randomUUID();
        WorkSessionEntity session = new WorkSessionEntity();
        session.setId(9L);
        AgentRunEntity source = new AgentRunEntity();
        source.setId(81L);
        source.setSession(session);
        source.setStatus(AgentRunStatus.FAILED);
        AgentRunEntity retry = new AgentRunEntity();
        retry.setId(84L);
        retry.setSession(session);
        retry.setRetryOfRun(source);
        AgentRunRecoveryOperationEntity operation = operation(
                operationId, source, AgentRunRecoveryAction.RETRY);

        when(operationService.start(operationId)).thenReturn(operation);
        when(runRepository.findWithSessionById(source.getId())).thenReturn(Optional.of(source));
        when(runRepository.existsBySessionIdAndStatusIn(
                session.getId(), AgentRunStatus.nonTerminalStatuses())).thenReturn(false);
        when(remoteCoordinator.proveTerminalOrAbsent(source.getId()))
                .thenReturn(RemoteAgentRunCoordinator.RetryProof.ABSENT);
        when(runService.createRemoteRetryRun(source.getId())).thenReturn(retry);

        coordinator.executeOne(operationId);

        verify(operationService).complete(
                operationId, AgentRunRecoveryOutcome.RETRY_CREATED, retry.getId());
        verify(canonicalSourceAdmissionService).admitBeforeWrite(session);
        verify(remoteCoordinator).dispatchAfterCommit(retry.getId());
    }

    @Test
    void liveRemoteProofRejectsRetryWithoutCreatingOrDispatching() {
        UUID operationId = UUID.randomUUID();
        WorkSessionEntity session = new WorkSessionEntity();
        session.setId(9L);
        AgentRunEntity source = new AgentRunEntity();
        source.setId(81L);
        source.setSession(session);
        source.setStatus(AgentRunStatus.FAILED);
        AgentRunRecoveryOperationEntity operation = operation(
                operationId, source, AgentRunRecoveryAction.RETRY);

        when(operationService.start(operationId)).thenReturn(operation);
        when(runRepository.findWithSessionById(source.getId())).thenReturn(Optional.of(source));
        when(runRepository.existsBySessionIdAndStatusIn(
                session.getId(), AgentRunStatus.nonTerminalStatuses())).thenReturn(false);
        when(remoteCoordinator.proveTerminalOrAbsent(source.getId()))
                .thenReturn(RemoteAgentRunCoordinator.RetryProof.STILL_LIVE);

        coordinator.executeOne(operationId);

        verify(operationService).complete(
                operationId, AgentRunRecoveryOutcome.EXECUTION_STILL_LIVE, null);
        verify(canonicalSourceAdmissionService).admitBeforeWrite(session);
        verify(runService, never()).createRemoteRetryRun(source.getId());
        verify(remoteCoordinator, never()).dispatchAfterCommit(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void deterministicBlockerStopsBeforeAdmissionWorkerProofOrRetryCreation() {
        UUID operationId = UUID.randomUUID();
        WorkSessionEntity session = new WorkSessionEntity();
        session.setId(9L);
        AgentRunEntity source = new AgentRunEntity();
        source.setId(81L);
        source.setSession(session);
        source.setStatus(AgentRunStatus.FAILED);
        source.setFailureCode("CLOSED_SESSION_OWNS_CAPACITY");
        source.setRecoveryNextAction(AgentRunRecoveryNextAction.RECONCILE_REMOTE_CLOSE);
        AgentRunRecoveryOperationEntity operation = operation(
                operationId, source, AgentRunRecoveryAction.RETRY);

        when(operationService.start(operationId)).thenReturn(operation);
        when(runRepository.findWithSessionById(source.getId())).thenReturn(Optional.of(source));
        when(runRepository.existsBySessionIdAndStatusIn(
                session.getId(), AgentRunStatus.nonTerminalStatuses())).thenReturn(false);
        org.mockito.Mockito.doThrow(new AgentRunRecoveryConflictException("blocker remains"))
                .when(runService).requireRemoteRetryEligible(source);

        coordinator.executeOne(operationId);

        verify(operationService).complete(
                operationId, AgentRunRecoveryOutcome.POLICY_BLOCKED, null);
        verify(canonicalSourceAdmissionService, never()).admitBeforeWrite(session);
        verify(remoteCoordinator, never()).proveTerminalOrAbsent(source.getId());
        verify(runService, never()).createRemoteRetryRun(source.getId());
        verify(remoteCoordinator, never()).dispatchAfterCommit(org.mockito.ArgumentMatchers.any());
    }

    private static AgentRunRecoveryOperationEntity operation(
            UUID operationId, AgentRunEntity run, AgentRunRecoveryAction action) {
        AgentRunRecoveryOperationEntity operation = new AgentRunRecoveryOperationEntity();
        operation.setOperationId(operationId);
        operation.setAgentRun(run);
        operation.setAction(action);
        return operation;
    }
}
