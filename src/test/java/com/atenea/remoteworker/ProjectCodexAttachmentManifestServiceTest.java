package com.atenea.remoteworker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.atenea.persistence.project.ProjectEntity;
import com.atenea.persistence.worksession.AgentRunEntity;
import com.atenea.persistence.worksession.AttachmentKind;
import com.atenea.persistence.worksession.AttachmentRetentionClass;
import com.atenea.persistence.worksession.AttachmentSource;
import com.atenea.persistence.worksession.AttachmentStorageScope;
import com.atenea.persistence.worksession.ExecutionTarget;
import com.atenea.persistence.worksession.SessionTurnAttachmentEntity;
import com.atenea.persistence.worksession.SessionTurnAttachmentRepository;
import com.atenea.persistence.worksession.SessionTurnEntity;
import com.atenea.persistence.worksession.WorkSessionAttachmentEntity;
import com.atenea.persistence.worksession.WorkSessionAttachmentRepository;
import com.atenea.persistence.worksession.WorkSessionEntity;
import com.atenea.service.worksession.TurnAttachmentFingerprintService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProjectCodexAttachmentManifestServiceTest {

    private static final UUID FIRST_ID =
            UUID.fromString("4e8f351e-e05a-41b6-99e5-3eb72d770002");
    private static final UUID SECOND_ID =
            UUID.fromString("9aa2c7e5-1fd9-48ec-aa10-03dfdfb8ca7d");

    @Mock
    private SessionTurnAttachmentRepository bindingRepository;

    @Mock
    private WorkSessionAttachmentRepository attachmentRepository;

    private TurnAttachmentFingerprintService fingerprintService;
    private ProjectCodexAttachmentManifestService service;

    @BeforeEach
    void setUp() {
        fingerprintService = new TurnAttachmentFingerprintService();
        service = new ProjectCodexAttachmentManifestService(
                bindingRepository, attachmentRepository, fingerprintService);
    }

    @Test
    void reconstructsOnlyExactOrderedReferencesMatchingPersistedManifest() {
        AgentRunEntity run = imageRun();
        WorkSessionAttachmentEntity first = image(
                run.getSession(), FIRST_ID, "image/png", 1024L, "a".repeat(64));
        WorkSessionAttachmentEntity second = image(
                run.getSession(), SECOND_ID, "image/webp", 2048L, "b".repeat(64));
        run.setAttachmentManifestSha256(manifest(first, second));
        givenBindings(run, FIRST_ID, SECOND_ID);
        when(attachmentRepository.findAllById(any()))
                .thenReturn(List.of(second, first));

        List<ProjectCodexAttachmentManifestService.AttachmentReference> result =
                service.exactReferences(run);

        assertEquals(List.of(FIRST_ID, SECOND_ID), result.stream()
                .map(ProjectCodexAttachmentManifestService.AttachmentReference::attachmentId)
                .toList());
        assertEquals(List.of("image/png", "image/webp"), result.stream()
                .map(ProjectCodexAttachmentManifestService.AttachmentReference::contentType)
                .toList());
        assertEquals(List.of(1024L, 2048L), result.stream()
                .map(ProjectCodexAttachmentManifestService.AttachmentReference::sizeBytes)
                .toList());
    }

    @Test
    void rejectsChangedReferenceOrForeignOwnershipBeforeSerialization() {
        AgentRunEntity run = imageRun();
        WorkSessionAttachmentEntity first = image(
                run.getSession(), FIRST_ID, "image/png", 1024L, "a".repeat(64));
        WorkSessionAttachmentEntity second = image(
                run.getSession(), SECOND_ID, "image/webp", 2048L, "b".repeat(64));
        run.setAttachmentManifestSha256(manifest(first, second));
        givenBindings(run, FIRST_ID, SECOND_ID);
        second.setSha256("c".repeat(64));
        when(attachmentRepository.findAllById(any()))
                .thenReturn(List.of(first, second));

        RemoteWorkerException changed = assertThrows(
                RemoteWorkerException.class,
                () -> service.exactReferences(run));
        assertEquals(409, changed.getStatusCode());

        second.setSha256("b".repeat(64));
        second.setWorkspaceIdentity("remote:foreign:work-session:" + run.getRemoteSessionId());
        RemoteWorkerException foreign = assertThrows(
                RemoteWorkerException.class,
                () -> service.exactReferences(run));
        assertEquals(409, foreign.getStatusCode());
    }

    private AgentRunEntity imageRun() {
        ProjectEntity project = new ProjectEntity();
        project.setId(7L);
        project.setName(ProjectCodexIdentity.PROJECT_NAME);
        project.setRepoPath(ProjectCodexIdentity.REPO_PATH);
        UUID remoteSessionId = UUID.fromString("4bb26a65-0a0a-4ae0-b8e0-b41e03a695bf");
        WorkSessionEntity session = new WorkSessionEntity();
        session.setId(41L);
        session.setProject(project);
        session.setBaseBranch(ProjectCodexIdentity.BRANCH);
        session.setSelectedWorkerId("ax42-01");
        session.setExecutionTarget(ExecutionTarget.REMOTE);
        session.setRemoteSessionId(remoteSessionId);
        session.setRemoteWorkloadKind(ProjectCodexIdentity.WORKLOAD_KIND);
        session.setWorkspaceIdentity("remote:ax42-01:work-session:" + remoteSessionId);
        session.setAttachmentPolicyRevision("atenea-real-attachments-v1");
        session.setCanonicalSourceRef("refs/heads/" + ProjectCodexIdentity.BRANCH);
        session.setCanonicalSourceCommit("1".repeat(40));
        session.setCanonicalSourceObservationSha256("2".repeat(64));
        session.setCanonicalSourceObservedAt(Instant.now());
        SessionTurnEntity originTurn = new SessionTurnEntity();
        originTurn.setId(91L);
        originTurn.setSession(session);
        AgentRunEntity run = new AgentRunEntity();
        run.setSession(session);
        run.setOriginTurn(originTurn);
        run.setSelectedWorkerId("ax42-01");
        run.setRemoteSessionId(remoteSessionId);
        run.setWorkspaceIdentity(session.getWorkspaceIdentity());
        run.setWorkloadKind(ProjectCodexIdentity.IMAGE_WORKLOAD_KIND);
        run.setProjectIdentity(ProjectCodexIdentity.PROJECT_IDENTITY);
        run.setRepositoryUrl(ProjectCodexIdentity.REPOSITORY);
        run.setRepositoryBranch(ProjectCodexIdentity.BRANCH);
        run.setRepositoryCommit(session.getCanonicalSourceCommit());
        run.setManifestSha256(ProjectCodexIdentity.MANIFEST_SHA256);
        ReviewedInstructionBundleIdentity.apply(run, ProjectCodexIdentity.PROJECT_IDENTITY);
        run.setAttachmentCount(2);
        run.setAttachmentBytes(3072L);
        run.setAttachmentManifestSha256("f".repeat(64));
        return run;
    }

    private WorkSessionAttachmentEntity image(
            WorkSessionEntity session,
            UUID id,
            String contentType,
            long sizeBytes,
            String sha256
    ) {
        WorkSessionAttachmentEntity attachment = new WorkSessionAttachmentEntity();
        attachment.setId(id);
        attachment.setWorkSession(session);
        attachment.setProject(session.getProject());
        attachment.setSource(AttachmentSource.OPERATOR_UPLOAD);
        attachment.setKind(AttachmentKind.IMAGE);
        attachment.setContentType(contentType);
        attachment.setSizeBytes(sizeBytes);
        attachment.setRetentionClass(AttachmentRetentionClass.SESSION);
        attachment.setRetainUntil(Instant.parse("2030-01-01T00:00:00Z"));
        attachment.setSha256(sha256);
        attachment.setWorkerId(session.getSelectedWorkerId());
        attachment.setStorageIdentity("opaque-storage-identity-" + id);
        attachment.setStorageScope(AttachmentStorageScope.REAL_SESSION);
        attachment.setRemoteSessionId(session.getRemoteSessionId());
        attachment.setWorkspaceIdentity(session.getWorkspaceIdentity());
        attachment.setCreatedAt(Instant.parse("2026-08-01T22:00:00Z"));
        attachment.setIndexedAt(Instant.parse("2026-08-01T22:00:01Z"));
        return attachment;
    }

    private void givenBindings(AgentRunEntity run, UUID... ids) {
        List<SessionTurnAttachmentEntity> bindings = java.util.stream.IntStream
                .range(0, ids.length)
                .mapToObj(position -> {
                    SessionTurnAttachmentEntity binding = mock(SessionTurnAttachmentEntity.class);
                    when(binding.getPosition()).thenReturn((short) position);
                    when(binding.getAttachmentId()).thenReturn(ids[position]);
                    return binding;
                })
                .toList();
        when(bindingRepository.findByWorkSessionIdAndSessionTurnIdOrderByPositionAsc(
                run.getSession().getId(), run.getOriginTurn().getId()))
                .thenReturn(bindings);
    }

    private String manifest(WorkSessionAttachmentEntity... attachments) {
        return fingerprintService.attachmentManifestSha256(java.util.Arrays.stream(attachments)
                .map(attachment -> new TurnAttachmentFingerprintService.AttachmentFingerprintInput(
                        attachment.getId(),
                        attachment.getContentType(),
                        attachment.getSizeBytes(),
                        attachment.getSha256()))
                .toList());
    }
}
