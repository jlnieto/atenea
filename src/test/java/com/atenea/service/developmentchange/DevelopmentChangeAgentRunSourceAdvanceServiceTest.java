package com.atenea.service.developmentchange;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.atenea.persistence.developmentchange.DevelopmentChangeEntity;
import com.atenea.persistence.developmentchange.DevelopmentChangeProjectionState;
import com.atenea.persistence.developmentchange.DevelopmentChangeRepository;
import com.atenea.persistence.developmentchange.DevelopmentChangeSourceState;
import com.atenea.persistence.developmentchange.DevelopmentChangeStatus;
import com.atenea.persistence.developmentchange.DevelopmentChangeWorkspaceOperationRepository;
import com.atenea.persistence.developmentchange.DevelopmentChangeWorkspaceState;
import com.atenea.persistence.project.ProjectEntity;
import com.atenea.persistence.worksession.AgentRunEntity;
import com.atenea.persistence.worksession.AgentRunStatus;
import com.atenea.persistence.worksession.ExecutionTarget;
import com.atenea.persistence.worksession.WorkSessionEntity;
import com.atenea.persistence.worksession.WorkSessionStatus;
import com.atenea.remoteworker.ProjectCodexIdentity;
import com.atenea.remoteworker.RemoteWorkerClient;
import com.atenea.remoteworker.RemoteWorkerException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DevelopmentChangeAgentRunSourceAdvanceServiceTest {

    private static final String COMMIT = "1".repeat(40);
    private static final String FIRST_SOURCE = "3".repeat(64);
    private static final String FIRST_OWNERSHIP = "5".repeat(64);
    private static final Instant FINISHED = Instant.parse("2026-08-24T12:00:00Z");

    private DevelopmentChangeRepository changeRepository;
    private DevelopmentChangeWorkspaceOperationRepository workspaceOperationRepository;
    private DevelopmentChangeAgentRunSourceAdvanceService service;
    private DevelopmentChangeEntity change;
    private WorkSessionEntity session;

    @BeforeEach
    void setUp() {
        changeRepository = mock(DevelopmentChangeRepository.class);
        workspaceOperationRepository = mock(DevelopmentChangeWorkspaceOperationRepository.class);
        service = new DevelopmentChangeAgentRunSourceAdvanceService(
                changeRepository, workspaceOperationRepository);
        session = changeSession();
        change = session.getDevelopmentChange();
        when(changeRepository.findByChangeKeyForUpdate(change.getChangeKey()))
                .thenReturn(Optional.of(change));
        when(changeRepository.saveAndFlush(any(DevelopmentChangeEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void twoMutatingRunsAdvanceOneMonotonicIdentityOnTheSameChange() {
        AgentRunEntity first = run(81L, "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");

        assertTrue(service.advance(
                first, identity(first, COMMIT, "6".repeat(64), true), FINISHED));
        assertEquals(4, change.getSourceRevision());
        assertEquals("6".repeat(64), change.getSourceFingerprintSha256());
        assertEquals(FIRST_OWNERSHIP, change.getWorkspaceOwnershipFingerprintSha256());
        assertEquals(DevelopmentChangeSourceState.DIRTY, change.getSourceState());

        AgentRunEntity second = run(82L, "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
        assertEquals(4L, second.getChangeSourceRevision());
        assertEquals("6".repeat(64), second.getChangeSourceFingerprintSha256());
        assertEquals(FIRST_OWNERSHIP,
                second.getChangeWorkspaceOwnershipFingerprintSha256());

        assertTrue(service.advance(
                second, identity(second, "2".repeat(40), null, false),
                FINISHED.plusSeconds(1)));
        assertEquals(5, change.getSourceRevision());
        assertEquals("6".repeat(64), change.getSourceFingerprintSha256());
        assertEquals("2".repeat(40), change.getObservedCanonicalCommit());
        assertEquals(FIRST_OWNERSHIP, change.getWorkspaceOwnershipFingerprintSha256());
        assertEquals(DevelopmentChangeSourceState.CLEAN, change.getSourceState());
    }

    @Test
    void unchangedExactResultIsIdempotentAndDoesNotAdvance() {
        AgentRunEntity run = run(81L, "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
        RemoteWorkerClient.SourceIdentity unchanged = identity(
                run, COMMIT, FIRST_SOURCE, true);

        assertFalse(service.advance(run, unchanged, FINISHED));
        assertFalse(service.advance(run, unchanged, FINISHED));
        assertEquals(3, change.getSourceRevision());
        verify(changeRepository, never()).saveAndFlush(any());
    }

    @Test
    void oldMutatingResultCannotOverwriteTheNewerIdentity() {
        AgentRunEntity old = run(81L, "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
        RemoteWorkerClient.SourceIdentity oldResult = identity(
                old, COMMIT, "6".repeat(64), true);
        service.advance(old, oldResult, FINISHED);
        AgentRunEntity newer = run(82L, "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
        service.advance(
                newer, identity(newer, COMMIT, "8".repeat(64), true),
                FINISHED.plusSeconds(1));

        assertThrows(RemoteWorkerException.class,
                () -> service.advance(old, oldResult, FINISHED));
        assertEquals(5, change.getSourceRevision());
        assertEquals("8".repeat(64), change.getSourceFingerprintSha256());
        assertEquals(FIRST_OWNERSHIP, change.getWorkspaceOwnershipFingerprintSha256());
    }

    @Test
    void crossedChangeWorkSessionWorkspaceOrExecutionIsRejected() {
        AgentRunEntity run = run(81L, "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
        RemoteWorkerClient.SourceIdentity valid = identity(
                run, COMMIT, "6".repeat(64), true);
        var crossed = java.util.List.of(
                new RemoteWorkerClient.SourceIdentity(
                        UUID.randomUUID().toString(), valid.databaseWorkSessionId(),
                        valid.remoteSessionId(), valid.workspaceIdentity(), valid.executionId(),
                        valid.sourceCommit(), valid.sourceFingerprintSha256(), true),
                new RemoteWorkerClient.SourceIdentity(
                        valid.changeKey(), 999L, valid.remoteSessionId(),
                        valid.workspaceIdentity(), valid.executionId(),
                        valid.sourceCommit(), valid.sourceFingerprintSha256(), true),
                new RemoteWorkerClient.SourceIdentity(
                        valid.changeKey(), valid.databaseWorkSessionId(),
                        UUID.randomUUID().toString(), valid.workspaceIdentity(),
                        valid.executionId(), valid.sourceCommit(),
                        valid.sourceFingerprintSha256(), true),
                new RemoteWorkerClient.SourceIdentity(
                        valid.changeKey(), valid.databaseWorkSessionId(),
                        valid.remoteSessionId(), "remote:ax42-01:change:" + UUID.randomUUID(),
                        valid.executionId(), valid.sourceCommit(),
                        valid.sourceFingerprintSha256(), true),
                new RemoteWorkerClient.SourceIdentity(
                        valid.changeKey(), valid.databaseWorkSessionId(),
                        valid.remoteSessionId(), valid.workspaceIdentity(), UUID.randomUUID().toString(),
                        valid.sourceCommit(), valid.sourceFingerprintSha256(), true));

        crossed.forEach(identity -> assertThrows(
                RemoteWorkerException.class,
                () -> service.advance(run, identity, FINISHED)));
        assertEquals(3, change.getSourceRevision());
        verify(changeRepository, never()).saveAndFlush(any());
    }

    @Test
    void concurrentRevisionAndAmbiguousIdentityFailClosed() {
        AgentRunEntity run = run(81L, "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
        change.setSourceRevision(4);
        assertThrows(RemoteWorkerException.class, () -> service.advance(
                run, identity(run, COMMIT, "6".repeat(64), true), FINISHED));

        change.setSourceRevision(3);
        assertThrows(RemoteWorkerException.class, () -> service.advance(
                run, identity(run, COMMIT, FIRST_SOURCE, false), FINISHED));
        assertEquals(FIRST_SOURCE, change.getSourceFingerprintSha256());
        assertEquals(FIRST_OWNERSHIP, change.getWorkspaceOwnershipFingerprintSha256());
        verify(changeRepository, never()).saveAndFlush(any());
    }

    @Test
    void activeWorkspaceOperationRejectsAdvanceWithoutProvisioningOrMoving() {
        AgentRunEntity run = run(81L, "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
        when(workspaceOperationRepository.existsByDevelopmentChangeIdAndStateIn(
                any(), any())).thenReturn(true);

        assertThrows(RemoteWorkerException.class, () -> service.advance(
                run, identity(run, COMMIT, "6".repeat(64), true), FINISHED));
        assertEquals(3, change.getSourceRevision());
        verify(changeRepository, never()).saveAndFlush(any());
    }

    @Test
    void legacyWorkSessionIsIgnoredWithoutReadingChangePersistence() {
        AgentRunEntity legacy = run(81L, "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
        legacy.setWorkloadKind(ProjectCodexIdentity.WORKLOAD_KIND);
        legacy.setDevelopmentChangeKey(null);
        legacy.getSession().setDevelopmentChange(null);

        assertFalse(service.advance(legacy, null, FINISHED));
        verify(changeRepository, never()).findByChangeKeyForUpdate(any());
        verify(changeRepository, never()).saveAndFlush(any());
    }

    private WorkSessionEntity changeSession() {
        UUID changeKey = UUID.fromString("df99f1a1-1f14-4ca8-a405-58cd5b91bf2f");
        UUID remoteSessionId = UUID.fromString("a1c3af50-af6e-4cc2-85d6-a491c50cddcc");
        String workspace = "remote:ax42-01:change:" + changeKey;
        String branch = "atenea/change-" + changeKey;
        ProjectEntity project = new ProjectEntity();
        project.setId(7L);
        project.setName(ProjectCodexIdentity.PROJECT_NAME);
        project.setRepoPath(ProjectCodexIdentity.REPO_PATH);
        WorkSessionEntity value = new WorkSessionEntity();
        value.setId(19L);
        value.setProject(project);
        value.setStatus(WorkSessionStatus.OPEN);
        value.setExecutionTarget(ExecutionTarget.REMOTE);
        value.setSelectedWorkerId(ProjectCodexIdentity.WORKER_ID);
        value.setRemoteSessionId(remoteSessionId);
        value.setRemoteWorkloadKind(ProjectCodexIdentity.WORKLOAD_KIND);
        value.setWorkspaceIdentity(workspace);
        value.setWorkspaceBranch(branch);
        value.setCanonicalSourceRef("refs/heads/main");
        value.setCanonicalSourceCommit(COMMIT);

        DevelopmentChangeEntity owned = new DevelopmentChangeEntity();
        owned.setId(91L);
        owned.setChangeKey(changeKey);
        owned.setProject(project);
        owned.setStatus(DevelopmentChangeStatus.OPEN);
        owned.setBaseRef("refs/heads/main");
        owned.setBaseCommit(COMMIT);
        owned.setObservedCanonicalCommit(COMMIT);
        owned.setWorkspaceIdentity(workspace);
        owned.setWorkspaceBranch(branch);
        owned.setSelectedWorkerId(ProjectCodexIdentity.WORKER_ID);
        owned.setSourceRevision(3);
        owned.setSourceFingerprintSha256(FIRST_SOURCE);
        owned.setWorkspaceOwnershipFingerprintSha256(FIRST_OWNERSHIP);
        owned.setSourceState(DevelopmentChangeSourceState.DIRTY);
        owned.setWorkspaceState(DevelopmentChangeWorkspaceState.READY);
        owned.setWorkspaceOperationRevision(2);
        owned.setWorkspaceUpdatedAt(Instant.parse("2026-08-23T12:00:00Z"));
        owned.setValidationState(DevelopmentChangeProjectionState.CURRENT);
        owned.setReviewState(DevelopmentChangeProjectionState.NOT_STARTED);
        owned.setIntegrationState(DevelopmentChangeProjectionState.NOT_STARTED);
        owned.setReleaseState(DevelopmentChangeProjectionState.NOT_STARTED);
        value.setDevelopmentChange(owned);
        return value;
    }

    private AgentRunEntity run(Long id, String executionId) {
        AgentRunEntity run = new AgentRunEntity();
        run.setId(id);
        run.setSession(session);
        run.setStatus(AgentRunStatus.RUNNING);
        run.setExecutionTarget(ExecutionTarget.REMOTE);
        run.setSelectedWorkerId(ProjectCodexIdentity.WORKER_ID);
        run.setRemoteSessionId(session.getRemoteSessionId());
        run.setWorkspaceIdentity(session.getWorkspaceIdentity());
        run.setWorkloadKind(ProjectCodexIdentity.CHANGE_WORKLOAD_KIND);
        run.setRepositoryCommit(change.getObservedCanonicalCommit());
        run.setRemoteExecutionId(executionId);
        run.setDevelopmentChangeKey(change.getChangeKey());
        run.setChangeBaseCommit(change.getBaseCommit());
        run.setChangeExpectedCanonicalCommit(change.getObservedCanonicalCommit());
        run.setChangeSourceRevision(change.getSourceRevision());
        run.setChangeSourceFingerprintSha256(change.getSourceFingerprintSha256());
        run.setChangeWorkspaceOwnershipFingerprintSha256(
                change.getWorkspaceOwnershipFingerprintSha256());
        return run;
    }

    private RemoteWorkerClient.SourceIdentity identity(
            AgentRunEntity run,
            String sourceCommit,
            String sourceFingerprint,
            boolean dirty
    ) {
        return new RemoteWorkerClient.SourceIdentity(
                run.getDevelopmentChangeKey().toString(),
                run.getSession().getId(),
                run.getRemoteSessionId().toString(),
                run.getWorkspaceIdentity(),
                run.getRemoteExecutionId(),
                sourceCommit,
                sourceFingerprint,
                dirty);
    }
}
