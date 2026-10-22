package com.atenea.service.worksession;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.atenea.delivery.SourceUpdateBaseGuard;
import com.atenea.persistence.developmentchange.*;
import com.atenea.persistence.project.ProjectEntity;
import com.atenea.persistence.worksession.*;
import com.atenea.remoteworker.ProjectCodexIdentity;
import com.atenea.remoteworker.RemoteWorkerClient;
import com.atenea.remoteworker.RemoteWorkerException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

class ValidationResultReconcilerTest {
    private static final UUID ID = UUID.fromString("de0b19a3-d312-47d0-9cd0-52ef13868a6d");
    private static final String TREE = "5".repeat(64);
    private final WorkSessionRepository sessions = mock(WorkSessionRepository.class);
    private final AgentRunRepository runs = mock(AgentRunRepository.class);
    private final ValidationOperationRepository operations = mock(ValidationOperationRepository.class);
    private final RemoteWorkerClient worker = mock(RemoteWorkerClient.class);
    private final WorkSessionAcceptanceService acceptance = mock(WorkSessionAcceptanceService.class);
    private final SourceUpdateBaseGuard base = mock(SourceUpdateBaseGuard.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ClosedValidationOperationService service = new ClosedValidationOperationService(
            sessions, runs, operations, worker, acceptance, base, jdbc);
    private WorkSessionEntity session;
    private DevelopmentChangeEntity change;
    private ValidationOperationEntity operation;

    @BeforeEach
    void fixture() {
        var project = new ProjectEntity();
        project.setId(1L); project.setName(ProjectCodexIdentity.PROJECT_NAME);
        project.setRepoPath(ProjectCodexIdentity.REPO_PATH);
        project.setDefaultBaseBranch(ProjectCodexIdentity.BRANCH);
        UUID key = UUID.fromString("c080799e-af05-4ed5-b572-f63f42488fbb");
        change = new DevelopmentChangeEntity();
        change.setChangeKey(key); change.setProject(project);
        change.setStatus(DevelopmentChangeStatus.OPEN);
        change.setWorkspaceState(DevelopmentChangeWorkspaceState.READY);
        change.setSourceState(DevelopmentChangeSourceState.DIRTY);
        change.setSourceRevision(2);
        change.setSourceFingerprintSha256(TREE);
        change.setValidationState(DevelopmentChangeProjectionState.STALE);
        change.setWorkspaceIdentity("remote:ax42-01:change:" + key);
        change.setWorkspaceBranch("atenea/change-" + key);
        change.setSelectedWorkerId(ProjectCodexIdentity.WORKER_ID);
        session = new WorkSessionEntity();
        session.setId(28L); session.setProject(project); session.setDevelopmentChange(change);
        session.setStatus(WorkSessionStatus.OPEN); session.setExecutionTarget(ExecutionTarget.REMOTE);
        session.setBaseBranch(ProjectCodexIdentity.BRANCH);
        session.setRemoteSessionId(UUID.fromString("22894ecf-fe8e-4d64-82f4-58fc88d88686"));
        session.setWorkspaceIdentity(change.getWorkspaceIdentity());
        session.setWorkspaceBranch(change.getWorkspaceBranch());
        session.setSelectedWorkerId(ProjectCodexIdentity.WORKER_ID);
        session.setCanonicalSourceCommit("1".repeat(40));
        session.setCanonicalSourceRef("refs/heads/main");
        session.setCanonicalSourceObservationSha256("2".repeat(64));
        session.setCanonicalSourceObservedAt(Instant.now());
        operation = new ValidationOperationEntity();
        operation.setId(ID); operation.setWorkSession(session);
        operation.setOperation(ValidationOperationKind.ANDROID_BUILD);
        operation.setDefinitionRevision(ValidationOperationKind.ANDROID_BUILD.definitionRevision());
        operation.setSourceTreeFingerprintSha256(TREE);
        operation.setStatus(ValidationOperationStatus.RUNNING);
        operation.setWorkerState("QUEUED");
        operation.setStartedAt(Instant.now());
        lenient().when(sessions.findLockedWithProjectAndDevelopmentChangeById(28L)).thenReturn(Optional.of(session));
        lenient().when(operations.findById(ID)).thenReturn(Optional.of(operation));
    }

    @Test
    void collectsFinishedAndroidWithoutAnotherCheckOrAcceptancePromotion() {
        when(worker.inspectValidation(session, ID.toString())).thenReturn(result("SUCCEEDED", ID, TREE, 0, "a".repeat(64)));
        var value = service.reconcileRetainedResult(28L, ID);
        assertEquals(ID, value.id());
        assertEquals(ValidationOperationStatus.SUCCEEDED, value.status());
        assertEquals(0, value.exitCode());
        assertEquals("a".repeat(64), value.artifactManifestSha256());
        assertEquals(DevelopmentChangeProjectionState.STALE, change.getValidationState());
        verify(operations).save(operation);
        verify(worker).inspectValidation(session, ID.toString());
        verifyNoMoreInteractions(worker);
        verifyNoInteractions(base, acceptance);
        var order = inOrder(jdbc, sessions);
        order.verify(jdbc).execute("SELECT pg_advisory_xact_lock(814205002)");
        order.verify(sessions).findLockedWithProjectAndDevelopmentChangeById(28L);
    }

    @Test
    void activeWorkflowOwnsTheResultEvenIfTheResidualCollectorSelectedItEarlier() {
        when(sessions.existsActiveValidationWorkflowBySessionId(28L)).thenReturn(true);
        assertEquals(ValidationOperationStatus.RUNNING,service.reconcileRetainedResult(28L,ID).status());
        verifyNoInteractions(worker,acceptance,base);verify(operations,never()).save(any());
        var order=inOrder(jdbc,sessions);
        order.verify(jdbc).execute("SELECT pg_advisory_xact_lock(814205002)");
        order.verify(sessions).findLockedWithProjectAndDevelopmentChangeById(28L);
        order.verify(sessions).existsActiveValidationWorkflowBySessionId(28L);

        when(sessions.existsActiveValidationWorkflowBySessionId(28L)).thenReturn(false);
        when(worker.inspectValidation(session,ID.toString())).thenReturn(result("SUCCEEDED",ID,TREE,0,"a".repeat(64)));
        assertEquals(ValidationOperationStatus.SUCCEEDED,service.reconcileRetainedResult(28L,ID).status());
        verify(worker,times(1)).inspectValidation(session,ID.toString());verifyNoMoreInteractions(worker);
        verifyNoInteractions(acceptance,base);
    }

    @Test
    void mainOrCurrentFingerprintAdvanceDoesNotDiscardHistoricalResult() {
        change.setSourceRevision(3); change.setSourceFingerprintSha256("b".repeat(64));
        doThrow(new IllegalStateException("main moved")).when(base).requireCurrent(anyLong());
        when(worker.inspectValidation(session, ID.toString())).thenReturn(result("SUCCEEDED", ID, TREE, 0, "a".repeat(64)));
        assertEquals(ValidationOperationStatus.SUCCEEDED, service.reconcileRetainedResult(28L, ID).status());
        assertEquals(TREE, operation.getSourceTreeFingerprintSha256());
        assertEquals("b".repeat(64), change.getSourceFingerprintSha256());
        verifyNoInteractions(base, acceptance);
    }

    @Test
    void repeatedCollectionOfTerminalRowIsIdempotentAndNeverInspectsAgain() {
        operation.setStatus(ValidationOperationStatus.SUCCEEDED);
        service.reconcileRetainedResult(28L, ID);
        service.reconcileRetainedResult(28L, ID);
        verifyNoInteractions(worker, acceptance, base);
        verify(operations, never()).save(any());
    }

    @Test
    void actuallyRunningOperationRemainsRunningAndDoesNotStartNextCheck() {
        when(worker.inspectValidation(session, ID.toString())).thenReturn(result("RUNNING", ID, TREE, null, null));
        assertEquals(ValidationOperationStatus.RUNNING, service.reconcileRetainedResult(28L, ID).status());
        assertNull(operation.getFinishedAt());
        verify(worker).inspectValidation(session, ID.toString());
        verifyNoMoreInteractions(worker);
        verifyNoInteractions(base, acceptance);
    }

    @Test
    void unavailableWorkerDoesNotFabricateATerminalResult() {
        when(worker.inspectValidation(session, ID.toString())).thenThrow(new RemoteWorkerException("unavailable", 503));
        assertThrows(RemoteWorkerException.class, () -> service.reconcileRetainedResult(28L, ID));
        assertEquals(ValidationOperationStatus.RUNNING, operation.getStatus());
        verify(operations, never()).save(any());
    }

    @Test
    void foreignOperationOrTreeCannotReplaceRetainedEvidence() {
        for (var value : List.of(result("SUCCEEDED", UUID.randomUUID(), TREE, 0, "a".repeat(64)),
                result("SUCCEEDED", ID, "b".repeat(64), 0, "a".repeat(64)))) {
            when(worker.inspectValidation(session, ID.toString())).thenReturn(value);
            assertThrows(RemoteWorkerException.class, () -> service.reconcileRetainedResult(28L, ID));
            assertEquals(ValidationOperationStatus.RUNNING, operation.getStatus());
        }
        verify(operations, never()).save(any());
    }

    @Test
    void invalidSuccessExitOrManifestIsRejectedWithoutChangingTheRow() {
        for (var value : List.of(result("SUCCEEDED", ID, TREE, 1, "a".repeat(64)),
                result("SUCCEEDED", ID, TREE, 0, null), result("SUCCEEDED", ID, TREE, 0, "foreign"))) {
            when(worker.inspectValidation(session, ID.toString())).thenReturn(value);
            assertThrows(RemoteWorkerException.class, () -> service.reconcileRetainedResult(28L, ID));
            assertEquals(ValidationOperationStatus.RUNNING, operation.getStatus());
        }
        verify(operations, never()).save(any());
    }

    @Test
    void foreignSessionRowIsRejectedBeforeWorkerAccess() {
        var foreign = new WorkSessionEntity(); foreign.setId(29L); operation.setWorkSession(foreign);
        assertThrows(WorkSessionOperationBlockedException.class, () -> service.reconcileRetainedResult(28L, ID));
        verifyNoInteractions(worker);
    }

    @Test
    void schedulerCollectsOnlyExistingIdsAndDoesNotDependOnAMobileRequest() {
        var collector = mock(ClosedValidationOperationService.class);
        var pending = mock(ValidationOperationRepository.PendingResult.class);
        when(pending.getSessionId()).thenReturn(28L); when(pending.getId()).thenReturn(ID);
        when(operations.pendingResults(PageRequest.of(0, 10))).thenReturn(List.of(pending));
        new ValidationResultReconciler(operations, collector).reconcilePending();
        verify(collector).reconcileRetainedResult(28L, ID);
        verifyNoMoreInteractions(collector);
    }

    @Test
    void schedulerPreservesUnconfirmedResultWithoutFakingSuccessOrRetrying() {
        var collector = mock(ClosedValidationOperationService.class);
        var pending = mock(ValidationOperationRepository.PendingResult.class);
        when(pending.getSessionId()).thenReturn(28L); when(pending.getId()).thenReturn(ID);
        when(operations.pendingResults(PageRequest.of(0, 10))).thenReturn(List.of(pending));
        when(collector.reconcileRetainedResult(28L, ID)).thenThrow(new RemoteWorkerException("unavailable", 503));
        assertDoesNotThrow(() -> new ValidationResultReconciler(operations, collector).reconcilePending());
        verify(collector).reconcileRetainedResult(28L, ID); verifyNoMoreInteractions(collector);
    }

    private RemoteWorkerClient.DurableValidationResult result(String state, UUID id, String tree, Integer exit, String manifest) {
        boolean terminal = "SUCCEEDED".equals(state);
        return new RemoteWorkerClient.DurableValidationResult(1, "closed-validation-broker/v1", id.toString(),
                session.getRemoteSessionId().toString(), session.getWorkspaceIdentity(), ProjectCodexIdentity.PROJECT_IDENTITY,
                "1".repeat(40), tree, "ANDROID_BUILD", ValidationOperationKind.ANDROID_BUILD.definitionRevision(),
                state, "NONE", "CONFIRMED", exit, 145919, manifest, "Closed validation passed",
                "2026-10-19T19:43:53Z", "2026-10-19T19:43:53Z", terminal ? "2026-10-19T19:46:21Z" : null,
                "2026-10-19T19:46:21Z", 417, false);
    }
}
