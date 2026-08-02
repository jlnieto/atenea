package com.atenea.service.worksession;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.atenea.api.worksession.CreateSessionTurnRequest;
import com.atenea.api.worksession.CreateSessionTurnResponse;
import com.atenea.api.worksession.SessionTurnResponse;
import com.atenea.codexoperations.CodexExecutionProfileSnapshotService;
import com.atenea.persistence.project.ProjectEntity;
import com.atenea.persistence.project.ProjectRepository;
import com.atenea.persistence.worksession.AgentRunRepository;
import com.atenea.persistence.worksession.AgentRunEntity;
import com.atenea.persistence.worksession.AttachmentKind;
import com.atenea.persistence.worksession.AttachmentRetentionClass;
import com.atenea.persistence.worksession.AttachmentSource;
import com.atenea.persistence.worksession.AttachmentStorageScope;
import com.atenea.persistence.worksession.ExecutionTarget;
import com.atenea.persistence.worksession.ExecutionProfileSource;
import com.atenea.persistence.worksession.CodexReasoningEffort;
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
import org.junit.jupiter.api.AfterEach;
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
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TurnAttachmentFingerprintService fingerprintService;

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

    private Long fixtureSessionId;
    private Long fixtureProjectId;

    @AfterEach
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
        fixtureSessionId = null;
        fixtureProjectId = null;
    }

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

    @Test
    void identicalReplayReturnsOriginalAndConflictingReplayPreservesOneAcceptance() {
        Instant now = Instant.parse("2026-08-02T00:00:00Z");
        WorkerNodeEntity worker = createWorker(now);
        ProjectEntity project = createProject(now);
        WorkSessionEntity session = createSession(project, worker, now);
        WorkSessionAttachmentEntity retained = createAttachment(session, project, worker, now);
        UUID clientRequestId = UUID.fromString("7b35f774-97f2-4a9e-b7db-0f18d59112ba");
        List<TurnAttachmentFingerprintService.AttachmentFingerprintInput> fingerprintInputs =
                List.of(new TurnAttachmentFingerprintService.AttachmentFingerprintInput(
                        retained.getId(),
                        retained.getContentType(),
                        retained.getSizeBytes(),
                        retained.getSha256()));
        TurnAttachmentSelectionValidator.ValidatedSelection selection =
                new TurnAttachmentSelectionValidator.ValidatedSelection(
                        List.of(new TurnAttachmentSelectionValidator.ValidatedAttachment(
                                retained.getId(),
                                retained.getContentType(),
                                retained.getSizeBytes(),
                                retained.getSha256())),
                        retained.getSizeBytes(),
                        fingerprintService.attachmentManifestSha256(fingerprintInputs));
        when(selectionValidator.validate(
                any(WorkSessionEntity.class),
                eq(List.of(retained.getId()))))
                .thenReturn(selection);

        CreateSessionTurnRequest originalRequest = new CreateSessionTurnRequest(
                "Inspect this image",
                clientRequestId,
                List.of(retained.getId()));
        CreateSessionTurnResponse accepted = sessionTurnService.createTurn(
                session.getId(),
                originalRequest);
        assertEquals(1, accepted.operatorTurn().attachments().size());
        assertEquals(retained.getId(), accepted.operatorTurn().attachments().get(0).id());
        long turnsAfterAcceptance = sessionTurnRepository.count();
        long runsAfterAcceptance = agentRunRepository.count();
        long bindingsAfterAcceptance = bindingCount();

        CreateSessionTurnResponse replay = sessionTurnService.createTurn(
                session.getId(),
                originalRequest);

        assertEquals(accepted.operatorTurn().id(), replay.operatorTurn().id());
        assertEquals(accepted.run().id(), replay.run().id());
        assertThrows(
                AttachmentConflictException.class,
                () -> sessionTurnService.createTurn(
                        session.getId(),
                        new CreateSessionTurnRequest(
                                "Different image instruction",
                                clientRequestId,
                                List.of(retained.getId()))));
        assertEquals(turnsAfterAcceptance, sessionTurnRepository.count());
        assertEquals(runsAfterAcceptance, agentRunRepository.count());
        assertEquals(bindingsAfterAcceptance, bindingCount());
        assertEquals(1L, attachmentRepository.count());
        List<SessionTurnResponse> history = sessionTurnService.getTurns(session.getId());
        SessionTurnResponse historicalOperatorTurn = history.stream()
                .filter(turn -> turn.id().equals(accepted.operatorTurn().id()))
                .findFirst()
                .orElseThrow();
        assertEquals(1, historicalOperatorTurn.attachments().size());
        assertEquals(
                "/api/sessions/" + session.getId() + "/attachments/"
                        + retained.getId() + "/content",
                historicalOperatorTurn.attachments().get(0).downloadPath());
        verify(selectionValidator, times(1)).validate(
                any(WorkSessionEntity.class),
                eq(List.of(retained.getId())));
        verify(remoteAgentRunCoordinator, times(1)).dispatchAfterCommit(accepted.run().id());
    }

    @Test
    void safeRetryAfterRetentionReusesPersistedBindingAndOnlyAddsLinkedRun() {
        Instant now = Instant.parse("2026-07-01T00:00:00Z");
        WorkerNodeEntity worker = createWorker(now);
        ProjectEntity project = createProject(now);
        WorkSessionEntity session = createSession(project, worker, now);
        WorkSessionAttachmentEntity retained = createAttachment(session, project, worker, now);
        TurnAttachmentSelectionValidator.ValidatedSelection selection =
                new TurnAttachmentSelectionValidator.ValidatedSelection(
                        List.of(new TurnAttachmentSelectionValidator.ValidatedAttachment(
                                retained.getId(),
                                retained.getContentType(),
                                retained.getSizeBytes(),
                                retained.getSha256())),
                        retained.getSizeBytes(),
                        fingerprintService.attachmentManifestSha256(List.of(
                                new TurnAttachmentFingerprintService.AttachmentFingerprintInput(
                                        retained.getId(),
                                        retained.getContentType(),
                                        retained.getSizeBytes(),
                                        retained.getSha256()))));
        when(selectionValidator.validate(
                any(WorkSessionEntity.class),
                eq(List.of(retained.getId())))).thenReturn(selection);
        doAnswer(invocation -> {
            AgentRunEntity run = invocation.getArgument(0);
            run.setCodexModelId("gpt-5.6-sol");
            run.setCodexModelSource(ExecutionProfileSource.WORK_SESSION);
            run.setCodexReasoningEffort(CodexReasoningEffort.HIGH);
            run.setCodexEffortSource(ExecutionProfileSource.NEXT_TURN);
            run.setCodexCatalogRevision("d".repeat(64));
            run.setCodexVersion("0.145.0");
            return null;
        }).when(codexExecutionProfileSnapshotService).applyCurrentProfile(any(AgentRunEntity.class));
        CreateSessionTurnResponse accepted = sessionTurnService.createTurn(
                session.getId(),
                new CreateSessionTurnRequest(
                        "Inspect retained image",
                        UUID.fromString("7b35f774-97f2-4a9e-b7db-0f18d59112ba"),
                        List.of(retained.getId())));
        AgentRunEntity source = agentRunService.markFailed(
                accepted.run().id(), null, "Synthetic retry fixture failure");
        retained.setRetainUntil(Instant.parse("2026-07-02T00:00:00Z"));
        attachmentRepository.saveAndFlush(retained);
        when(selectionValidator.validateBoundRetry(
                any(WorkSessionEntity.class),
                eq(List.of(retained.getId())))).thenReturn(selection);
        long turnsBefore = sessionTurnRepository.count();
        long bindingsBefore = bindingCount();
        long attachmentsBefore = attachmentRepository.count();
        long runsBefore = agentRunRepository.count();
        String storageIdentityBefore = retained.getStorageIdentity();
        String workspaceIdentityBefore = retained.getWorkspaceIdentity();

        AgentRunEntity retry = agentRunService.createRemoteRetryRun(source.getId());

        assertEquals(runsBefore + 1, agentRunRepository.count());
        assertEquals(turnsBefore, sessionTurnRepository.count());
        assertEquals(bindingsBefore, bindingCount());
        assertEquals(attachmentsBefore, attachmentRepository.count());
        WorkSessionAttachmentEntity unchanged = attachmentRepository
                .findById(retained.getId()).orElseThrow();
        assertEquals(Instant.parse("2026-07-02T00:00:00Z"), unchanged.getRetainUntil());
        assertEquals(storageIdentityBefore, unchanged.getStorageIdentity());
        assertEquals(workspaceIdentityBefore, unchanged.getWorkspaceIdentity());
        assertEquals(source.getOriginTurn().getId(), retry.getOriginTurn().getId());
        assertEquals(source.getAttachmentManifestSha256(), retry.getAttachmentManifestSha256());
        assertEquals(source.getCodexModelId(), retry.getCodexModelId());
        assertEquals(source.getCodexModelSource(), retry.getCodexModelSource());
        assertEquals(source.getCodexReasoningEffort(), retry.getCodexReasoningEffort());
        assertEquals(source.getCodexEffortSource(), retry.getCodexEffortSource());
        assertEquals(source.getCodexCatalogRevision(), retry.getCodexCatalogRevision());
        assertEquals(source.getCodexVersion(), retry.getCodexVersion());
        verify(selectionValidator).validateBoundRetry(
                any(WorkSessionEntity.class),
                eq(List.of(retained.getId())));
    }

    @Test
    void explicitImageTurnsContinueOneThreadAndLaterTextTurnBindsNoImage() {
        Instant now = Instant.parse("2026-08-02T00:00:00Z");
        WorkerNodeEntity worker = createWorker(now);
        ProjectEntity project = createProject(now);
        WorkSessionEntity session = createSession(project, worker, now);
        WorkSessionAttachmentEntity firstAttachment = createAttachment(session, project, worker, now);
        WorkSessionAttachmentEntity secondAttachment = createAttachment(
                session,
                project,
                worker,
                now,
                UUID.fromString("9aa2c7e5-1fd9-48ec-aa10-03dfdfb8ca7d"),
                "image/webp",
                2048L,
                "b".repeat(64),
                "opaque-storage-identity-two");
        TurnAttachmentSelectionValidator.ValidatedSelection firstSelection = selection(firstAttachment);
        TurnAttachmentSelectionValidator.ValidatedSelection secondSelection = selection(secondAttachment);
        when(selectionValidator.validate(
                any(WorkSessionEntity.class),
                eq(List.of(firstAttachment.getId()))))
                .thenReturn(firstSelection);
        when(selectionValidator.validate(
                any(WorkSessionEntity.class),
                eq(List.of(secondAttachment.getId()))))
                .thenReturn(secondSelection);
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
        long turnsBefore = sessionTurnRepository.count();
        long runsBefore = agentRunRepository.count();
        long bindingsBefore = bindingCount();

        CreateSessionTurnResponse first = sessionTurnService.createTurn(
                session.getId(),
                new CreateSessionTurnRequest(
                        "Inspect the first synthetic image",
                        UUID.fromString("7b35f774-97f2-4a9e-b7db-0f18d59112ba"),
                        List.of(firstAttachment.getId())));
        AgentRunEntity firstRun = agentRunRepository.findById(first.run().id()).orElseThrow();
        assertEquals(ProjectCodexIdentity.IMAGE_WORKLOAD_KIND, firstRun.getWorkloadKind());
        assertEquals(1, firstRun.getAttachmentCount());
        assertEquals(firstAttachment.getSizeBytes(), firstRun.getAttachmentBytes());
        assertEquals(firstSelection.manifestSha256(), firstRun.getAttachmentManifestSha256());
        assertEquals(List.of(firstAttachment.getId()), first.operatorTurn().attachments().stream()
                .map(attachment -> attachment.id()).toList());
        agentRunService.markSucceeded(firstRun.getId(), "turn-synthetic-one", "Synthetic success one");

        String stableThreadId = "bcf43e2e-c9e8-42df-96b2-e9183462c2f4";
        session.setExternalThreadId(stableThreadId);
        session = workSessionRepository.saveAndFlush(session);

        CreateSessionTurnResponse second = sessionTurnService.createTurn(
                session.getId(),
                new CreateSessionTurnRequest(
                        "Inspect the second synthetic image in the same thread",
                        UUID.fromString("fb332c29-d2e4-47ff-b33a-f26643179056"),
                        List.of(secondAttachment.getId())));
        AgentRunEntity secondRun = agentRunRepository.findById(second.run().id()).orElseThrow();
        assertEquals(ProjectCodexIdentity.IMAGE_WORKLOAD_KIND, secondRun.getWorkloadKind());
        assertEquals(firstRun.getWorkspaceIdentity(), secondRun.getWorkspaceIdentity());
        assertEquals(firstRun.getRemoteSessionId(), secondRun.getRemoteSessionId());
        assertEquals(stableThreadId,
                workSessionRepository.findById(session.getId()).orElseThrow().getExternalThreadId());
        assertEquals(1, secondRun.getAttachmentCount());
        assertEquals(secondAttachment.getSizeBytes(), secondRun.getAttachmentBytes());
        assertEquals(secondSelection.manifestSha256(), secondRun.getAttachmentManifestSha256());
        assertEquals(List.of(secondAttachment.getId()), second.operatorTurn().attachments().stream()
                .map(attachment -> attachment.id()).toList());
        agentRunService.markSucceeded(secondRun.getId(), "turn-synthetic-two", "Synthetic success two");

        CreateSessionTurnResponse textOnly = sessionTurnService.createTurn(
                session.getId(),
                new CreateSessionTurnRequest("Continue in the same thread without an image"));
        AgentRunEntity textRun = agentRunRepository.findById(textOnly.run().id()).orElseThrow();
        assertEquals(ProjectCodexIdentity.WORKLOAD_KIND, textRun.getWorkloadKind());
        assertEquals(firstRun.getWorkspaceIdentity(), textRun.getWorkspaceIdentity());
        assertEquals(firstRun.getRemoteSessionId(), textRun.getRemoteSessionId());
        assertEquals(stableThreadId,
                workSessionRepository.findById(session.getId()).orElseThrow().getExternalThreadId());
        assertEquals(0, textRun.getAttachmentCount());
        assertEquals(0L, textRun.getAttachmentBytes());
        assertNull(textRun.getAttachmentManifestSha256());
        assertEquals(List.of(), textOnly.operatorTurn().attachments());

        List<SessionTurnResponse> history = sessionTurnService.getTurns(session.getId());
        assertEquals(3, history.size());
        assertEquals(List.of(firstAttachment.getId()), history.get(0).attachments().stream()
                .map(attachment -> attachment.id()).toList());
        assertEquals(List.of(secondAttachment.getId()), history.get(1).attachments().stream()
                .map(attachment -> attachment.id()).toList());
        assertEquals(List.of(), history.get(2).attachments());
        assertEquals(turnsBefore + 3, sessionTurnRepository.count());
        assertEquals(runsBefore + 3, agentRunRepository.count());
        assertEquals(bindingsBefore + 2, bindingCount());
        verify(selectionValidator, times(1)).validate(
                any(WorkSessionEntity.class),
                eq(List.of(firstAttachment.getId())));
        verify(selectionValidator, times(1)).validate(
                any(WorkSessionEntity.class),
                eq(List.of(secondAttachment.getId())));
        verify(remoteAgentRunCoordinator, times(3)).dispatchAfterCommit(any());
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
        return createAttachment(
                session,
                project,
                worker,
                now,
                UUID.fromString("4e8f351e-e05a-41b6-99e5-3eb72d770002"),
                "image/png",
                1024L,
                "a".repeat(64),
                "opaque-storage-identity");
    }

    private WorkSessionAttachmentEntity createAttachment(
            WorkSessionEntity session,
            ProjectEntity project,
            WorkerNodeEntity worker,
            Instant now,
            UUID attachmentId,
            String contentType,
            long sizeBytes,
            String sha256,
            String storageIdentity
    ) {
        WorkSessionAttachmentEntity attachment = new WorkSessionAttachmentEntity();
        attachment.setId(attachmentId);
        attachment.setWorkSession(session);
        attachment.setProject(project);
        attachment.setSource(AttachmentSource.OPERATOR_UPLOAD);
        attachment.setKind(AttachmentKind.IMAGE);
        attachment.setOriginalFilename("sanitized-image");
        attachment.setContentType(contentType);
        attachment.setSizeBytes(sizeBytes);
        attachment.setRetentionClass(AttachmentRetentionClass.SESSION);
        attachment.setRetainUntil(Instant.parse("2030-01-01T00:00:00Z"));
        attachment.setSha256(sha256);
        attachment.setWorkerId(worker.getId());
        attachment.setStorageIdentity(storageIdentity);
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
