package com.atenea.service.worksession;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.atenea.api.worksession.RecoverDraftWorkSessionResponse;
import com.atenea.persistence.project.ProjectEntity;
import com.atenea.persistence.worksession.AgentRunRepository;
import com.atenea.persistence.worksession.AgentRunStatus;
import com.atenea.persistence.worksession.ExecutionTarget;
import com.atenea.persistence.worksession.WorkSessionEntity;
import com.atenea.persistence.worksession.WorkSessionPullRequestStatus;
import com.atenea.persistence.worksession.WorkSessionRepository;
import com.atenea.persistence.worksession.WorkSessionStatus;
import com.atenea.remoteworker.CanonicalSourceAdmissionService;
import com.atenea.remoteworker.ProjectCodexIdentity;
import com.atenea.remoteworker.RemoteRoutingSelector;
import com.atenea.remoteworker.RemoteWorkerClient;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RetainedDraftRecoveryServiceTest {

    private static final String RETAINED_HEAD = "0".repeat(40);
    private static final String ACCEPTED_COMMIT = "1".repeat(40);

    @Mock
    private WorkSessionRepository workSessionRepository;
    @Mock
    private AgentRunRepository agentRunRepository;
    @Mock
    private CanonicalSourceAdmissionService canonicalSourceAdmissionService;
    @Mock
    private RemoteWorkerClient remoteWorkerClient;
    @Mock
    private RemoteRoutingSelector remoteRoutingSelector;
    @Mock
    private SessionBranchService sessionBranchService;

    private RetainedDraftRecoveryService service;

    @BeforeEach
    void setUp() {
        service = new RetainedDraftRecoveryService(
                workSessionRepository,
                agentRunRepository,
                canonicalSourceAdmissionService,
                remoteWorkerClient,
                remoteRoutingSelector,
                sessionBranchService);
    }

    @Test
    void retainsSanitizedDraftAndCreatesSeparateCurrentRemoteSession() {
        WorkSessionEntity retained = retainedSession();
        when(workSessionRepository.findLockedWithProjectById(41L)).thenReturn(Optional.of(retained));
        when(agentRunRepository.existsBySessionIdAndStatusIn(
                41L,
                AgentRunStatus.nonTerminalStatuses())).thenReturn(false);
        doAnswer(invocation -> {
            WorkSessionEntity session = invocation.getArgument(0);
            session.setCanonicalSourceRef("refs/heads/" + ProjectCodexIdentity.BRANCH);
            session.setCanonicalSourceCommit(ACCEPTED_COMMIT);
            session.setCanonicalSourceObservationSha256("2".repeat(64));
            session.setCanonicalSourceObservedAt(Instant.parse("2026-07-30T12:00:00Z"));
            return null;
        }).when(canonicalSourceAdmissionService).admitBeforeWrite(retained);
        when(remoteWorkerClient.fingerprintRetainedDraft(retained)).thenReturn(fingerprint(retained));

        AtomicLong identifiers = new AtomicLong(42L);
        when(workSessionRepository.save(any(WorkSessionEntity.class))).thenAnswer(invocation -> {
            WorkSessionEntity session = invocation.getArgument(0);
            if (session.getId() == null) {
                session.setId(identifiers.getAndIncrement());
            }
            return session;
        });
        when(workSessionRepository.saveAndFlush(any(WorkSessionEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        doAnswer(invocation -> {
            WorkSessionEntity replacement = invocation.getArgument(0);
            UUID remoteId = UUID.fromString("22222222-2222-4222-8222-222222222222");
            replacement.setExecutionTarget(ExecutionTarget.REMOTE);
            replacement.setSelectedWorkerId("ax42-01");
            replacement.setRemoteSessionId(remoteId);
            replacement.setRemoteWorkloadKind(ProjectCodexIdentity.WORKLOAD_KIND);
            replacement.setWorkspaceIdentity("remote:ax42-01:work-session:" + remoteId);
            return null;
        }).when(remoteRoutingSelector).pinNewSession(any(WorkSessionEntity.class));
        when(sessionBranchService.prepareWorkspaceBranch(any(WorkSessionEntity.class), any()))
                .thenReturn("atenea/session-22222222-2222-4222-8222-222222222222");

        RecoverDraftWorkSessionResponse response = service.recover(41L);

        assertEquals(41L, response.blockedSessionId());
        assertEquals(42L, response.replacementSessionId());
        assertEquals(RETAINED_HEAD, response.retainedHead());
        assertEquals(ACCEPTED_COMMIT, response.acceptedCommit());
        assertEquals("3".repeat(64), response.draftFingerprintSha256());
        assertEquals(2, response.stagedChangeCount());
        assertEquals(3, response.unstagedChangeCount());
        assertEquals(4, response.untrackedChangeCount());
        assertFalse(response.valuesExposed());
        assertEquals(WorkSessionStatus.DRAFT_BLOCKED, retained.getStatus());
        assertEquals(42L, retained.getReplacementWorkSessionId());
        assertNull(retained.getClosedAt());

        ArgumentCaptor<WorkSessionEntity> saved = ArgumentCaptor.forClass(WorkSessionEntity.class);
        verify(workSessionRepository, org.mockito.Mockito.atLeast(2)).save(saved.capture());
        WorkSessionEntity replacement = saved.getAllValues().stream()
                .filter(candidate -> Long.valueOf(42L).equals(candidate.getId()))
                .findFirst()
                .orElseThrow();
        assertEquals(WorkSessionStatus.OPEN, replacement.getStatus());
        assertEquals(ExecutionTarget.REMOTE, replacement.getExecutionTarget());
        assertEquals(ACCEPTED_COMMIT, replacement.getCanonicalSourceCommit());
        assertNull(replacement.getExternalThreadId());
        assertNull(replacement.getFinalCommitSha());
        assertNull(replacement.getDraftFingerprintSha256());
    }

    @Test
    void activeRunFailsBeforeCanonicalObservationOrWorkerInspection() {
        WorkSessionEntity retained = retainedSession();
        when(workSessionRepository.findLockedWithProjectById(41L)).thenReturn(Optional.of(retained));
        when(agentRunRepository.existsBySessionIdAndStatusIn(
                41L,
                AgentRunStatus.nonTerminalStatuses())).thenReturn(true);

        assertThrows(WorkSessionOperationBlockedException.class, () -> service.recover(41L));

        verify(canonicalSourceAdmissionService, never()).admitBeforeWrite(any());
        verify(remoteWorkerClient, never()).fingerprintRetainedDraft(any());
        assertEquals(WorkSessionStatus.OPEN, retained.getStatus());
    }

    @Test
    void retryOfCompletedRecoveryIsIdempotentAndDoesNotCallWorker() {
        WorkSessionEntity retained = retainedSession();
        retained.setStatus(WorkSessionStatus.DRAFT_BLOCKED);
        retained.setCanonicalSourceCommit(ACCEPTED_COMMIT);
        retained.setDraftRetainedHead(RETAINED_HEAD);
        retained.setDraftFingerprintSha256("3".repeat(64));
        retained.setDraftStagedChangeCount(2);
        retained.setDraftUnstagedChangeCount(3);
        retained.setDraftUntrackedChangeCount(4);
        retained.setDraftBlockedAt(Instant.now());
        retained.setReplacementWorkSessionId(42L);
        when(workSessionRepository.findLockedWithProjectById(41L)).thenReturn(Optional.of(retained));

        RecoverDraftWorkSessionResponse response = service.recover(41L);

        assertEquals(42L, response.replacementSessionId());
        verify(canonicalSourceAdmissionService, never()).admitBeforeWrite(any());
        verify(remoteWorkerClient, never()).fingerprintRetainedDraft(any());
        verify(workSessionRepository, never()).save(any());
    }

    private WorkSessionEntity retainedSession() {
        ProjectEntity project = new ProjectEntity();
        project.setId(7L);
        project.setName(ProjectCodexIdentity.PROJECT_NAME);
        project.setRepoPath(ProjectCodexIdentity.REPO_PATH);
        project.setDefaultBaseBranch(ProjectCodexIdentity.BRANCH);

        UUID remoteId = UUID.fromString("11111111-1111-4111-8111-111111111111");
        WorkSessionEntity session = new WorkSessionEntity();
        session.setId(41L);
        session.setProject(project);
        session.setStatus(WorkSessionStatus.OPEN);
        session.setTitle("Retained work");
        session.setBaseBranch(ProjectCodexIdentity.BRANCH);
        session.setWorkspaceBranch("atenea/session-" + remoteId);
        session.setExecutionTarget(ExecutionTarget.REMOTE);
        session.setSelectedWorkerId("ax42-01");
        session.setRemoteSessionId(remoteId);
        session.setRemoteWorkloadKind(ProjectCodexIdentity.WORKLOAD_KIND);
        session.setWorkspaceIdentity("remote:ax42-01:work-session:" + remoteId);
        session.setPullRequestStatus(WorkSessionPullRequestStatus.NOT_CREATED);
        session.setOpenedAt(Instant.parse("2026-07-29T12:00:00Z"));
        session.setLastActivityAt(Instant.parse("2026-07-29T12:00:00Z"));
        session.setCreatedAt(Instant.parse("2026-07-29T12:00:00Z"));
        session.setUpdatedAt(Instant.parse("2026-07-29T12:00:00Z"));
        return session;
    }

    private RemoteWorkerClient.DraftFingerprint fingerprint(WorkSessionEntity retained) {
        return new RemoteWorkerClient.DraftFingerprint(
                "draft_blocked_ready",
                retained.getRemoteSessionId().toString(),
                retained.getWorkspaceIdentity(),
                ProjectCodexIdentity.PROJECT_IDENTITY,
                RETAINED_HEAD,
                ACCEPTED_COMMIT,
                "3".repeat(64),
                2,
                3,
                4,
                false);
    }
}
