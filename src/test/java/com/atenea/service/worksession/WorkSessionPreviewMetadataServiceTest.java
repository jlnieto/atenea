package com.atenea.service.worksession;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.atenea.persistence.project.ProjectEntity;
import com.atenea.persistence.worksession.AgentRunEntity;
import com.atenea.persistence.worksession.AgentRunRepository;
import com.atenea.persistence.worksession.ExecutionTarget;
import com.atenea.persistence.worksession.PreviewState;
import com.atenea.persistence.worksession.WorkSessionEntity;
import com.atenea.persistence.worksession.WorkSessionPreviewEntity;
import com.atenea.persistence.worksession.WorkSessionPreviewRepository;
import com.atenea.persistence.worksession.WorkSessionRepository;
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
class WorkSessionPreviewMetadataServiceTest {

    private static final UUID PREVIEW_ID =
            UUID.fromString("61000000-0000-4000-8000-000000000001");
    private static final Instant CREATED_AT = Instant.parse("2026-07-29T00:00:00Z");

    @Mock
    private WorkSessionRepository workSessionRepository;

    @Mock
    private AgentRunRepository agentRunRepository;

    @Mock
    private WorkSessionPreviewRepository previewRepository;

    private WorkSessionPreviewMetadataService service;

    @BeforeEach
    void setUp() {
        service = new WorkSessionPreviewMetadataService(
                workSessionRepository,
                agentRunRepository,
                previewRepository);
    }

