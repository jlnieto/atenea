package com.atenea.attachments;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.atenea.persistence.project.ProjectEntity;
import com.atenea.persistence.worksession.AttachmentKind;
import com.atenea.persistence.worksession.AttachmentRetentionClass;
import com.atenea.persistence.worksession.AttachmentSource;
import com.atenea.persistence.worksession.ExecutionTarget;
import com.atenea.persistence.worksession.WorkSessionAttachmentEntity;
import com.atenea.persistence.worksession.WorkSessionEntity;
import com.atenea.persistence.worksession.WorkSessionRepository;
import com.atenea.service.worksession.AttachmentOwnershipException;
import com.atenea.service.worksession.WorkSessionAttachmentMetadataService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

@ExtendWith(MockitoExtension.class)
class WorkSessionAttachmentServiceTest {

    private static final UUID ATTACHMENT_ID =
            UUID.fromString("d9e42006-8aac-42ca-84e6-c2cad4a82548");
    private static final byte[] PNG = "\u0089PNG\r\n\u001a\nsynthetic".getBytes();

    @Mock
    private AttachmentWorkerClient workerClient;
    @Mock
    private WorkSessionRepository workSessionRepository;
    @Mock
    private WorkSessionAttachmentMetadataService metadataService;

    private AttachmentProperties properties;
    private WorkSessionAttachmentService service;

    @BeforeEach
    void setUp() {
        properties = new AttachmentProperties();
        properties.setEnabled(true);
        properties.setSyntheticProjectAllowlist(Set.of("synthetic-attachments"));
        service = new WorkSessionAttachmentService(
                properties,
                workerClient,
                workSessionRepository,
                metadataService);
    }

    @Test
    void uploadsOnlyThroughPersistedSessionAffinityAndIndexesOpaqueIdentity() {
        WorkSessionEntity session = remoteSession(12L, 7L, "synthetic-attachments");
        WorkSessionAttachmentEntity indexed = attachment(session);
        when(workSessionRepository.findWithProjectById(12L)).thenReturn(Optional.of(session));
        when(workerClient.health()).thenReturn(health());
        when(workerClient.put(
                org.mockito.ArgumentMatchers.eq(12L),
                org.mockito.ArgumentMatchers.eq(ATTACHMENT_ID),
                org.mockito.ArgumentMatchers.eq(AttachmentSource.OPERATOR_UPLOAD),
                org.mockito.ArgumentMatchers.eq(AttachmentKind.IMAGE),
                org.mockito.ArgumentMatchers.eq(AttachmentRetentionClass.SESSION),
                org.mockito.ArgumentMatchers.eq("image/png"),
                any(),
                any(),
                org.mockito.ArgumentMatchers.any(byte[].class)))
                .thenAnswer(invocation -> {
                    Instant requestedCreatedAt = invocation.getArgument(7);
                    assertEquals(0, requestedCreatedAt.getNano() % 1_000);
                    return new AttachmentWorkerClient.PutResult(
                            true,
                            stored(invocation.getArgument(5), invocation.getArgument(6)));
                });
        when(metadataService.index(org.mockito.ArgumentMatchers.eq(12L), any()))
                .thenReturn(indexed);

        WorkSessionAttachmentEntity result = service.upload(
                12L,
                ATTACHMENT_ID,
                null,
                AttachmentSource.OPERATOR_UPLOAD,
                AttachmentKind.IMAGE,
                AttachmentRetentionClass.SESSION,
                new MockMultipartFile("file", "screen.png", "image/png", PNG));

        assertEquals(ATTACHMENT_ID, result.getId());
        verify(metadataService).index(org.mockito.ArgumentMatchers.eq(12L), any());
        verify(workerClient, never()).deleteSynthetic(any(), any());
    }

