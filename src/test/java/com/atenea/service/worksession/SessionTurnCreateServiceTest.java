package com.atenea.service.worksession;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.atenea.api.worksession.AgentRunResponse;
import com.atenea.api.worksession.CreateSessionTurnRequest;
import com.atenea.api.worksession.CreateSessionTurnResponse;
import com.atenea.codexappserver.CodexAppServerClient.CodexAppServerExecutionHandle;
import com.atenea.codexappserver.CodexAppServerExecutionResult;
import com.atenea.attachments.RealAttachmentProjectRegistry;
import com.atenea.persistence.project.ProjectEntity;
import com.atenea.persistence.worksession.AgentRunEntity;
import com.atenea.persistence.worksession.AgentRunRepository;
import com.atenea.persistence.worksession.AgentRunStatus;
import com.atenea.persistence.worksession.SessionTurnActor;
import com.atenea.persistence.worksession.SessionTurnEntity;
import com.atenea.persistence.worksession.SessionTurnRepository;
import com.atenea.persistence.worksession.SessionTurnAttachmentRepository;
import com.atenea.persistence.worksession.SessionTurnAttachmentEntity;
import com.atenea.persistence.worksession.WorkSessionEntity;
import com.atenea.persistence.worksession.WorkSessionAttachmentEntity;
import com.atenea.persistence.worksession.WorkSessionAttachmentRepository;
import com.atenea.persistence.worksession.WorkSessionRepository;
import com.atenea.persistence.worksession.WorkSessionStatus;
import com.atenea.persistence.worksession.ExecutionTarget;
import com.atenea.persistence.worksession.WorkloadClass;
import com.atenea.service.project.WorkspaceRepositoryPathValidator;
import com.atenea.service.git.GitRepositoryService;
import com.atenea.remoteworker.CanonicalSourceAdmissionService;
import com.atenea.remoteworker.ProjectCodexIdentity;
import com.atenea.remoteworker.RemoteAgentRunCoordinator;
import com.atenea.service.git.GitRepositoryOperationException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SessionTurnCreateServiceTest {

    @Mock
    private WorkSessionRepository workSessionRepository;

    @Mock
    private SessionTurnRepository sessionTurnRepository;

    @Mock
    private SessionTurnAttachmentRepository sessionTurnAttachmentRepository;

    @Mock
    private WorkSessionAttachmentRepository workSessionAttachmentRepository;

    @Mock
    private GitRepositoryService gitRepositoryService;

    @Mock
    private AgentRunRepository agentRunRepository;

    @Mock
    private AgentRunService agentRunService;

    @Mock
    private AgentRunReconciliationService agentRunReconciliationService;

    @Mock
    private SessionCodexOrchestrator sessionCodexOrchestrator;

    @Mock
    private SessionTurnCompletionService sessionTurnCompletionService;

    @Mock
    private CanonicalSourceAdmissionService canonicalSourceAdmissionService;

    @Mock
    private TurnAttachmentSelectionValidator turnAttachmentSelectionValidator;

    @Mock
    private TurnAttachmentFingerprintService turnAttachmentFingerprintService;

    @Mock
    private RemoteAgentRunCoordinator remoteAgentRunCoordinator;

    @TempDir
    Path tempDir;

    private SessionTurnService sessionTurnService;

    @BeforeEach
    void setUp() throws IOException {
        Path workspaceRoot = Files.createDirectories(tempDir.resolve("repos"));
        sessionTurnService = new SessionTurnService(
                workSessionRepository,
                sessionTurnRepository,
                sessionTurnAttachmentRepository,
                workSessionAttachmentRepository,
                new WorkspaceRepositoryPathValidator(workspaceRoot.toString()),
                gitRepositoryService,
                agentRunRepository,
                agentRunService,
                new AgentRunProgressService(),
                agentRunReconciliationService,
                sessionCodexOrchestrator,
                sessionTurnCompletionService,
                canonicalSourceAdmissionService,
                turnAttachmentSelectionValidator,
                turnAttachmentFingerprintService
        );
        sessionTurnService.setRemoteAgentRunCoordinator(remoteAgentRunCoordinator);
    }

    @Test
    void imageTurnPersistsIdentitySnapshotAndOrderedBindingsBeforeDispatch() {
        WorkSessionEntity session = exactRemoteAteneaSession();
        UUID clientRequestId = UUID.fromString("7b35f774-97f2-4a9e-b7db-0f18d59112ba");
        UUID firstAttachmentId = UUID.fromString("4e8f351e-e05a-41b6-99e5-3eb72d770002");
        UUID secondAttachmentId = UUID.fromString("9aa2c7e5-1fd9-48ec-aa10-03dfdfb8ca7d");
        TurnAttachmentSelectionValidator.ValidatedSelection selection =
                new TurnAttachmentSelectionValidator.ValidatedSelection(
                        List.of(
                                new TurnAttachmentSelectionValidator.ValidatedAttachment(
                                        firstAttachmentId, "image/png", 1024L, "a".repeat(64)),
                                new TurnAttachmentSelectionValidator.ValidatedAttachment(
                                        secondAttachmentId, "image/webp", 2048L, "b".repeat(64))),
                        3072L,
                        "c".repeat(64));
        when(workSessionRepository.findLockedWithProjectById(12L)).thenReturn(Optional.of(session));
        when(turnAttachmentSelectionValidator.validate(
                session,
                List.of(firstAttachmentId, secondAttachmentId)))
                .thenReturn(selection);
        when(turnAttachmentFingerprintService.requestFingerprintSha256(
                eq("Inspect both images"),
                any()))
                .thenReturn("d".repeat(64));
        when(sessionTurnRepository.save(any(SessionTurnEntity.class))).thenAnswer(invocation -> {
            SessionTurnEntity saved = invocation.getArgument(0);
            saved.setId(101L);
            return saved;
        });
        AgentRunEntity run = new AgentRunEntity();
        run.setId(55L);
        run.setSession(session);
        run.setStatus(AgentRunStatus.QUEUED);
        when(agentRunService.createRemoteQueuedRun(
                eq(session),
                any(SessionTurnEntity.class),
                eq(WorkloadClass.NORMAL),
                eq(selection))).thenReturn(run);
        when(sessionTurnAttachmentRepository.insert(12L, 101L, firstAttachmentId, (short) 0))
                .thenReturn(1);
        when(sessionTurnAttachmentRepository.insert(12L, 101L, secondAttachmentId, (short) 1))
                .thenReturn(1);

        sessionTurnService.createTurn(
                12L,
                new CreateSessionTurnRequest(
                        "Inspect both images",
                        clientRequestId,
                        List.of(firstAttachmentId, secondAttachmentId)));

        ArgumentCaptor<SessionTurnEntity> turnCaptor =
                ArgumentCaptor.forClass(SessionTurnEntity.class);
        InOrder order = inOrder(
                sessionTurnRepository,
                agentRunService,
                sessionTurnAttachmentRepository,
                remoteAgentRunCoordinator);
        order.verify(sessionTurnRepository).save(turnCaptor.capture());
        order.verify(agentRunService).createRemoteQueuedRun(
                session,
                turnCaptor.getValue(),
                WorkloadClass.NORMAL,
                selection);
        order.verify(sessionTurnAttachmentRepository)
                .insert(12L, 101L, firstAttachmentId, (short) 0);
        order.verify(sessionTurnAttachmentRepository)
                .insert(12L, 101L, secondAttachmentId, (short) 1);
        order.verify(remoteAgentRunCoordinator).dispatchAfterCommit(55L);
        assertEquals(clientRequestId, turnCaptor.getValue().getClientRequestId());
        assertEquals("d".repeat(64), turnCaptor.getValue().getRequestFingerprintSha256());
    }

    @Test
    void invalidImageSelectionCreatesNoTurnBindingRunOrDispatch() {
        WorkSessionEntity session = exactRemoteAteneaSession();
        UUID attachmentId = UUID.fromString("4e8f351e-e05a-41b6-99e5-3eb72d770002");
        when(workSessionRepository.findLockedWithProjectById(12L)).thenReturn(Optional.of(session));
        when(turnAttachmentSelectionValidator.validate(session, List.of(attachmentId)))
                .thenThrow(new AttachmentOwnershipException("synthetic invalid image"));

        assertThrows(
                AttachmentOwnershipException.class,
                () -> sessionTurnService.createTurn(
                        12L,
                        new CreateSessionTurnRequest(
                                "Inspect image",
                                UUID.randomUUID(),
                                List.of(attachmentId))));

        verify(sessionTurnRepository, never()).save(any(SessionTurnEntity.class));
        verify(sessionTurnAttachmentRepository, never())
                .insert(any(), any(), any(), anyShort());
        verify(agentRunService, never()).createRemoteQueuedRun(
                any(), any(), any(), any(TurnAttachmentSelectionValidator.ValidatedSelection.class));
        verify(remoteAgentRunCoordinator, never()).dispatchAfterCommit(any());
    }

    @Test
    void acceptedImageRequestReplaysOriginalAndConflictingReuseCreatesNothing() {
        WorkSessionEntity session = exactRemoteAteneaSession();
        UUID clientRequestId = UUID.fromString("7b35f774-97f2-4a9e-b7db-0f18d59112ba");
        UUID firstAttachmentId = UUID.fromString("4e8f351e-e05a-41b6-99e5-3eb72d770002");
        UUID secondAttachmentId = UUID.fromString("9aa2c7e5-1fd9-48ec-aa10-03dfdfb8ca7d");
        SessionTurnEntity acceptedTurn = new SessionTurnEntity();
        acceptedTurn.setId(101L);
        acceptedTurn.setSession(session);
        acceptedTurn.setActor(SessionTurnActor.OPERATOR);
        acceptedTurn.setMessageText("Inspect both images");
        acceptedTurn.setClientRequestId(clientRequestId);
        acceptedTurn.setRequestFingerprintSha256("d".repeat(64));
        acceptedTurn.setCreatedAt(Instant.parse("2026-08-02T00:00:00Z"));
        SessionTurnAttachmentEntity firstBinding = mock(SessionTurnAttachmentEntity.class);
        SessionTurnAttachmentEntity secondBinding = mock(SessionTurnAttachmentEntity.class);
        when(firstBinding.getAttachmentId()).thenReturn(firstAttachmentId);
        when(firstBinding.getPosition()).thenReturn((short) 0);
        when(secondBinding.getAttachmentId()).thenReturn(secondAttachmentId);
        when(secondBinding.getPosition()).thenReturn((short) 1);
        WorkSessionAttachmentEntity firstAttachment = replayAttachment(
                firstAttachmentId, "image/png", 1024L, "a".repeat(64));
        WorkSessionAttachmentEntity secondAttachment = replayAttachment(
                secondAttachmentId, "image/webp", 2048L, "b".repeat(64));
        AgentRunEntity acceptedRun = new AgentRunEntity();
        acceptedRun.setId(55L);
        acceptedRun.setSession(session);
        acceptedRun.setOriginTurn(acceptedTurn);
        acceptedRun.setStatus(AgentRunStatus.QUEUED);
        acceptedRun.setWorkloadKind(ProjectCodexIdentity.IMAGE_WORKLOAD_KIND);
        acceptedRun.setAttachmentCount(2);
        acceptedRun.setAttachmentBytes(3072L);
        acceptedRun.setAttachmentManifestSha256("c".repeat(64));
        AgentRunResponse acceptedRunResponse = mock(AgentRunResponse.class);

        when(workSessionRepository.findLockedWithProjectById(12L))
                .thenReturn(Optional.of(session));
        when(sessionTurnRepository.findBySessionIdAndClientRequestId(12L, clientRequestId))
                .thenReturn(Optional.of(acceptedTurn));
        when(sessionTurnAttachmentRepository
                .findByWorkSessionIdAndSessionTurnIdOrderByPositionAsc(12L, 101L))
                .thenReturn(List.of(firstBinding, secondBinding));
        when(workSessionAttachmentRepository.findByIdAndWorkSessionId(firstAttachmentId, 12L))
                .thenReturn(Optional.of(firstAttachment));
        when(workSessionAttachmentRepository.findByIdAndWorkSessionId(secondAttachmentId, 12L))
                .thenReturn(Optional.of(secondAttachment));
        when(turnAttachmentFingerprintService.attachmentManifestSha256(any()))
                .thenReturn("c".repeat(64));
        when(turnAttachmentFingerprintService.requestFingerprintSha256(
                eq("Inspect both images"), any()))
                .thenReturn("d".repeat(64));
        when(turnAttachmentFingerprintService.requestFingerprintSha256(
                eq("Different message"), any()))
                .thenReturn("e".repeat(64));
        when(agentRunRepository.findFirstBySessionIdAndOriginTurnIdOrderByCreatedAtAsc(12L, 101L))
                .thenReturn(Optional.of(acceptedRun));
        when(agentRunService.toResponse(acceptedRun)).thenReturn(acceptedRunResponse);

        CreateSessionTurnResponse replay = sessionTurnService.createTurn(
                12L,
                new CreateSessionTurnRequest(
                        "Inspect both images",
                        clientRequestId,
                        List.of(firstAttachmentId, secondAttachmentId)));

        assertEquals(101L, replay.operatorTurn().id());
        assertEquals(acceptedRunResponse, replay.run());
        assertThrows(
                AttachmentConflictException.class,
                () -> sessionTurnService.createTurn(
                        12L,
                        new CreateSessionTurnRequest(
                                "Different message",
                                clientRequestId,
                                List.of(firstAttachmentId, secondAttachmentId))));
        assertThrows(
                AttachmentConflictException.class,
                () -> sessionTurnService.createTurn(
                        12L,
                        new CreateSessionTurnRequest(
                                "Inspect both images",
                                clientRequestId,
                                List.of(secondAttachmentId, firstAttachmentId))));

        verify(turnAttachmentSelectionValidator, never()).validate(any(), any());
        verify(canonicalSourceAdmissionService, never()).admitBeforeWrite(any());
        verify(agentRunReconciliationService, never()).reconcileSession(any());
        verify(sessionTurnRepository, never()).save(any());
        verify(sessionTurnAttachmentRepository, never()).insert(any(), any(), any(), anyShort());
        verify(agentRunService, never()).createRemoteQueuedRun(any(), any(), any(), any());
        verify(remoteAgentRunCoordinator, never()).dispatchAfterCommit(any());
    }

    @Test
    void canonicalAdmissionFailureCreatesNoOperatorTurnOrAgentRun() throws Exception {
        Path repoPath = createRepoPath("internal/atenea");
        WorkSessionEntity session = buildSession(12L, 7L, repoPath.toString(), WorkSessionStatus.OPEN, null);
        when(workSessionRepository.findWithProjectById(12L)).thenReturn(Optional.of(session));
        org.mockito.Mockito.doThrow(new WorkSessionOperationBlockedException("canonical source blocked"))
                .when(canonicalSourceAdmissionService).admitBeforeWrite(session);

        assertThrows(
                WorkSessionOperationBlockedException.class,
                () -> sessionTurnService.createTurn(12L, new CreateSessionTurnRequest("Do not persist")));

        verify(sessionTurnRepository, never()).save(any(SessionTurnEntity.class));
        verify(agentRunService, never()).createRunningRun(any(), any());
    }

    @Test
    void createTurnFirstTurnCreatesThreadAndPersistsConversation() throws Exception {
        Path repoPath = createRepoPath("internal/atenea");
        WorkSessionEntity session = buildSession(12L, 7L, repoPath.toString(), WorkSessionStatus.OPEN, null);
        AtomicLong turnIds = new AtomicLong(100L);

        when(workSessionRepository.findWithProjectById(12L)).thenReturn(Optional.of(session));
        when(agentRunRepository.existsBySessionIdAndStatus(12L, AgentRunStatus.RUNNING)).thenReturn(false);
        when(gitRepositoryService.getCurrentBranch(repoPath.toString())).thenReturn("main");
        when(sessionTurnRepository.save(any(SessionTurnEntity.class))).thenAnswer(invocation -> {
            SessionTurnEntity turn = invocation.getArgument(0);
            turn.setId(turnIds.incrementAndGet());
            return turn;
        });
        when(agentRunService.createRunningRun(eq(session), any(SessionTurnEntity.class))).thenAnswer(invocation -> {
            SessionTurnEntity originTurn = invocation.getArgument(1);
            AgentRunEntity run = new AgentRunEntity();
            run.setId(55L);
            run.setSession(session);
            run.setOriginTurn(originTurn);
            run.setStatus(AgentRunStatus.RUNNING);
            run.setTargetRepoPath(repoPath.toString());
            run.setStartedAt(Instant.parse("2026-03-25T10:05:01Z"));
            run.setCreatedAt(Instant.parse("2026-03-25T10:05:01Z"));
            return run;
        });
        when(sessionCodexOrchestrator.startTurn(eq(repoPath.toString()), eq("Inspect the project"), eq(null), any()))
                .thenReturn(handle("thread-1", "turn-1"));
        when(agentRunService.toResponse(any(AgentRunEntity.class))).thenAnswer(invocation -> {
            AgentRunEntity run = invocation.getArgument(0);
            return new AgentRunResponse(
                    run.getId(),
                    session.getId(),
                    run.getOriginTurn().getId(),
                    run.getResultTurn() == null ? null : run.getResultTurn().getId(),
                    run.getStatus(),
                    run.getTargetRepoPath(),
                    run.getExternalTurnId(),
                    run.getStartedAt(),
                    run.getFinishedAt(),
                    run.getOutputSummary(),
                    run.getErrorSummary(),
                    run.getCreatedAt()
            );
        });

        CreateSessionTurnResponse response = sessionTurnService.createTurn(
                12L,
                new CreateSessionTurnRequest("Inspect the project"));

        assertEquals("thread-1", session.getExternalThreadId());
        assertEquals("Inspect the project", response.operatorTurn().messageText());
        assertEquals("turn-1", response.run().externalTurnId());
        assertEquals(AgentRunStatus.RUNNING, response.run().status());
        assertNull(response.codexTurn());
        assertNotNull(session.getLastActivityAt());

        ArgumentCaptor<SessionTurnEntity> originTurnCaptor = ArgumentCaptor.forClass(SessionTurnEntity.class);
        verify(agentRunService).createRunningRun(eq(session), originTurnCaptor.capture());
        assertEquals(SessionTurnActor.OPERATOR, originTurnCaptor.getValue().getActor());
        assertEquals("Inspect the project", originTurnCaptor.getValue().getMessageText());
        assertEquals(false, originTurnCaptor.getValue().isInternal());
    }

    @Test
    void createTurnSecondTurnReusesExistingThread() throws Exception {
        Path repoPath = createRepoPath("internal/atenea");
        WorkSessionEntity session = buildSession(12L, 7L, repoPath.toString(), WorkSessionStatus.OPEN, "thread-existing");
        AtomicLong turnIds = new AtomicLong(200L);

        when(workSessionRepository.findWithProjectById(12L)).thenReturn(Optional.of(session));
        when(agentRunRepository.existsBySessionIdAndStatus(12L, AgentRunStatus.RUNNING)).thenReturn(false);
        when(gitRepositoryService.getCurrentBranch(repoPath.toString())).thenReturn("main");
        when(sessionTurnRepository.save(any(SessionTurnEntity.class))).thenAnswer(invocation -> {
            SessionTurnEntity turn = invocation.getArgument(0);
            turn.setId(turnIds.incrementAndGet());
            return turn;
        });
        when(agentRunService.createRunningRun(eq(session), any(SessionTurnEntity.class))).thenAnswer(invocation -> {
            SessionTurnEntity originTurn = invocation.getArgument(1);
            AgentRunEntity run = new AgentRunEntity();
            run.setId(56L);
            run.setSession(session);
            run.setOriginTurn(originTurn);
            run.setStatus(AgentRunStatus.RUNNING);
            run.setTargetRepoPath(repoPath.toString());
            run.setStartedAt(Instant.parse("2026-03-25T10:06:01Z"));
            run.setCreatedAt(Instant.parse("2026-03-25T10:06:01Z"));
            return run;
        });
        when(sessionCodexOrchestrator.startTurn(
                eq(repoPath.toString()),
                eq("Continue with implementation"),
                eq("thread-existing"),
                any()))
                .thenReturn(handle("thread-existing", "turn-2"));
        when(agentRunService.toResponse(any(AgentRunEntity.class))).thenAnswer(invocation -> {
            AgentRunEntity run = invocation.getArgument(0);
            return new AgentRunResponse(
                    run.getId(),
                    session.getId(),
                    run.getOriginTurn().getId(),
                    run.getResultTurn() == null ? null : run.getResultTurn().getId(),
                    run.getStatus(),
                    run.getTargetRepoPath(),
                    run.getExternalTurnId(),
                    run.getStartedAt(),
                    run.getFinishedAt(),
                    run.getOutputSummary(),
                    run.getErrorSummary(),
                    run.getCreatedAt()
            );
        });

        CreateSessionTurnResponse response = sessionTurnService.createTurn(
                12L,
                new CreateSessionTurnRequest("Continue with implementation"));

        assertEquals("thread-existing", session.getExternalThreadId());
        assertEquals("turn-2", response.run().externalTurnId());
        assertNull(response.codexTurn());
        verify(sessionCodexOrchestrator).startTurn(
                eq(repoPath.toString()),
                eq("Continue with implementation"),
                eq("thread-existing"),
                any());
    }

    @Test
    void createTurnRetriesWithFreshThreadWhenStoredThreadNoLongerExists() throws Exception {
        Path repoPath = createRepoPath("internal/atenea");
        WorkSessionEntity session = buildSession(12L, 7L, repoPath.toString(), WorkSessionStatus.OPEN, "thread-stale");
        AtomicLong turnIds = new AtomicLong(220L);

        when(workSessionRepository.findWithProjectById(12L)).thenReturn(Optional.of(session));
        when(agentRunRepository.existsBySessionIdAndStatus(12L, AgentRunStatus.RUNNING)).thenReturn(false);
        when(gitRepositoryService.getCurrentBranch(repoPath.toString())).thenReturn("main");
        when(sessionTurnRepository.save(any(SessionTurnEntity.class))).thenAnswer(invocation -> {
            SessionTurnEntity turn = invocation.getArgument(0);
            turn.setId(turnIds.incrementAndGet());
            return turn;
        });
        when(agentRunService.createRunningRun(eq(session), any(SessionTurnEntity.class))).thenAnswer(invocation -> {
            SessionTurnEntity originTurn = invocation.getArgument(1);
            AgentRunEntity run = new AgentRunEntity();
            run.setId(58L);
            run.setSession(session);
            run.setOriginTurn(originTurn);
            run.setStatus(AgentRunStatus.RUNNING);
            run.setTargetRepoPath(repoPath.toString());
            run.setStartedAt(Instant.parse("2026-03-25T10:06:01Z"));
            run.setCreatedAt(Instant.parse("2026-03-25T10:06:01Z"));
            return run;
        });
        when(sessionCodexOrchestrator.startTurn(
                eq(repoPath.toString()),
                eq("Continue with implementation"),
                eq("thread-stale"),
                any()))
                .thenThrow(new IllegalStateException(
                        "Codex App Server returned an error for turn/start: thread not found: thread-stale"));
        when(sessionCodexOrchestrator.startTurn(
                eq(repoPath.toString()),
                eq("Continue with implementation"),
                eq(null),
                any()))
                .thenReturn(handle("thread-fresh", "turn-3"));
        when(agentRunService.toResponse(any(AgentRunEntity.class))).thenAnswer(invocation -> {
            AgentRunEntity run = invocation.getArgument(0);
            return new AgentRunResponse(
                    run.getId(),
                    session.getId(),
                    run.getOriginTurn().getId(),
                    run.getResultTurn() == null ? null : run.getResultTurn().getId(),
                    run.getStatus(),
                    run.getTargetRepoPath(),
                    run.getExternalTurnId(),
                    run.getStartedAt(),
                    run.getFinishedAt(),
                    run.getOutputSummary(),
                    run.getErrorSummary(),
                    run.getCreatedAt()
            );
        });

        CreateSessionTurnResponse response = sessionTurnService.createTurn(
                12L,
                new CreateSessionTurnRequest("Continue with implementation"));

        assertEquals("thread-fresh", session.getExternalThreadId());
        assertEquals("turn-3", response.run().externalTurnId());
        verify(sessionCodexOrchestrator, times(1)).startTurn(
                eq(repoPath.toString()),
                eq("Continue with implementation"),
                eq("thread-stale"),
                any());
        verify(sessionCodexOrchestrator, times(1)).startTurn(
                eq(repoPath.toString()),
                eq("Continue with implementation"),
                eq(null),
                any());
    }

    @Test
    void createTurnMaintainsThreadContinuityAcrossTwoSequentialTurns() throws Exception {
        Path repoPath = createRepoPath("internal/atenea");
        WorkSessionEntity session = buildSession(12L, 7L, repoPath.toString(), WorkSessionStatus.OPEN, null);
        AtomicLong turnIds = new AtomicLong(300L);
        AtomicLong runIds = new AtomicLong(60L);

        when(workSessionRepository.findWithProjectById(12L)).thenReturn(Optional.of(session));
        when(agentRunRepository.existsBySessionIdAndStatus(12L, AgentRunStatus.RUNNING)).thenReturn(false);
        when(gitRepositoryService.getCurrentBranch(repoPath.toString())).thenReturn("main");
        when(sessionTurnRepository.save(any(SessionTurnEntity.class))).thenAnswer(invocation -> {
            SessionTurnEntity turn = invocation.getArgument(0);
            turn.setId(turnIds.incrementAndGet());
            return turn;
        });
        when(agentRunService.createRunningRun(eq(session), any(SessionTurnEntity.class))).thenAnswer(invocation -> {
            SessionTurnEntity originTurn = invocation.getArgument(1);
            AgentRunEntity run = new AgentRunEntity();
            run.setId(runIds.incrementAndGet());
            run.setSession(session);
            run.setOriginTurn(originTurn);
            run.setStatus(AgentRunStatus.RUNNING);
            run.setTargetRepoPath(repoPath.toString());
            run.setStartedAt(Instant.parse("2026-03-25T10:08:01Z"));
            run.setCreatedAt(Instant.parse("2026-03-25T10:08:01Z"));
            return run;
        });
        when(sessionCodexOrchestrator.startTurn(eq(repoPath.toString()), eq("First turn"), eq(null), any()))
                .thenReturn(handle("thread-stable", "turn-a"));
        when(sessionCodexOrchestrator.startTurn(eq(repoPath.toString()), eq("Second turn"), eq("thread-stable"), any()))
                .thenReturn(handle("thread-stable", "turn-b"));
        when(agentRunService.toResponse(any(AgentRunEntity.class))).thenAnswer(invocation -> {
            AgentRunEntity run = invocation.getArgument(0);
            return new AgentRunResponse(
                    run.getId(),
                    session.getId(),
                    run.getOriginTurn().getId(),
                    run.getResultTurn() == null ? null : run.getResultTurn().getId(),
                    run.getStatus(),
                    run.getTargetRepoPath(),
                    run.getExternalTurnId(),
                    run.getStartedAt(),
                    run.getFinishedAt(),
                    run.getOutputSummary(),
                    run.getErrorSummary(),
                    run.getCreatedAt()
            );
        });

        CreateSessionTurnResponse first = sessionTurnService.createTurn(12L, new CreateSessionTurnRequest("First turn"));
        String persistedThreadIdAfterFirstTurn = session.getExternalThreadId();
        CreateSessionTurnResponse second = sessionTurnService.createTurn(12L, new CreateSessionTurnRequest("Second turn"));

        assertEquals("thread-stable", persistedThreadIdAfterFirstTurn);
        assertEquals("thread-stable", session.getExternalThreadId());
        assertEquals("turn-a", first.run().externalTurnId());
        assertEquals("turn-b", second.run().externalTurnId());
    }

    @Test
    void createTurnFailsWhenSessionIsClosed() {
        WorkSessionEntity session = buildSession(12L, 7L, "/workspace/repos/internal/atenea", WorkSessionStatus.CLOSED, null);
        when(workSessionRepository.findWithProjectById(12L)).thenReturn(Optional.of(session));

        assertThrows(
                WorkSessionNotOpenException.class,
                () -> sessionTurnService.createTurn(12L, new CreateSessionTurnRequest("Inspect the project")));
    }

    @Test
    void createTurnFailsWhenSessionIsAlreadyRunning() throws Exception {
        Path repoPath = createRepoPath("internal/atenea");
        WorkSessionEntity session = buildSession(12L, 7L, repoPath.toString(), WorkSessionStatus.OPEN, null);

        when(workSessionRepository.findWithProjectById(12L)).thenReturn(Optional.of(session));
        when(agentRunRepository.existsBySessionIdAndStatus(12L, AgentRunStatus.RUNNING)).thenReturn(true);

        WorkSessionAlreadyRunningException exception = assertThrows(
                WorkSessionAlreadyRunningException.class,
                () -> sessionTurnService.createTurn(12L, new CreateSessionTurnRequest("Inspect the project")));

        assertEquals(
                "WorkSession with id '12' is already RUNNING and does not accept a new executable turn",
                exception.getMessage());
        verify(sessionTurnRepository, never()).save(any(SessionTurnEntity.class));
        verify(agentRunService, never()).createRunningRun(any(WorkSessionEntity.class), any(SessionTurnEntity.class));
    }

    @Test
    void createTurnMarksRunFailedWhenCodexFails() throws Exception {
        Path repoPath = createRepoPath("internal/atenea");
        WorkSessionEntity session = buildSession(12L, 7L, repoPath.toString(), WorkSessionStatus.OPEN, null);
        AtomicLong turnIds = new AtomicLong(100L);

        when(workSessionRepository.findWithProjectById(12L)).thenReturn(Optional.of(session));
        when(agentRunRepository.existsBySessionIdAndStatus(12L, AgentRunStatus.RUNNING)).thenReturn(false);
        when(gitRepositoryService.getCurrentBranch(repoPath.toString())).thenReturn("main");
        when(sessionTurnRepository.save(any(SessionTurnEntity.class))).thenAnswer(invocation -> {
            SessionTurnEntity turn = invocation.getArgument(0);
            turn.setId(turnIds.incrementAndGet());
            return turn;
        });
        when(agentRunService.createRunningRun(eq(session), any(SessionTurnEntity.class))).thenAnswer(invocation -> {
            SessionTurnEntity originTurn = invocation.getArgument(1);
            AgentRunEntity run = new AgentRunEntity();
            run.setId(57L);
            run.setSession(session);
            run.setOriginTurn(originTurn);
            run.setStatus(AgentRunStatus.RUNNING);
            run.setTargetRepoPath(repoPath.toString());
            run.setStartedAt(Instant.parse("2026-03-25T10:07:01Z"));
            run.setCreatedAt(Instant.parse("2026-03-25T10:07:01Z"));
            return run;
        });
        when(sessionCodexOrchestrator.startTurn(eq(repoPath.toString()), eq("Inspect the project"), eq(null), any()))
                .thenThrow(new RuntimeException("Timed out waiting for Codex App Server completion"));
        when(agentRunService.markFailed(eq(57L), eq((String) null), eq("Timed out waiting for Codex App Server completion")))
                .thenAnswer(invocation -> {
                    AgentRunEntity run = new AgentRunEntity();
                    run.setId(57L);
                    run.setSession(session);
                    run.setStatus(AgentRunStatus.FAILED);
                    run.setTargetRepoPath(repoPath.toString());
                    run.setErrorSummary("Timed out waiting for Codex App Server completion");
                    run.setCreatedAt(Instant.parse("2026-03-25T10:07:01Z"));
                    return run;
                });

        WorkSessionTurnExecutionFailedException exception = assertThrows(
                WorkSessionTurnExecutionFailedException.class,
                () -> sessionTurnService.createTurn(12L, new CreateSessionTurnRequest("Inspect the project")));

        assertEquals("Codex execution failed for WorkSession turn", exception.getMessage());
        verify(agentRunService).markFailed(57L, null, "Timed out waiting for Codex App Server completion");
        verify(agentRunService, org.mockito.Mockito.never()).markSucceeded(any(), any(), any(), any());
        assertNull(session.getExternalThreadId());
    }

    @Test
    void createTurnFailsWhenRepoIsNotOperational() throws Exception {
        Path repoPath = createRepoPath("internal/atenea");
        WorkSessionEntity session = buildSession(12L, 7L, repoPath.toString(), WorkSessionStatus.OPEN, null);
        when(workSessionRepository.findWithProjectById(12L)).thenReturn(Optional.of(session));
        when(agentRunRepository.existsBySessionIdAndStatus(12L, AgentRunStatus.RUNNING)).thenReturn(false);
        when(gitRepositoryService.getCurrentBranch(repoPath.toString()))
                .thenThrow(new GitRepositoryOperationException("Git command failed: rev-parse"));

        assertThrows(
                WorkSessionOperationBlockedException.class,
                () -> sessionTurnService.createTurn(12L, new CreateSessionTurnRequest("Inspect the project")));
    }

    private Path createRepoPath(String relativePath) throws IOException {
        Path repoPath = Files.createDirectories(tempDir.resolve("repos").resolve(relativePath));
        Files.createDirectories(repoPath.resolve(".git"));
        return repoPath;
    }

    private static WorkSessionEntity buildSession(
            Long sessionId,
            Long projectId,
            String repoPath,
            WorkSessionStatus status,
            String externalThreadId
    ) {
        ProjectEntity project = new ProjectEntity();
        project.setId(projectId);
        project.setName("Atenea");
        project.setRepoPath(repoPath);
        project.setCreatedAt(Instant.parse("2026-03-25T10:00:00Z"));
        project.setUpdatedAt(Instant.parse("2026-03-25T10:00:00Z"));

        WorkSessionEntity session = new WorkSessionEntity();
        session.setId(sessionId);
        session.setProject(project);
        session.setStatus(status);
        session.setTitle("Inspect project status");
        session.setBaseBranch("main");
        session.setExternalThreadId(externalThreadId);
        session.setOpenedAt(Instant.parse("2026-03-25T10:05:00Z"));
        session.setLastActivityAt(Instant.parse("2026-03-25T10:05:00Z"));
        session.setCreatedAt(Instant.parse("2026-03-25T10:05:00Z"));
        session.setUpdatedAt(Instant.parse("2026-03-25T10:05:00Z"));
        return session;
    }

    private static WorkSessionEntity exactRemoteAteneaSession() {
        WorkSessionEntity session = buildSession(
                12L,
                7L,
                ProjectCodexIdentity.REPO_PATH,
                WorkSessionStatus.OPEN,
                null);
        session.getProject().setName(ProjectCodexIdentity.PROJECT_NAME);
        session.setBaseBranch(ProjectCodexIdentity.BRANCH);
        session.setExecutionTarget(ExecutionTarget.REMOTE);
        session.setSelectedWorkerId("ax42-01");
        UUID remoteSessionId = UUID.fromString("a1c3af50-af6e-4cc2-85d6-a491c50cddcc");
        session.setRemoteSessionId(remoteSessionId);
        session.setRemoteWorkloadKind(ProjectCodexIdentity.WORKLOAD_KIND);
        session.setWorkspaceIdentity("remote:ax42-01:work-session:" + remoteSessionId);
        session.setAttachmentPolicyRevision(RealAttachmentProjectRegistry.ATENEA_POLICY_REVISION);
        return session;
    }

    private static WorkSessionAttachmentEntity replayAttachment(
            UUID id,
            String contentType,
            long sizeBytes,
            String sha256
    ) {
        WorkSessionAttachmentEntity attachment = new WorkSessionAttachmentEntity();
        attachment.setId(id);
        attachment.setContentType(contentType);
        attachment.setSizeBytes(sizeBytes);
        attachment.setSha256(sha256);
        return attachment;
    }

    private static SessionTurnEntity buildTurn(Long id, WorkSessionEntity session, SessionTurnActor actor, String text) {
        SessionTurnEntity turn = new SessionTurnEntity();
        turn.setId(id);
        turn.setSession(session);
        turn.setActor(actor);
        turn.setMessageText(text);
        turn.setInternal(false);
        turn.setCreatedAt(Instant.parse("2026-03-25T10:05:01Z"));
        return turn;
    }

    private static CodexAppServerExecutionHandle handle(String threadId, String turnId) {
        return new CodexAppServerExecutionHandle(
                threadId,
                turnId,
                CompletableFuture.completedFuture(new CodexAppServerExecutionResult(
                        threadId,
                        turnId,
                        CodexAppServerExecutionResult.Status.COMPLETED,
                        "Completed later",
                        "Completed later",
                        "commentary",
                        null)));
    }
}