    @Test
    void createsOneStartingPreviewWithDerivedOwnershipAndBounds() {
        WorkSessionEntity session = remoteSession(61L, 7L);
        AgentRunEntity run = run(610L, session);
        when(previewRepository.findById(PREVIEW_ID)).thenReturn(Optional.empty());
        when(workSessionRepository.findLockedWithProjectById(61L)).thenReturn(Optional.of(session));
        when(agentRunRepository.findWithSessionById(610L)).thenReturn(Optional.of(run));
        when(previewRepository.existsByWorkSessionIdAndStateIn(any(), any())).thenReturn(false);
        when(previewRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        WorkSessionPreviewEntity created = service.create(61L, request(610L));

        assertEquals(PREVIEW_ID, created.getId());
        assertEquals(61L, created.getWorkSession().getId());
        assertEquals(7L, created.getProject().getId());
        assertEquals(610L, created.getAgentRun().getId());
        assertEquals(PreviewState.STARTING, created.getState());
        assertEquals(1L, created.getLifecycleRevision());
        assertEquals(CREATED_AT.plusSeconds(300), created.getLeaseExpiresAt());
        assertEquals(CREATED_AT.plusSeconds(8 * 60 * 60), created.getHardExpiresAt());
        assertEquals(CREATED_AT.plusSeconds(30L * 24 * 60 * 60), created.getAuditRetainUntil());
        assertNull(created.getPrivateUrl());
    }

    @Test
    void rejectsForeignAgentRunBeforeCreatingPreview() {
        WorkSessionEntity session = remoteSession(61L, 7L);
        when(previewRepository.findById(PREVIEW_ID)).thenReturn(Optional.empty());
        when(workSessionRepository.findLockedWithProjectById(61L)).thenReturn(Optional.of(session));
        when(previewRepository.existsByWorkSessionIdAndStateIn(any(), any())).thenReturn(false);
        when(agentRunRepository.findWithSessionById(610L))
                .thenReturn(Optional.of(run(610L, remoteSession(62L, 8L))));

        assertThrows(PreviewOwnershipException.class, () -> service.create(61L, request(610L)));

        verify(previewRepository, never()).save(any());
    }

    @Test
    void rejectsWorkerDifferentFromPersistedAffinity() {
        WorkSessionEntity session = remoteSession(61L, 7L);
        session.setSelectedWorkerId("other-worker");
        when(previewRepository.findById(PREVIEW_ID)).thenReturn(Optional.empty());
        when(workSessionRepository.findLockedWithProjectById(61L)).thenReturn(Optional.of(session));

        assertThrows(PreviewOwnershipException.class, () -> service.create(61L, request(null)));

        verify(previewRepository, never()).save(any());
    }

    @Test
    void rejectsSecondActivePreviewForSameSession() {
        WorkSessionEntity session = remoteSession(61L, 7L);
        when(previewRepository.findById(PREVIEW_ID)).thenReturn(Optional.empty());
        when(workSessionRepository.findLockedWithProjectById(61L)).thenReturn(Optional.of(session));
        when(previewRepository.existsByWorkSessionIdAndStateIn(any(), any())).thenReturn(true);

        assertThrows(PreviewConflictException.class, () -> service.create(61L, request(null)));

        verify(previewRepository, never()).save(any());
    }

    @Test
    void identicalCreateRetryReturnsExistingRecord() {
        WorkSessionPreviewEntity existing = preview(remoteSession(61L, 7L), PreviewState.STARTING, 1L);
        when(previewRepository.findById(PREVIEW_ID)).thenReturn(Optional.of(existing));
        PreviewIndexRequest retry = new PreviewIndexRequest(
                PREVIEW_ID,
                null,
                "ax42-01",
                "runtime:session:61",
                "a".repeat(64),
                false,
                CREATED_AT.plusSeconds(1));

        WorkSessionPreviewEntity result = service.create(61L, retry);

        assertSame(existing, result);
        verify(previewRepository, never()).save(any());
    }

    @Test
    void conflictingIdentityReuseFailsClosed() {
        WorkSessionPreviewEntity existing = preview(remoteSession(61L, 7L), PreviewState.STARTING, 1L);
        existing.setAllocationFingerprint("b".repeat(64));
        when(previewRepository.findById(PREVIEW_ID)).thenReturn(Optional.of(existing));

        assertThrows(PreviewConflictException.class, () -> service.create(61L, request(null)));

        verify(previewRepository, never()).save(any());
    }

    @Test
    void staleLifecycleRevisionMutatesNothing() {
        WorkSessionPreviewEntity existing = preview(remoteSession(61L, 7L), PreviewState.STARTING, 3L);
        when(previewRepository.findLockedById(PREVIEW_ID)).thenReturn(Optional.of(existing));

        assertThrows(PreviewConflictException.class, () ->
                service.markReady(PREVIEW_ID, 2L, "http://100.81.98.93:19000/", CREATED_AT.plusSeconds(30)));

        verify(previewRepository, never()).saveAndFlush(any());
        assertEquals(PreviewState.STARTING, existing.getState());
    }

    @Test
    void readyAndRenewKeepPrivateRouteWithinHardLimit() {
        WorkSessionPreviewEntity existing = preview(remoteSession(61L, 7L), PreviewState.STARTING, 1L);
        when(previewRepository.findLockedById(PREVIEW_ID)).thenReturn(Optional.of(existing));
        when(previewRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        WorkSessionPreviewEntity ready = service.markReady(
                PREVIEW_ID,
                1L,
                "http://100.81.98.93:19000/app",
                CREATED_AT.plusSeconds(60));

        assertEquals(PreviewState.READY, ready.getState());
        assertEquals("http://100.81.98.93:19000/app", ready.getPrivateUrl());
        assertEquals(CREATED_AT.plusSeconds(360), ready.getLeaseExpiresAt());

        ready.setLifecycleRevision(2L);
        WorkSessionPreviewEntity renewed = service.renew(
                PREVIEW_ID,
                2L,
                ready.getHardExpiresAt().minusSeconds(60));

        assertEquals(ready.getHardExpiresAt(), renewed.getLeaseExpiresAt());
    }

    @Test
    void expiryRequiresElapsedLeaseAndPreservesAuditIdentity() {
        WorkSessionPreviewEntity existing = preview(remoteSession(61L, 7L), PreviewState.READY, 2L);
        when(previewRepository.findLockedById(PREVIEW_ID)).thenReturn(Optional.of(existing));
        when(previewRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        assertThrows(PreviewConflictException.class, () ->
                service.expire(PREVIEW_ID, 2L, CREATED_AT.plusSeconds(299)));

        WorkSessionPreviewEntity expired =
                service.expire(PREVIEW_ID, 2L, CREATED_AT.plusSeconds(300));

        assertEquals(PreviewState.EXPIRED, expired.getState());
        assertNull(expired.getPrivateUrl());
        assertEquals(PREVIEW_ID, expired.getId());
        assertEquals(CREATED_AT.plusSeconds(30L * 24 * 60 * 60), expired.getAuditRetainUntil());
    }

    @Test
    void reconciliationAndAuditQueriesRemainSessionScoped() {
        WorkSessionPreviewEntity owned = preview(remoteSession(61L, 7L), PreviewState.RECONCILING, 4L);
        when(previewRepository.findByStateInOrderByCreatedAtAscIdAsc(any()))
                .thenReturn(List.of(owned));
        when(workSessionRepository.existsById(61L)).thenReturn(true);
        when(previewRepository
                .findByWorkSessionIdAndAuditRetainUntilAfterOrderByCreatedAtDescIdDesc(
                        61L, CREATED_AT))
                .thenReturn(List.of(owned));

        assertSame(owned, service.reconcilable().getFirst());
        assertSame(owned, service.retainedAudit(61L, CREATED_AT).getFirst());
        assertFalse(owned.getState().isTerminal());
    }

    private static PreviewIndexRequest request(Long runId) {
        return new PreviewIndexRequest(
                PREVIEW_ID,
                runId,
                "ax42-01",
                "runtime:session:61",
                "a".repeat(64),
                true,
                CREATED_AT);
    }

    private static WorkSessionPreviewEntity preview(
            WorkSessionEntity session,
            PreviewState state,
            long revision
    ) {
        WorkSessionPreviewEntity preview = new WorkSessionPreviewEntity();
        preview.setId(PREVIEW_ID);
        preview.setWorkSession(session);
        preview.setProject(session.getProject());
        preview.setWorkerId("ax42-01");
        preview.setAllocationIdentity("runtime:session:61");
        preview.setAllocationFingerprint("a".repeat(64));
        preview.setState(state);
        preview.setLifecycleRevision(revision);
        preview.setLocalhostCompatible(true);
        preview.setPrivateUrl(state == PreviewState.READY
                ? "http://100.81.98.93:19000/app"
                : null);
        preview.setLeaseExpiresAt(CREATED_AT.plusSeconds(300));
        preview.setHardExpiresAt(CREATED_AT.plusSeconds(8 * 60 * 60));
        preview.setAuditRetainUntil(CREATED_AT.plusSeconds(30L * 24 * 60 * 60));
        preview.setCreatedAt(CREATED_AT);
        preview.setUpdatedAt(CREATED_AT);
        return preview;
    }

    private static WorkSessionEntity remoteSession(Long sessionId, Long projectId) {
        ProjectEntity project = new ProjectEntity();
        project.setId(projectId);
        WorkSessionEntity session = new WorkSessionEntity();
        session.setId(sessionId);
        session.setProject(project);
        session.setExecutionTarget(ExecutionTarget.REMOTE);
        session.setSelectedWorkerId("ax42-01");
        session.setWorkspaceIdentity("remote:ax42-01:work-session:" + sessionId);
        return session;
    }

    private static AgentRunEntity run(Long runId, WorkSessionEntity session) {
        AgentRunEntity run = new AgentRunEntity();
        run.setId(runId);
        run.setSession(session);
        return run;
    }
}