    @Test
    void rejectsForeignSessionBeforeCallingWorker() {
        WorkSessionEntity session = remoteSession(12L, 7L, "synthetic-attachments");
        session.setSelectedWorkerId("foreign-worker");
        when(workSessionRepository.findWithProjectById(12L)).thenReturn(Optional.of(session));

        assertThrows(AttachmentOwnershipException.class, () -> service.upload(
                12L,
                ATTACHMENT_ID,
                null,
                AttachmentSource.OPERATOR_UPLOAD,
                AttachmentKind.IMAGE,
                AttachmentRetentionClass.SESSION,
                new MockMultipartFile("file", "screen.png", "image/png", PNG)));

        verify(workerClient, never()).health();
        verify(workerClient, never()).put(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void disabledCreationLeavesRetainedMetadataAndDownloadAvailable() {
        properties.setEnabled(false);
        WorkSessionEntity session = remoteSession(12L, 7L, "synthetic-attachments");
        WorkSessionAttachmentEntity metadata = attachment(session);
        when(workSessionRepository.findWithProjectById(12L)).thenReturn(Optional.of(session));
        when(metadataService.get(12L, ATTACHMENT_ID)).thenReturn(metadata);
        when(workerClient.content(12L, ATTACHMENT_ID))
                .thenReturn(new AttachmentWorkerClient.Content("image/png", PNG));

        assertThrows(AttachmentFeatureDisabledException.class, () -> service.upload(
                12L,
                ATTACHMENT_ID,
                null,
                AttachmentSource.OPERATOR_UPLOAD,
                AttachmentKind.IMAGE,
                AttachmentRetentionClass.SESSION,
                new MockMultipartFile("file", "screen.png", "image/png", PNG)));
        assertArrayEquals(PNG, service.download(12L, ATTACHMENT_ID).content());
    }

    @Test
    void cleansOnlyNewSyntheticWorkerObjectWhenIndexingFails() {
        WorkSessionEntity session = remoteSession(12L, 7L, "synthetic-attachments");
        when(workSessionRepository.findWithProjectById(12L)).thenReturn(Optional.of(session));
        when(workerClient.health()).thenReturn(health());
        when(workerClient.put(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> new AttachmentWorkerClient.PutResult(
                        true,
                        stored(invocation.getArgument(5), invocation.getArgument(6))));
        when(metadataService.index(org.mockito.ArgumentMatchers.eq(12L), any()))
                .thenThrow(new IllegalStateException("synthetic index failure"));

        assertThrows(IllegalStateException.class, () -> service.upload(
                12L,
                ATTACHMENT_ID,
                null,
                AttachmentSource.OPERATOR_UPLOAD,
                AttachmentKind.IMAGE,
                AttachmentRetentionClass.SESSION,
                new MockMultipartFile("file", "screen.png", "image/png", PNG)));

        verify(workerClient).deleteSynthetic(12L, ATTACHMENT_ID);
    }

    private AttachmentWorkerClient.Health health() {
        return new AttachmentWorkerClient.Health(
                AttachmentProperties.PROTOCOL,
                "ax42-01",
                true,
                AttachmentProperties.DEFAULT_MAX_FILE_BYTES,
                AttachmentProperties.DEFAULT_MAX_SESSION_BYTES,
                List.of("image/png"),
                Instant.now());
    }

    private AttachmentWorkerClient.StoredAttachment stored(String contentType, String sha256) {
        return new AttachmentWorkerClient.StoredAttachment(
                AttachmentProperties.PROTOCOL,
                "ax42-01",
                "12",
                ATTACHMENT_ID,
                "work-sessions/12/" + ATTACHMENT_ID + "/content",
                AttachmentSource.OPERATOR_UPLOAD,
                AttachmentKind.IMAGE,
                contentType,
                PNG.length,
                AttachmentRetentionClass.SESSION,
                sha256,
                true,
                Instant.parse("2026-07-28T23:00:00Z"),
                Instant.parse("2026-07-28T23:00:01Z"));
    }

    private static WorkSessionEntity remoteSession(Long sessionId, Long projectId, String projectName) {
        ProjectEntity project = new ProjectEntity();
        project.setId(projectId);
        project.setName(projectName);
        WorkSessionEntity session = new WorkSessionEntity();
        session.setId(sessionId);
        session.setProject(project);
        session.setExecutionTarget(ExecutionTarget.REMOTE);
        session.setSelectedWorkerId("ax42-01");
        return session;
    }

    private static WorkSessionAttachmentEntity attachment(WorkSessionEntity session) {
        WorkSessionAttachmentEntity attachment = new WorkSessionAttachmentEntity();
        attachment.setId(ATTACHMENT_ID);
        attachment.setWorkSession(session);
        attachment.setProject(session.getProject());
        attachment.setSource(AttachmentSource.OPERATOR_UPLOAD);
        attachment.setKind(AttachmentKind.IMAGE);
        attachment.setOriginalFilename("screen.png");
        attachment.setContentType("image/png");
        attachment.setSizeBytes(PNG.length);
        attachment.setRetentionClass(AttachmentRetentionClass.SESSION);
        attachment.setRetainUntil(Instant.parse("2026-08-27T23:00:00Z"));
        attachment.setSha256(sha256(PNG));
        attachment.setWorkerId("ax42-01");
        attachment.setStorageIdentity("opaque");
        attachment.setCreatedAt(Instant.parse("2026-07-28T23:00:00Z"));
        attachment.setIndexedAt(Instant.parse("2026-07-28T23:00:01Z"));
        return attachment;
    }

    private static String sha256(byte[] content) {
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(content));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
