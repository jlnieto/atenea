package com.atenea.service.worksession;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.atenea.api.worksession.CreateSessionTurnRequest;
import com.atenea.codexoperations.CodexExecutionProfileSnapshotService;
import com.atenea.persistence.project.ProjectEntity;
import com.atenea.persistence.project.ProjectRepository;
import com.atenea.persistence.worksession.AgentRunRepository;
import com.atenea.persistence.worksession.AttachmentKind;
import com.atenea.persistence.worksession.AttachmentRetentionClass;
import com.atenea.persistence.worksession.AttachmentSource;
import com.atenea.persistence.worksession.AttachmentStorageScope;
import com.atenea.persistence.worksession.ExecutionTarget;
import com.atenea.persistence.worksession.SessionTurnRepository;
import com.atenea.persistence.worksession.WorkSessionAttachmentEntity;
import com.atenea.persistence.worksession.WorkSessionAttachmentRepository;
import com.atenea.persistence.worksession.WorkSessionEntity;
import com.atenea.persistence.worksession.WorkSessionPullRequestStatus;
import com.atenea.persistence.worksession.WorkSessionRepository;
import com.atenea.persistence.worksession.WorkSessionStatus;
import com.atenea.persistence.worksession.WorkerNodeEntity;
import com.atenea.persistence.worksession.WorkerNodeRepository;
import com.atenea.remoteworker.CanonicalSourceAdmissionService;
import com.atenea.remoteworker.ProjectCodexIdentity;
import com.atenea.remoteworker.RemoteAgentRunCoordinator;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class ImageTurnAtomicPersistenceIntegrationTest {

    @Autowired
    private SessionTurnService sessionTurnService;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private WorkSessionRepository workSessionRepository;

    @Autowired
    private WorkerNodeRepository workerNodeRepository;

    @Autowired
    private WorkSessionAttachmentRepository attachmentRepository;

    @Autowired
    private SessionTurnRepository sessionTurnRepository;

    @Autowired
    private AgentRunRepository agentRunRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private TurnAttachmentSelectionValidator selectionValidator;

    @MockBean
    private CanonicalSourceAdmissionService canonicalSourceAdmissionService;

    @MockBean
    private AgentRunReconciliationService agentRunReconciliationService;

    @MockBean
    private RemoteAgentRunCoordinator remoteAgentRunCoordinator;

    @MockBean
    private CodexExecutionProfileSnapshotService codexExecutionProfileSnapshotService;

    @Test
    void secondBindingFailureRollsBackTurnFirstBindingAndAgentRunBeforeDispatch() {
        Instant now = Instant.parse("2026-08-02T00:00:00Z");
        WorkerNodeEntity worker = createWorker(now);
        ProjectEntity project = createProject(now);
        WorkSessionEntity session = createSession(project, worker, now);
        WorkSessionAttachmentEntity retained = createAttachment(session, project, worker, now);
        UUID missingSecondId = UUID.fromString("9aa2c7e5-1fd9-48ec-aa10-03dfdfb8ca7d");
        TurnAttachmentSelectionValidator.ValidatedSelection selection =
                new TurnAttachmentSelectionValidator.ValidatedSelection(
                        List.of(
                                new TurnAttachmentSelectionValidator.ValidatedAttachment(
                                        retained.getId(),
                                        retained.getContentType(),
                                        retained.getSizeBytes(),
                                        retained.getSha256()),
                                new TurnAttachmentSelectionValidator.ValidatedAttachment(
                                        missingSecondId,
                                        "image/webp",
                                        2048L,
                                        "b".repeat(64))),
                        3072L,
                        "c".repeat(64));
        when(selectionValidator.validate(
                any(WorkSessionEntity.class),
                eq(List.of(retained.getId(), missingSecondId))))
                .thenReturn(selection);

        long turnsBefore = sessionTurnRepository.count();
        long runsBefore = agentRunRepository.count();
        long bindingsBefore = bindingCount();
        Instant activityBefore = session.getLastActivityAt();

        assertThrows(
                DataIntegrityViolationException.class,
                () -> sessionTurnService.createTurn(
                        session.getId(),
                        new CreateSessionTurnRequest(
                                "Inspect both images",
                                UUID.fromString("7b35f774-97f2-4a9e-b7db-0f18d59112ba"),
                                List.of(retained.getId(), missingSecondId))));

        assertEquals(turnsBefore, sessionTurnRepository.count());
        assertEquals(runsBefore, agentRunRepository.count());
        assertEquals(bindingsBefore, bindingCount());
        assertEquals(activityBefore,
                workSessionRepository.findById(session.getId()).orElseThrow().getLastActivityAt());
        assertEquals(1L, attachmentRepository.count());
        verify(remoteAgentRunCoordinator, never()).dispatchAfterCommit(any());
    }

    private long bindingCount() {
        Long value = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM session_turn_attachment",
                Long.class);
        return value == null ? 0L : value;
    }

    private WorkerNodeEntity createWorker(Instant now) {
        WorkerNodeEntity worker = workerNodeRepository.findById("ax42-01").orElseGet(() -> {
            WorkerNodeEntity created = new WorkerNodeEntity();
            created.setId("ax42-01");
            created.setProtocolVersion("agent-run-worker/v1");
            created.setEndpoint("https://worker.invalid");
            created.setEnabled(true);
            created.setHealthy(true);
            created.setNormalCapacity(4);
            created.setHeavyCapacity(1);
            created.setNormalInUse(0);
            created.setHeavyInUse(0);
            created.setCapabilities("project-codex-v1");
            created.setCreatedAt(now);
            created.setUpdatedAt(now);
            return created;
        });
        return workerNodeRepository.saveAndFlush(worker);
    }

    private ProjectEntity createProject(Instant now) {
        ProjectEntity project = new ProjectEntity();
        project.setName(ProjectCodexIdentity.PROJECT_NAME);
        project.setRepoPath(ProjectCodexIdentity.REPO_PATH);
        project.setDefaultBaseBranch(ProjectCodexIdentity.BRANCH);
        project.setCreatedAt(now);
        project.setUpdatedAt(now);
        return projectRepository.saveAndFlush(project);
    }

    private WorkSessionEntity createSession(
            ProjectEntity project,
            WorkerNodeEntity worker,
            Instant now
    ) {
        UUID remoteSessionId = UUID.randomUUID();
        WorkSessionEntity session = new WorkSessionEntity();
        session.setProject(project);
        session.setStatus(WorkSessionStatus.OPEN);
        session.setTitle("Atomic image turn fixture");
        session.setBaseBranch(ProjectCodexIdentity.BRANCH);
        session.setExecutionTarget(ExecutionTarget.REMOTE);
        session.setSelectedWorkerId(worker.getId());
        session.setRemoteSessionId(remoteSessionId);
        session.setRemoteWorkloadKind(ProjectCodexIdentity.WORKLOAD_KIND);
        session.setWorkspaceIdentity(
                "remote:" + worker.getId() + ":work-session:" + remoteSessionId);
        session.setAttachmentPolicyRevision("atenea-real-attachments-v1");
        session.setCanonicalSourceRef("refs/heads/" + ProjectCodexIdentity.BRANCH);
        session.setCanonicalSourceCommit("1".repeat(40));
        session.setCanonicalSourceObservationSha256("2".repeat(64));
        session.setCanonicalSourceObservedAt(now);
        session.setPullRequestStatus(WorkSessionPullRequestStatus.NOT_CREATED);
        session.setOpenedAt(now);
        session.setLastActivityAt(now);
        session.setCreatedAt(now);
        session.setUpdatedAt(now);
        return workSessionRepository.saveAndFlush(session);
    }

    private WorkSessionAttachmentEntity createAttachment(
            WorkSessionEntity session,
            ProjectEntity project,
            WorkerNodeEntity worker,
            Instant now
    ) {
        WorkSessionAttachmentEntity attachment = new WorkSessionAttachmentEntity();
        attachment.setId(UUID.fromString("4e8f351e-e05a-41b6-99e5-3eb72d770002"));
        attachment.setWorkSession(session);
        attachment.setProject(project);
        attachment.setSource(AttachmentSource.OPERATOR_UPLOAD);
        attachment.setKind(AttachmentKind.IMAGE);
        attachment.setOriginalFilename("sanitized-image");
        attachment.setContentType("image/png");
        attachment.setSizeBytes(1024L);
        attachment.setRetentionClass(AttachmentRetentionClass.SESSION);
        attachment.setRetainUntil(Instant.parse("2030-01-01T00:00:00Z"));
        attachment.setSha256("a".repeat(64));
        attachment.setWorkerId(worker.getId());
        attachment.setStorageIdentity("opaque-storage-identity");
        attachment.setStorageScope(AttachmentStorageScope.REAL_SESSION);
        attachment.setRemoteSessionId(session.getRemoteSessionId());
        attachment.setWorkspaceIdentity(session.getWorkspaceIdentity());
        attachment.setCreatedAt(now);
        attachment.setIndexedAt(now);
        return attachmentRepository.saveAndFlush(attachment);
    }
}
