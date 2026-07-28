package com.atenea.service.worksession;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.atenea.persistence.project.ProjectEntity;
import com.atenea.persistence.worksession.AgentRunEntity;
import com.atenea.persistence.worksession.AgentRunRepository;
import com.atenea.persistence.worksession.AttachmentKind;
import com.atenea.persistence.worksession.AttachmentRetentionClass;
import com.atenea.persistence.worksession.AttachmentSource;
import com.atenea.persistence.worksession.ExecutionTarget;
import com.atenea.persistence.worksession.WorkSessionAttachmentEntity;
import com.atenea.persistence.worksession.WorkSessionAttachmentRepository;
import com.atenea.persistence.worksession.WorkSessionEntity;
import com.atenea.persistence.worksession.WorkSessionRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class WorkSessionAttachmentMetadataServiceTest {

    private static final Instant CREATED_AT = Instant.parse("2026-07-28T23:00:00Z");
    private static final UUID ATTACHMENT_ID =
            UUID.fromString("d9e42006-8aac-42ca-84e6-c2cad4a82548");

    @Mock
    private WorkSessionRepository workSessionRepository;

    @Mock
    private AgentRunRepository agentRunRepository;

    @Mock
    private WorkSessionAttachmentRepository attachmentRepository;

    private WorkSessionAttachmentMetadataService service;

    @BeforeEach
    void setUp() {
        service = new WorkSessionAttachmentMetadataService(
                workSessionRepository,
                agentRunRepository,
                attachmentRepository);
    }

    @Test
    void indexesOneOwnedAttachmentWithDerivedProjectAndRetention() {
        WorkSessionEntity session = remoteSession(12L, 7L);
        AgentRunEntity run = run(55L, session);
        when(attachmentRepository.findById(ATTACHMENT_ID)).thenReturn(Optional.empty());
        when(workSessionRepository.findLockedWithProjectById(12L)).thenReturn(Optional.of(session));
        when(agentRunRepository.findWithSessionById(55L)).thenReturn(Optional.of(run));
        when(attachmentRepository.sumSizeBytesByWorkSessionId(12L)).thenReturn(1024L);
        when(attachmentRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        WorkSessionAttachmentEntity indexed = service.index(12L, request(55L, 4096L));

        assertEquals(ATTACHMENT_ID, indexed.getId());
        assertEquals(12L, indexed.getWorkSession().getId());
        assertEquals(7L, indexed.getProject().getId());
        assertEquals(55L, indexed.getAgentRun().getId());
        assertEquals(CREATED_AT.plusSeconds(30L * 24L * 60L * 60L), indexed.getRetainUntil());
        assertEquals("ax42-01", indexed.getWorkerId());
        assertEquals("work-sessions/12/d9e42006-8aac-42ca-84e6-c2cad4a82548/content", indexed.getStorageIdentity());
    }

    @Test
    void rejectsAgentRunFromAnotherSessionBeforeIndexing() {
        WorkSessionEntity session = remoteSession(12L, 7L);
        AgentRunEntity foreignRun = run(55L, remoteSession(13L, 8L));
        when(attachmentRepository.findById(ATTACHMENT_ID)).thenReturn(Optional.empty());
        when(workSessionRepository.findLockedWithProjectById(12L)).thenReturn(Optional.of(session));
        when(agentRunRepository.findWithSessionById(55L)).thenReturn(Optional.of(foreignRun));

        assertThrows(AttachmentOwnershipException.class, () -> service.index(12L, request(55L, 4096L)));

        verify(attachmentRepository, never()).save(any());
    }

    @Test
    void rejectsWorkerDifferentFromPersistedSessionAffinity() {
        WorkSessionEntity session = remoteSession(12L, 7L);
        when(attachmentRepository.findById(ATTACHMENT_ID)).thenReturn(Optional.empty());
        when(workSessionRepository.findLockedWithProjectById(12L)).thenReturn(Optional.of(session));
        AttachmentIndexRequest request = new AttachmentIndexRequest(
                ATTACHMENT_ID,
                null,
                AttachmentSource.OPERATOR_UPLOAD,
                AttachmentKind.IMAGE,
                "screen.png",
                "image/png",
                4096,
                AttachmentRetentionClass.SESSION,
                "a".repeat(64),
                "foreign-worker",
                "foreign",
                CREATED_AT);

        assertThrows(AttachmentOwnershipException.class, () -> service.index(12L, request));

        verify(attachmentRepository, never()).save(any());
    }

    @Test
    void rejectsQuotaWithoutCreatingMetadata() {
        WorkSessionEntity session = remoteSession(12L, 7L);
        when(attachmentRepository.findById(ATTACHMENT_ID)).thenReturn(Optional.empty());
        when(workSessionRepository.findLockedWithProjectById(12L)).thenReturn(Optional.of(session));
        when(attachmentRepository.sumSizeBytesByWorkSessionId(12L))
                .thenReturn(WorkSessionAttachmentMetadataService.DEFAULT_MAX_SESSION_BYTES - 1024L);

        assertThrows(AttachmentLimitException.class, () -> service.index(12L, request(null, 4096L)));

        verify(attachmentRepository, never()).save(any());
    }

    @Test
    void identicalRetryReturnsExistingMetadataWithoutSaving() {
        WorkSessionAttachmentEntity existing = entity(remoteSession(12L, 7L), null);
        when(attachmentRepository.findById(ATTACHMENT_ID)).thenReturn(Optional.of(existing));

        WorkSessionAttachmentEntity result = service.index(12L, request(null, 4096L));

        assertSame(existing, result);
        verify(attachmentRepository, never()).save(any());
    }

    @Test
    void conflictingIdentityReuseFailsClosed() {
        WorkSessionAttachmentEntity existing = entity(remoteSession(12L, 7L), null);
        existing.setSha256("b".repeat(64));
        when(attachmentRepository.findById(ATTACHMENT_ID)).thenReturn(Optional.of(existing));

        assertThrows(AttachmentConflictException.class, () -> service.index(12L, request(null, 4096L)));

        verify(attachmentRepository, never()).save(any());
    }

    @Test
    void screenshotResolutionUsesStableSessionScopedOrderingAndOffset() {
        when(workSessionRepository.existsById(12L)).thenReturn(true);
        WorkSessionAttachmentEntity latest = entity(remoteSession(12L, 7L), null);
        WorkSessionAttachmentEntity previous = entity(remoteSession(12L, 7L), null);
        ArgumentCaptor<Pageable> pageCaptor = ArgumentCaptor.forClass(Pageable.class);
        when(attachmentRepository.findByWorkSessionIdAndKindOrderByCreatedAtDescIdDesc(
                org.mockito.ArgumentMatchers.eq(12L),
                org.mockito.ArgumentMatchers.eq(AttachmentKind.IMAGE),
                pageCaptor.capture()))
                .thenReturn(List.of(previous));

        assertSame(previous, service.previousScreenshot(12L, null).orElseThrow());
        assertEquals(1, pageCaptor.getValue().getPageNumber());
        assertEquals(1, pageCaptor.getValue().getPageSize());
        assertEquals(12L, previous.getWorkSession().getId());
        assertEquals(12L, latest.getWorkSession().getId());
    }

    private static AttachmentIndexRequest request(Long runId, long size) {
        return new AttachmentIndexRequest(
                ATTACHMENT_ID,
                runId,
                AttachmentSource.OPERATOR_UPLOAD,
                AttachmentKind.IMAGE,
                "screen.png",
                "image/png",
                size,
                AttachmentRetentionClass.SESSION,
                "a".repeat(64),
                "ax42-01",
                "work-sessions/12/d9e42006-8aac-42ca-84e6-c2cad4a82548/content",
                CREATED_AT);
    }

    private static WorkSessionAttachmentEntity entity(WorkSessionEntity session, AgentRunEntity run) {
        WorkSessionAttachmentEntity entity = new WorkSessionAttachmentEntity();
        entity.setId(ATTACHMENT_ID);
        entity.setWorkSession(session);
        entity.setProject(session.getProject());
        entity.setAgentRun(run);
        entity.setSource(AttachmentSource.OPERATOR_UPLOAD);
        entity.setKind(AttachmentKind.IMAGE);
        entity.setOriginalFilename("screen.png");
        entity.setContentType("image/png");
        entity.setSizeBytes(4096L);
        entity.setRetentionClass(AttachmentRetentionClass.SESSION);
        entity.setRetainUntil(CREATED_AT.plus(AttachmentRetentionClass.SESSION.duration()));
        entity.setSha256("a".repeat(64));
        entity.setWorkerId("ax42-01");
        entity.setStorageIdentity(
                "work-sessions/12/d9e42006-8aac-42ca-84e6-c2cad4a82548/content");
        entity.setCreatedAt(CREATED_AT);
        entity.setIndexedAt(CREATED_AT.plusSeconds(1));
        return entity;
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
