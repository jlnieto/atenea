package com.atenea.service.worksession;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.atenea.attachments.AttachmentAdmissionPolicy;
import com.atenea.attachments.AttachmentProperties;
import com.atenea.attachments.AttachmentWorkerClient;
import com.atenea.attachments.AttachmentWorkerException;
import com.atenea.attachments.RealAttachmentProjectRegistry;
import com.atenea.persistence.project.ProjectEntity;
import com.atenea.persistence.worksession.AttachmentKind;
import com.atenea.persistence.worksession.AttachmentRetentionClass;
import com.atenea.persistence.worksession.AttachmentSource;
import com.atenea.persistence.worksession.AttachmentStorageScope;
import com.atenea.persistence.worksession.ExecutionTarget;
import com.atenea.persistence.worksession.WorkSessionAttachmentEntity;
import com.atenea.persistence.worksession.WorkSessionAttachmentRepository;
import com.atenea.persistence.worksession.WorkSessionEntity;
import com.atenea.remoteworker.ProjectCodexIdentity;
import java.time.Instant;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TurnAttachmentSelectionValidatorTest {

    private static final UUID FIRST_ID =
            UUID.fromString("4e8f351e-e05a-41b6-99e5-3eb72d770002");
    private static final UUID SECOND_ID =
            UUID.fromString("9aa2c7e5-1fd9-48ec-aa10-03dfdfb8ca7d");
    private static final UUID THIRD_ID =
            UUID.fromString("6ca948ab-5974-42f8-ae0f-9f20921f1b85");

    @Mock
    private AttachmentWorkerClient workerClient;

    @Mock
    private WorkSessionAttachmentRepository attachmentRepository;

    private AttachmentProperties properties;
    private TurnAttachmentSelectionValidator validator;

    @BeforeEach
    void setUp() {
        properties = new AttachmentProperties();
        properties.setEnabled(true);
        properties.setRealProjectAllowlist(Set.of(ProjectCodexIdentity.PROJECT_IDENTITY));
        RealAttachmentProjectRegistry registry = new RealAttachmentProjectRegistry(properties);
        validator = new TurnAttachmentSelectionValidator(
                properties,
                new AttachmentAdmissionPolicy(properties, registry),
                workerClient,
                attachmentRepository,
                new TurnAttachmentFingerprintService());
    }

    @Test
    void validatesTwoImagesInSubmittedOrderAndBuildsExactManifest() {
        WorkSessionEntity session = exactRealSession();
        WorkSessionAttachmentEntity first = image(session, FIRST_ID, "image/png", 1024L);
        WorkSessionAttachmentEntity second = image(session, SECOND_ID, "image/webp", 2048L);
        byte[] firstContent = new byte[1024];
        byte[] secondContent = new byte[2048];
        firstContent[0] = 1;
        secondContent[0] = 2;
        first.setSha256(sha256(firstContent));
        second.setSha256(sha256(secondContent));
        givenIndexed(first);
        givenIndexed(second);
        givenCompatibleWorker();
        when(workerClient.metadata(session.getRemoteSessionId(), FIRST_ID))
                .thenReturn(retained(session, first));
        when(workerClient.metadata(session.getRemoteSessionId(), SECOND_ID))
                .thenReturn(retained(session, second));
        when(workerClient.content(session.getRemoteSessionId(), FIRST_ID))
                .thenReturn(new AttachmentWorkerClient.Content("image/png", firstContent));
        when(workerClient.content(session.getRemoteSessionId(), SECOND_ID))
                .thenReturn(new AttachmentWorkerClient.Content("image/webp", secondContent));

        TurnAttachmentSelectionValidator.ValidatedSelection result =
                validator.validate(session, List.of(SECOND_ID, FIRST_ID));

        assertEquals(List.of(SECOND_ID, FIRST_ID),
                result.attachments().stream()
                        .map(TurnAttachmentSelectionValidator.ValidatedAttachment::id)
                        .toList());
        assertEquals(3072L, result.totalBytes());
        assertTrue(result.manifestSha256().matches("^[0-9a-f]{64}$"));
        verify(workerClient).metadata(session.getRemoteSessionId(), SECOND_ID);
        verify(workerClient).metadata(session.getRemoteSessionId(), FIRST_ID);
        verify(workerClient).content(session.getRemoteSessionId(), SECOND_ID);
        verify(workerClient).content(session.getRemoteSessionId(), FIRST_ID);
    }

    @Test
    void rejectsEmptyOverBoundAndDuplicateSelectionsBeforeRepositoryOrWorker() {
        WorkSessionEntity session = exactRealSession();

        assertThrows(IllegalArgumentException.class,
                () -> validator.validate(session, List.of()));
        assertThrows(AttachmentLimitException.class,
                () -> validator.validate(
                        session,
                        List.of(FIRST_ID, SECOND_ID, THIRD_ID, UUID.randomUUID(), UUID.randomUUID())));
        assertThrows(AttachmentConflictException.class,
                () -> validator.validate(session, List.of(FIRST_ID, FIRST_ID)));

        verifyNoInteractions(attachmentRepository, workerClient);
    }

    @Test
    void rejectsForeignAttachmentWithoutProbingWorker() {
        WorkSessionEntity session = exactRealSession();
        when(attachmentRepository.findByIdAndWorkSessionId(FIRST_ID, 12L))
                .thenReturn(Optional.empty());

        assertThrows(AttachmentOwnershipException.class,
                () -> validator.validate(session, List.of(FIRST_ID)));

        verifyNoInteractions(workerClient);
    }

    @Test
    void rejectsPartialRealScopeAndLeavesWorkerUntouched() {
        WorkSessionEntity session = exactRealSession();
        WorkSessionAttachmentEntity attachment = image(session, FIRST_ID, "image/png", 1024L);
        attachment.setWorkspaceIdentity(
                "remote:ax42-01:work-session:" + UUID.randomUUID());
        givenIndexed(attachment);

        assertThrows(AttachmentOwnershipException.class,
                () -> validator.validate(session, List.of(FIRST_ID)));

        verifyNoInteractions(workerClient);
    }

    @Test
    void rejectsExpiredImageForNewBindingWithoutDeletingOrProbingWorker() {
        WorkSessionEntity session = exactRealSession();
        WorkSessionAttachmentEntity attachment = image(session, FIRST_ID, "image/png", 1024L);
        attachment.setRetainUntil(Instant.parse("2020-01-01T00:00:00Z"));
        givenIndexed(attachment);

        AttachmentConflictException exception = assertThrows(
                AttachmentConflictException.class,
                () -> validator.validate(session, List.of(FIRST_ID)));

        assertTrue(exception.getMessage().contains("vinculación nueva"));
        verifyNoInteractions(workerClient);
    }

    @Test
    void rejectsNonImageAndUnsupportedImageTypeBeforeWorker() {
        WorkSessionEntity session = exactRealSession();
        WorkSessionAttachmentEntity text = image(session, FIRST_ID, "text/plain", 1024L);
        text.setKind(AttachmentKind.FILE);
        givenIndexed(text);

        assertThrows(AttachmentOwnershipException.class,
                () -> validator.validate(session, List.of(FIRST_ID)));

        WorkSessionAttachmentEntity gif = image(session, SECOND_ID, "image/gif", 1024L);
        givenIndexed(gif);
        assertThrows(AttachmentOwnershipException.class,
                () -> validator.validate(session, List.of(SECOND_ID)));
        verifyNoInteractions(workerClient);
    }

    @Test
    void rejectsCombinedSizeAboveThirtyTwoMibBeforeWorker() {
        WorkSessionEntity session = exactRealSession();
        long twelveMib = 12L * 1024L * 1024L;
        givenIndexed(image(session, FIRST_ID, "image/png", twelveMib));
        givenIndexed(image(session, SECOND_ID, "image/jpeg", twelveMib));
        givenIndexed(image(session, THIRD_ID, "image/webp", twelveMib));

        assertThrows(AttachmentLimitException.class,
                () -> validator.validate(session, List.of(FIRST_ID, SECOND_ID, THIRD_ID)));

        verifyNoInteractions(workerClient);
    }

    @Test
    void rejectsIncompatibleWorkerBeforeReadingRetainedMetadata() {
        WorkSessionEntity session = exactRealSession();
        givenIndexed(image(session, FIRST_ID, "image/png", 1024L));
        when(workerClient.health()).thenReturn(new AttachmentWorkerClient.Health(
                "foreign/v1",
                RealAttachmentProjectRegistry.ATENEA_WORKER_ID,
                true,
                AttachmentProperties.DEFAULT_MAX_FILE_BYTES,
                AttachmentProperties.DEFAULT_MAX_SESSION_BYTES,
                AttachmentProperties.TURN_IMAGE_CONTENT_TYPES,
                Instant.now()));

        assertThrows(AttachmentWorkerException.class,
                () -> validator.validate(session, List.of(FIRST_ID)));

        verify(workerClient, never()).realProjectCapability();
        verify(workerClient, never()).metadata(session.getRemoteSessionId(), FIRST_ID);
    }

    @Test
    void rejectsRetainedIntegrityMismatchWithoutReadingContent() {
        WorkSessionEntity session = exactRealSession();
        WorkSessionAttachmentEntity attachment = image(session, FIRST_ID, "image/png", 1024L);
        givenIndexed(attachment);
        givenCompatibleWorker();
        AttachmentWorkerClient.StoredAttachment retained = retained(session, attachment);
        when(workerClient.metadata(session.getRemoteSessionId(), FIRST_ID))
                .thenReturn(new AttachmentWorkerClient.StoredAttachment(
                        retained.protocolVersion(),
                        retained.workerId(),
                        retained.sessionId(),
                        retained.attachmentId(),
                        retained.storageIdentity(),
                        retained.source(),
                        retained.kind(),
                        retained.contentType(),
                        retained.sizeBytes(),
                        retained.retentionClass(),
                        "b".repeat(64),
                        retained.syntheticFixture(),
                        retained.createdAt(),
                        retained.storedAt()));

        assertThrows(AttachmentOwnershipException.class,
                () -> validator.validate(session, List.of(FIRST_ID)));

        verify(workerClient, never()).content(session.getRemoteSessionId(), FIRST_ID);
        verify(workerClient, never()).deleteSynthetic(12L, FIRST_ID);
    }

    @Test
    void rejectsModifiedRetainedContentAfterExactMetadata() {
        WorkSessionEntity session = exactRealSession();
        WorkSessionAttachmentEntity attachment = image(session, FIRST_ID, "image/png", 1024L);
        byte[] original = new byte[1024];
        attachment.setSha256(sha256(original));
        givenIndexed(attachment);
        givenCompatibleWorker();
        when(workerClient.metadata(session.getRemoteSessionId(), FIRST_ID))
                .thenReturn(retained(session, attachment));
        byte[] modified = new byte[1024];
        modified[0] = 1;
        when(workerClient.content(session.getRemoteSessionId(), FIRST_ID))
                .thenReturn(new AttachmentWorkerClient.Content("image/png", modified));

        assertThrows(AttachmentOwnershipException.class,
                () -> validator.validate(session, List.of(FIRST_ID)));

        verify(workerClient, never()).deleteSynthetic(12L, FIRST_ID);
    }

    private void givenIndexed(WorkSessionAttachmentEntity attachment) {
        when(attachmentRepository.findByIdAndWorkSessionId(
                attachment.getId(),
                attachment.getWorkSession().getId()))
                .thenReturn(Optional.of(attachment));
    }

    private void givenCompatibleWorker() {
        when(workerClient.health()).thenReturn(new AttachmentWorkerClient.Health(
                AttachmentProperties.PROTOCOL,
                RealAttachmentProjectRegistry.ATENEA_WORKER_ID,
                true,
                AttachmentProperties.DEFAULT_MAX_FILE_BYTES,
                AttachmentProperties.DEFAULT_MAX_SESSION_BYTES,
                AttachmentProperties.TURN_IMAGE_CONTENT_TYPES,
                Instant.now()));
        when(workerClient.realProjectCapability()).thenReturn(
                new AttachmentWorkerClient.RealProjectCapability(
                        AttachmentWorkerClient.REAL_PROJECT_PROTOCOL,
                        RealAttachmentProjectRegistry.ATENEA_WORKER_ID,
                        true,
                        List.of(ProjectCodexIdentity.PROJECT_IDENTITY),
                        List.of(AttachmentStorageScope.REAL_SESSION),
                        Instant.now()));
    }

    private WorkSessionEntity exactRealSession() {
        ProjectEntity project = new ProjectEntity();
        project.setId(7L);
        project.setName(ProjectCodexIdentity.PROJECT_NAME);
        project.setRepoPath(ProjectCodexIdentity.REPO_PATH);

        WorkSessionEntity session = new WorkSessionEntity();
        session.setId(12L);
        session.setProject(project);
        session.setBaseBranch(ProjectCodexIdentity.BRANCH);
        session.setExecutionTarget(ExecutionTarget.REMOTE);
        session.setSelectedWorkerId(RealAttachmentProjectRegistry.ATENEA_WORKER_ID);
        UUID remoteSessionId = UUID.fromString("a1c3af50-af6e-4cc2-85d6-a491c50cddcc");
        session.setRemoteSessionId(remoteSessionId);
        session.setRemoteWorkloadKind(ProjectCodexIdentity.WORKLOAD_KIND);
        session.setWorkspaceIdentity(
                "remote:" + RealAttachmentProjectRegistry.ATENEA_WORKER_ID
                        + ":work-session:" + remoteSessionId);
        session.setAttachmentPolicyRevision(RealAttachmentProjectRegistry.ATENEA_POLICY_REVISION);
        return session;
    }

    private WorkSessionAttachmentEntity image(
            WorkSessionEntity session,
            UUID id,
            String contentType,
            long sizeBytes
    ) {
        WorkSessionAttachmentEntity attachment = new WorkSessionAttachmentEntity();
        attachment.setId(id);
        attachment.setWorkSession(session);
        attachment.setProject(session.getProject());
        attachment.setSource(AttachmentSource.OPERATOR_UPLOAD);
        attachment.setKind(AttachmentKind.IMAGE);
        attachment.setOriginalFilename("sanitized-image");
        attachment.setContentType(contentType);
        attachment.setSizeBytes(sizeBytes);
        attachment.setRetentionClass(AttachmentRetentionClass.SESSION);
        attachment.setRetainUntil(Instant.parse("2030-01-01T00:00:00Z"));
        attachment.setSha256("a".repeat(64));
        attachment.setWorkerId(session.getSelectedWorkerId());
        attachment.setStorageIdentity("opaque-storage-identity-" + id);
        attachment.setStorageScope(AttachmentStorageScope.REAL_SESSION);
        attachment.setRemoteSessionId(session.getRemoteSessionId());
        attachment.setWorkspaceIdentity(session.getWorkspaceIdentity());
        attachment.setCreatedAt(Instant.parse("2026-08-01T22:00:00Z"));
        attachment.setIndexedAt(Instant.parse("2026-08-01T22:00:01Z"));
        return attachment;
    }

    private AttachmentWorkerClient.StoredAttachment retained(
            WorkSessionEntity session,
            WorkSessionAttachmentEntity attachment
    ) {
        return new AttachmentWorkerClient.StoredAttachment(
                AttachmentProperties.PROTOCOL,
                session.getSelectedWorkerId(),
                session.getRemoteSessionId().toString(),
                attachment.getId(),
                attachment.getStorageIdentity(),
                attachment.getSource(),
                attachment.getKind(),
                attachment.getContentType(),
                attachment.getSizeBytes(),
                attachment.getRetentionClass(),
                attachment.getSha256(),
                false,
                attachment.getCreatedAt(),
                Instant.parse("2026-08-01T22:00:00.500Z"));
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
