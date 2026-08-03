package com.atenea.service.worksession;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.atenea.api.worksession.CreateSessionTurnRequest;
import com.atenea.api.worksession.CreateSessionTurnResponse;
import com.atenea.codexoperations.CodexExecutionProfileSnapshotService;
import com.atenea.persistence.project.ProjectEntity;
import com.atenea.persistence.project.ProjectRepository;
import com.atenea.persistence.worksession.AgentRunEntity;
import com.atenea.persistence.worksession.AgentRunRepository;
import com.atenea.persistence.worksession.AttachmentKind;
import com.atenea.persistence.worksession.AttachmentRetentionClass;
import com.atenea.persistence.worksession.AttachmentSource;
import com.atenea.persistence.worksession.AttachmentStorageScope;
import com.atenea.persistence.worksession.CodexReasoningEffort;
import com.atenea.persistence.worksession.ExecutionProfileSource;
import com.atenea.persistence.worksession.ExecutionTarget;
import com.atenea.persistence.worksession.SessionTurnActor;
import com.atenea.persistence.worksession.SessionTurnEntity;
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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;

@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ImageTurnBackendRestartIntegrationTest {

    private static final UUID ATTACHMENT_ID =
            UUID.fromString("4e8f351e-e05a-41b6-99e5-3eb72d770002");
    private static final UUID CLIENT_REQUEST_ID =
            UUID.fromString("7b35f774-97f2-4a9e-b7db-0f18d59112ba");
    private static final String MESSAGE = "Synthetic restart continuity request";

    @Autowired
    private SessionTurnService sessionTurnService;

    @Autowired
    private AgentRunService agentRunService;

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
    private TurnAttachmentFingerprintService fingerprintService;

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

    private Long fixtureProjectId;
    private Long fixtureSessionId;
    private boolean fixtureWorkerCreated;
    private Long acceptedTurnId;
    private Long acceptedRunId;
    private Long resultTurnId;
    private String acceptedManifest;
    private String retainedStorageIdentity;
    private long turnsAfterAcceptance;
    private long runsAfterAcceptance;
    private long bindingsAfterAcceptance;
    private long attachmentsAfterAcceptance;

    @Test
    @Order(1)
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void persistsOneTerminalImageAcceptanceBeforeBackendRestart() {
        Instant now = Instant.parse("2026-08-02T00:00:00Z");
        WorkerNodeEntity worker = createWorker(now);
        ProjectEntity project = createProject(now);
        WorkSessionEntity session = createSession(project, worker, now);
        WorkSessionAttachmentEntity attachment = createAttachment(session, project, worker, now);
        TurnAttachmentSelectionValidator.ValidatedSelection selection = selection(attachment);
        when(selectionValidator.validate(
                any(WorkSessionEntity.class),
                eq(List.of(ATTACHMENT_ID))))
                .thenReturn(selection);
        doAnswer(invocation -> {
            AgentRunEntity run = invocation.getArgument(0);
            run.setCodexModelId("gpt-5.6-sol");
            run.setCodexModelSource(ExecutionProfileSource.WORK_SESSION);
            run.setCodexReasoningEffort(CodexReasoningEffort.HIGH);
            run.setCodexEffortSource(ExecutionProfileSource.WORK_SESSION);
            run.setCodexCatalogRevision("d".repeat(64));
            run.setCodexVersion("0.145.0");
            return null;
        }).when(codexExecutionProfileSnapshotService).applyCurrentProfile(any(AgentRunEntity.class));

        CreateSessionTurnResponse accepted = sessionTurnService.createTurn(
                session.getId(),
                new CreateSessionTurnRequest(MESSAGE, CLIENT_REQUEST_ID, List.of(ATTACHMENT_ID)));
        AgentRunEntity run = agentRunRepository.findById(accepted.run().id()).orElseThrow();
        SessionTurnEntity resultTurn = new SessionTurnEntity();
        resultTurn.setSession(session);
        resultTurn.setActor(SessionTurnActor.CODEX);
        resultTurn.setMessageText("Synthetic terminal result");
        resultTurn.setInternal(false);
        resultTurn.setCreatedAt(now.plusSeconds(1));
        resultTurn = sessionTurnRepository.saveAndFlush(resultTurn);
        session.setExternalThreadId("bcf43e2e-c9e8-42df-96b2-e9183462c2f4");
        workSessionRepository.saveAndFlush(session);
        run = agentRunService.markSucceeded(
                run.getId(),
                "turn-synthetic-terminal",
                "Synthetic terminal success",
                resultTurn);

        fixtureSessionId = session.getId();
        acceptedTurnId = accepted.operatorTurn().id();
        acceptedRunId = run.getId();
        resultTurnId = resultTurn.getId();
        acceptedManifest = run.getAttachmentManifestSha256();
        retainedStorageIdentity = attachment.getStorageIdentity();
        turnsAfterAcceptance = sessionTurnRepository.count();
        runsAfterAcceptance = agentRunRepository.count();
        bindingsAfterAcceptance = bindingCount();
        attachmentsAfterAcceptance = attachmentRepository.count();

        assertEquals(1, accepted.operatorTurn().attachments().size());
        assertEquals(1, run.getAttachmentCount());
        assertEquals(attachment.getSizeBytes(), run.getAttachmentBytes());
        assertEquals(selection.manifestSha256(), acceptedManifest);
        assertEquals(resultTurnId, run.getResultTurn().getId());
        verify(remoteAgentRunCoordinator).dispatchAfterCommit(acceptedRunId);
    }

    @Test
    @Order(2)
    void replayAfterBackendRestartReturnsExactAcceptanceWithoutUploadOrDispatch() {
        WorkSessionEntity session = workSessionRepository.findById(fixtureSessionId).orElseThrow();
        WorkSessionAttachmentEntity attachment = attachmentRepository
                .findByIdAndWorkSessionId(ATTACHMENT_ID, fixtureSessionId)
                .orElseThrow();
        AgentRunEntity persistedRun = agentRunRepository.findById(acceptedRunId).orElseThrow();

        CreateSessionTurnResponse replay = sessionTurnService.createTurn(
                fixtureSessionId,
                new CreateSessionTurnRequest(MESSAGE, CLIENT_REQUEST_ID, List.of(ATTACHMENT_ID)));

        assertEquals(acceptedTurnId, replay.operatorTurn().id());
        assertEquals(acceptedRunId, replay.run().id());
        assertEquals(resultTurnId, replay.run().resultTurnId());
        assertEquals(acceptedManifest, persistedRun.getAttachmentManifestSha256());
        assertEquals(retainedStorageIdentity, attachment.getStorageIdentity());
        assertEquals("bcf43e2e-c9e8-42df-96b2-e9183462c2f4", session.getExternalThreadId());
        assertEquals(turnsAfterAcceptance, sessionTurnRepository.count());
        assertEquals(runsAfterAcceptance, agentRunRepository.count());
        assertEquals(bindingsAfterAcceptance, bindingCount());
        assertEquals(attachmentsAfterAcceptance, attachmentRepository.count());
        assertEquals(List.of(ATTACHMENT_ID), replay.operatorTurn().attachments().stream()
                .map(projected -> projected.id()).toList());
        verify(selectionValidator, never()).validate(any(), any());
        verify(remoteAgentRunCoordinator, never()).dispatchAfterCommit(any());
    }

    @AfterAll
    void removeExactFixtureRows() {
        if (fixtureSessionId != null) {
            jdbcTemplate.update(
                    "DELETE FROM session_turn_attachment WHERE work_session_id = ?",
                    fixtureSessionId);
            jdbcTemplate.update("DELETE FROM agent_run WHERE session_id = ?", fixtureSessionId);
            jdbcTemplate.update("DELETE FROM session_turn WHERE session_id = ?", fixtureSessionId);
            jdbcTemplate.update(
                    "DELETE FROM work_session_attachment WHERE work_session_id = ?",
                    fixtureSessionId);
            jdbcTemplate.update("DELETE FROM work_session WHERE id = ?", fixtureSessionId);
        }
        if (fixtureProjectId != null) {
            jdbcTemplate.update("DELETE FROM project WHERE id = ?", fixtureProjectId);
        }
        if (fixtureWorkerCreated) {
            jdbcTemplate.update(
                    "DELETE FROM worker_codex_activation_barrier WHERE worker_id = ?",
                    "ax42-01");
            jdbcTemplate.update("DELETE FROM worker_node WHERE id = ?", "ax42-01");
        }
    }

    private long bindingCount() {
        Long value = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM session_turn_attachment",
                Long.class);
        return value == null ? 0L : value;
    }

    private WorkerNodeEntity createWorker(Instant now) {
        WorkerNodeEntity worker = workerNodeRepository.findById("ax42-01").orElseGet(() -> {
            fixtureWorkerCreated = true;
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
        ProjectEntity saved = projectRepository.saveAndFlush(project);
        fixtureProjectId = saved.getId();
        return saved;
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
        session.setTitle("Backend restart image fixture");
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
        WorkSessionEntity saved = workSessionRepository.saveAndFlush(session);
        fixtureSessionId = saved.getId();
        return saved;
    }

    private WorkSessionAttachmentEntity createAttachment(
            WorkSessionEntity session,
            ProjectEntity project,
            WorkerNodeEntity worker,
            Instant now
    ) {
        WorkSessionAttachmentEntity attachment = new WorkSessionAttachmentEntity();
        attachment.setId(ATTACHMENT_ID);
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
        attachment.setStorageIdentity("opaque-restart-storage-identity");
        attachment.setStorageScope(AttachmentStorageScope.REAL_SESSION);
        attachment.setRemoteSessionId(session.getRemoteSessionId());
        attachment.setWorkspaceIdentity(session.getWorkspaceIdentity());
        attachment.setCreatedAt(now);
        attachment.setIndexedAt(now);
        return attachmentRepository.saveAndFlush(attachment);
    }

    private TurnAttachmentSelectionValidator.ValidatedSelection selection(
            WorkSessionAttachmentEntity attachment
    ) {
        TurnAttachmentFingerprintService.AttachmentFingerprintInput input =
                new TurnAttachmentFingerprintService.AttachmentFingerprintInput(
                        attachment.getId(),
                        attachment.getContentType(),
                        attachment.getSizeBytes(),
                        attachment.getSha256());
        return new TurnAttachmentSelectionValidator.ValidatedSelection(
                List.of(new TurnAttachmentSelectionValidator.ValidatedAttachment(
                        attachment.getId(),
                        attachment.getContentType(),
                        attachment.getSizeBytes(),
                        attachment.getSha256())),
                attachment.getSizeBytes(),
                fingerprintService.attachmentManifestSha256(List.of(input)));
    }
}
