package com.atenea.attachments;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
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
import com.atenea.service.worksession.AttachmentIndexRequest;
import com.atenea.service.worksession.AttachmentOwnershipException;
import com.atenea.service.worksession.WorkSessionAttachmentMetadataService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

@ExtendWith(MockitoExtension.class)
class WorkSessionAttachmentServiceTest {

    private static final UUID ATTACHMENT_ID =
            UUID.fromString("d9e42006-8aac-42ca-84e6-c2cad4a82548");
    private static final byte[] PNG =
            "\u0089PNG\r\n\u001a\nsynthetic".getBytes(StandardCharsets.ISO_8859_1);

    @TempDir
    Path temporaryDirectory;

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
        RealAttachmentProjectRegistry registry = new RealAttachmentProjectRegistry(properties);
        service = new WorkSessionAttachmentService(
                properties,
                new AttachmentAdmissionPolicy(properties, registry),
                new AttachmentUploadSpooler(temporaryDirectory.resolve("spool")),
                workerClient,
                workSessionRepository,
                metadataService);
    }

    @Test
    void derivesRoutineImageClassificationAndIndexesOpaqueIdentity() throws Exception {
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
                org.mockito.ArgumentMatchers.any(Path.class)))
                .thenAnswer(invocation -> {
                    Instant requestedCreatedAt = invocation.getArgument(7);
                    assertEquals(0, requestedCreatedAt.getNano() % 1_000);
                    assertArrayEquals(PNG, Files.readAllBytes(invocation.getArgument(8)));
                    return new AttachmentWorkerClient.PutResult(
                            true,
                            stored(invocation.getArgument(5), invocation.getArgument(6)));
                });
        when(metadataService.index(org.mockito.ArgumentMatchers.eq(12L), any()))
                .thenAnswer(invocation -> {
                    assertSpoolEmpty();
                    return indexed;
                });

        MockMultipartFile file = spy(new MockMultipartFile(
                "file", "screen.png", "image/png", PNG));
        WorkSessionAttachmentEntity result = service.upload(
                12L,
                ATTACHMENT_ID,
                null,
                null,
                null,
                null,
                file);

        assertEquals(ATTACHMENT_ID, result.getId());
        ArgumentCaptor<AttachmentIndexRequest> request =
                ArgumentCaptor.forClass(AttachmentIndexRequest.class);
        verify(metadataService).index(org.mockito.ArgumentMatchers.eq(12L), request.capture());
        assertEquals(AttachmentSource.OPERATOR_UPLOAD, request.getValue().source());
        assertEquals(AttachmentKind.IMAGE, request.getValue().kind());
        assertEquals(AttachmentRetentionClass.SESSION, request.getValue().retentionClass());
        verify(workerClient, never()).deleteSynthetic(any(), any());
        verify(file, never()).getBytes();
        assertSpoolEmpty();
    }

    @Test
    void derivesRoutineFileClassificationAndAcceptsMatchingCompatibilityClaims() {
        byte[] pdf = "%PDF-1.7 synthetic".getBytes();
        WorkSessionEntity session = remoteSession(12L, 7L, "synthetic-attachments");
        WorkSessionAttachmentEntity indexed = attachment(session);
        indexed.setKind(AttachmentKind.FILE);
        indexed.setContentType("application/pdf");
        indexed.setSizeBytes(pdf.length);
        when(workSessionRepository.findWithProjectById(12L)).thenReturn(Optional.of(session));
        when(workerClient.health()).thenReturn(health());
        when(workerClient.put(
                org.mockito.ArgumentMatchers.eq(12L),
                org.mockito.ArgumentMatchers.eq(ATTACHMENT_ID),
                org.mockito.ArgumentMatchers.eq(AttachmentSource.OPERATOR_UPLOAD),
                org.mockito.ArgumentMatchers.eq(AttachmentKind.FILE),
                org.mockito.ArgumentMatchers.eq(AttachmentRetentionClass.SESSION),
                org.mockito.ArgumentMatchers.eq("application/pdf"),
                any(),
                any(),
                org.mockito.ArgumentMatchers.any(Path.class)))
                .thenAnswer(invocation -> {
                    assertArrayEquals(pdf, Files.readAllBytes(invocation.getArgument(8)));
                    return new AttachmentWorkerClient.PutResult(
                            true,
                            stored(
                                    invocation.getArgument(5),
                                    invocation.getArgument(6),
                                    AttachmentKind.FILE,
                                    pdf.length));
                });
        when(metadataService.index(org.mockito.ArgumentMatchers.eq(12L), any()))
                .thenReturn(indexed);

        WorkSessionAttachmentEntity result = service.upload(
                12L,
                ATTACHMENT_ID,
                null,
                AttachmentSource.OPERATOR_UPLOAD,
                AttachmentKind.FILE,
                AttachmentRetentionClass.SESSION,
                new MockMultipartFile("file", "document.pdf", "application/pdf", pdf));

        assertEquals(AttachmentKind.FILE, result.getKind());
        ArgumentCaptor<AttachmentIndexRequest> request =
                ArgumentCaptor.forClass(AttachmentIndexRequest.class);
        verify(metadataService).index(org.mockito.ArgumentMatchers.eq(12L), request.capture());
        assertEquals(AttachmentSource.OPERATOR_UPLOAD, request.getValue().source());
        assertEquals(AttachmentKind.FILE, request.getValue().kind());
        assertEquals(AttachmentRetentionClass.SESSION, request.getValue().retentionClass());
        assertSpoolEmpty();
    }

    @Test
    void rejectsEveryNonDerivedClassificationBeforeCallingWorker() {
        WorkSessionEntity session = remoteSession(12L, 7L, "synthetic-attachments");
        when(workSessionRepository.findWithProjectById(12L)).thenReturn(Optional.of(session));
        MockMultipartFile image = new MockMultipartFile(
                "file", "screen.png", "image/png", PNG);

        assertThrows(IllegalArgumentException.class, () -> service.upload(
                12L, ATTACHMENT_ID, null,
                AttachmentSource.BROWSER_SCREENSHOT, null, null, image));
        assertThrows(IllegalArgumentException.class, () -> service.upload(
                12L, ATTACHMENT_ID, null,
                AttachmentSource.BROWSER_TRACE, null, null, image));
        assertThrows(IllegalArgumentException.class, () -> service.upload(
                12L, ATTACHMENT_ID, null,
                AttachmentSource.REPORT, null, null, image));
        assertThrows(IllegalArgumentException.class, () -> service.upload(
                12L, ATTACHMENT_ID, null,
                null, AttachmentKind.TRACE, null, image));
        assertThrows(IllegalArgumentException.class, () -> service.upload(
                12L, ATTACHMENT_ID, null,
                null, AttachmentKind.REPORT, null, image));
        assertThrows(IllegalArgumentException.class, () -> service.upload(
                12L, ATTACHMENT_ID, null,
                null, AttachmentKind.FILE, null, image));
        assertThrows(IllegalArgumentException.class, () -> service.upload(
                12L, ATTACHMENT_ID, null,
                null, null, AttachmentRetentionClass.EVIDENCE, image));
        assertThrows(IllegalArgumentException.class, () -> service.upload(
                12L, ATTACHMENT_ID, null,
                null, null, AttachmentRetentionClass.TRANSIENT, image));

        verify(workerClient, never()).health();
        verify(workerClient, never()).put(any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(metadataService, never()).index(any(), any());
        assertSpoolEmpty();
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
        when(metadataService.list(12L, 50)).thenReturn(List.of(metadata));
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
        assertEquals(List.of(metadata), service.list(12L, 50));
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
        assertSpoolEmpty();
    }

    @Test
    void removesPrivateSpoolWhenWorkerFails() throws Exception {
        WorkSessionEntity session = remoteSession(12L, 7L, "synthetic-attachments");
        when(workSessionRepository.findWithProjectById(12L)).thenReturn(Optional.of(session));
        when(workerClient.health()).thenReturn(health());
        when(workerClient.put(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new AttachmentWorkerException(
                        "synthetic worker failure",
                        503,
                        "attachment_worker_unavailable"));

        assertThrows(AttachmentWorkerException.class, () -> service.upload(
                12L,
                ATTACHMENT_ID,
                null,
                null,
                null,
                null,
                new MockMultipartFile("file", "screen.png", "image/png", PNG)));

        verify(metadataService, never()).index(any(), any());
        assertSpoolEmpty();
    }

    @Test
    void removesPrivateSpoolAndNewObjectWhenWorkerIdentityIsInvalid() {
        WorkSessionEntity session = remoteSession(12L, 7L, "synthetic-attachments");
        when(workSessionRepository.findWithProjectById(12L)).thenReturn(Optional.of(session));
        when(workerClient.health()).thenReturn(health());
        when(workerClient.put(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new AttachmentWorkerClient.PutResult(
                        true,
                        new AttachmentWorkerClient.StoredAttachment(
                                AttachmentProperties.PROTOCOL,
                                "ax42-01",
                                "12",
                                ATTACHMENT_ID,
                                "work-sessions/12/opaque",
                                AttachmentSource.OPERATOR_UPLOAD,
                                AttachmentKind.IMAGE,
                                "image/png",
                                PNG.length,
                                AttachmentRetentionClass.SESSION,
                                "0".repeat(64),
                                true,
                                Instant.parse("2026-08-01T23:00:00Z"),
                                Instant.parse("2026-08-01T23:00:01Z"))));

        assertThrows(AttachmentWorkerException.class, () -> service.upload(
                12L,
                ATTACHMENT_ID,
                null,
                null,
                null,
                null,
                new MockMultipartFile("file", "screen.png", "image/png", PNG)));

        verify(workerClient).deleteSynthetic(12L, ATTACHMENT_ID);
        verify(metadataService, never()).index(any(), any());
        assertSpoolEmpty();
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
        return stored(contentType, sha256, AttachmentKind.IMAGE, PNG.length);
    }

    private AttachmentWorkerClient.StoredAttachment stored(
            String contentType,
            String sha256,
            AttachmentKind kind,
            int size
    ) {
        return new AttachmentWorkerClient.StoredAttachment(
                AttachmentProperties.PROTOCOL,
                "ax42-01",
                "12",
                ATTACHMENT_ID,
                "work-sessions/12/" + ATTACHMENT_ID + "/content",
                AttachmentSource.OPERATOR_UPLOAD,
                kind,
                contentType,
                size,
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

    private void assertSpoolEmpty() {
        Path spoolRoot = temporaryDirectory.resolve("spool");
        if (!Files.exists(spoolRoot)) {
            return;
        }
        try (var files = Files.list(spoolRoot)) {
            assertEquals(0L, files.count());
        } catch (java.io.IOException exception) {
            throw new AssertionError("Could not inspect the test spool", exception);
        }
    }
}
