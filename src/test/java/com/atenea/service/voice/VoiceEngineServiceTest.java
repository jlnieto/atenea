package com.atenea.service.voice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.atenea.api.core.CoreCommandResponse;
import com.atenea.api.core.CoreCommandResultResponse;
import com.atenea.api.mobile.CreateMobileVoiceNoteRequest;
import com.atenea.api.mobile.CreateMobileVoiceNoteSendIntentRequest;
import com.atenea.api.mobile.MobileVoicePlaybackRequest;
import com.atenea.api.mobile.SendMobileVoiceNotesRequest;
import com.atenea.api.mobile.UpdateMobileVoiceFocusRequest;
import com.atenea.api.worksession.AgentRunResponse;
import com.atenea.api.worksession.CreateSessionTurnRequest;
import com.atenea.api.worksession.CreateSessionTurnResponse;
import com.atenea.api.worksession.SessionTurnResponse;
import com.atenea.auth.AuthenticatedOperator;
import com.atenea.persistence.auth.OperatorEntity;
import com.atenea.persistence.auth.OperatorRepository;
import com.atenea.persistence.core.CoreCommandEntity;
import com.atenea.persistence.core.CoreCommandRepository;
import com.atenea.persistence.core.CoreCommandStatus;
import com.atenea.persistence.core.CoreOperatorContextEntity;
import com.atenea.persistence.core.CoreResultType;
import com.atenea.persistence.core.CoreTargetType;
import com.atenea.persistence.operations.ManagedHostRepository;
import com.atenea.persistence.project.ProjectEntity;
import com.atenea.persistence.project.ProjectRepository;
import com.atenea.persistence.voice.VoiceDomain;
import com.atenea.persistence.voice.VoiceFocusEntity;
import com.atenea.persistence.voice.VoiceFocusRepository;
import com.atenea.persistence.voice.VoiceNoteEntity;
import com.atenea.persistence.voice.VoiceNoteRepository;
import com.atenea.persistence.voice.VoiceNoteSendDestinationType;
import com.atenea.persistence.voice.VoiceNoteSendIntentEntity;
import com.atenea.persistence.voice.VoiceNoteSendIntentRepository;
import com.atenea.persistence.voice.VoiceNoteSendIntentStatus;
import com.atenea.persistence.voice.VoiceNoteStatus;
import com.atenea.persistence.worksession.AgentRunRepository;
import com.atenea.persistence.worksession.AgentRunEntity;
import com.atenea.persistence.worksession.AgentRunStatus;
import com.atenea.persistence.worksession.SessionTurnActor;
import com.atenea.persistence.worksession.SessionTurnEntity;
import com.atenea.persistence.worksession.WorkSessionEntity;
import com.atenea.persistence.worksession.WorkSessionRepository;
import com.atenea.persistence.worksession.WorkSessionStatus;
import com.atenea.service.core.CoreCommandService;
import com.atenea.service.core.CoreOperatorContextService;
import com.atenea.service.worksession.SessionTurnService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class VoiceEngineServiceTest {

    @Mock
    private OperatorRepository operatorRepository;
    @Mock
    private ProjectRepository projectRepository;
    @Mock
    private WorkSessionRepository workSessionRepository;
    @Mock
    private CoreCommandRepository coreCommandRepository;
    @Mock
    private ManagedHostRepository managedHostRepository;
    @Mock
    private VoiceFocusRepository voiceFocusRepository;
    @Mock
    private VoiceNoteRepository voiceNoteRepository;
    @Mock
    private VoiceNoteSendIntentRepository voiceNoteSendIntentRepository;
    @Mock
    private AgentRunRepository agentRunRepository;
    @Mock
    private CoreCommandService coreCommandService;
    @Mock
    private CoreOperatorContextService coreOperatorContextService;
    @Mock
    private SessionTurnService sessionTurnService;

    private VoiceEngineService service;
    private OperatorEntity operator;
    private AuthenticatedOperator authenticatedOperator;

    @BeforeEach
    void setUp() {
        service = new VoiceEngineService(
                operatorRepository,
                projectRepository,
                workSessionRepository,
                coreCommandRepository,
                managedHostRepository,
                voiceFocusRepository,
                voiceNoteRepository,
                voiceNoteSendIntentRepository,
                agentRunRepository,
                coreCommandService,
                coreOperatorContextService,
                sessionTurnService,
                new ObjectMapper().findAndRegisterModules());
        operator = new OperatorEntity();
        operator.setId(4L);
        operator.setEmail("operator@atenea.local");
        operator.setDisplayName("Operator");
        operator.setActive(true);
        authenticatedOperator = new AuthenticatedOperator(4L, "operator@atenea.local", "Operator");
    }

    @Test
    void getFocusFallsBackToCoreOperatorContextWorkSession() {
        ProjectEntity project = new ProjectEntity();
        project.setId(7L);
        project.setName("fomasys");
        WorkSessionEntity session = new WorkSessionEntity();
        session.setId(12L);
        session.setTitle("Conversacion activa");
        session.setProject(project);
        CoreOperatorContextEntity context = new CoreOperatorContextEntity();
        context.setOperatorKey(CoreOperatorContextService.DEFAULT_OPERATOR_KEY);
        context.setActiveProjectId(7L);
        context.setActiveWorkSessionId(12L);
        context.setActiveCommandId(44L);
        context.setUpdatedAt(Instant.parse("2026-05-15T00:00:00Z"));
        when(operatorRepository.findById(4L)).thenReturn(Optional.of(operator));
        when(voiceFocusRepository.findById(4L)).thenReturn(Optional.empty());
        when(voiceNoteRepository.findByOperatorIdAndStatusOrderByCreatedAtAsc(4L, VoiceNoteStatus.ACTIVE))
                .thenReturn(List.of());
        when(coreOperatorContextService.getOrDefault(CoreOperatorContextService.DEFAULT_OPERATOR_KEY)).thenReturn(context);
        when(workSessionRepository.findWithProjectById(12L)).thenReturn(Optional.of(session));
        CoreCommandEntity command = new CoreCommandEntity();
        command.setId(44L);
        when(coreCommandRepository.findById(44L)).thenReturn(Optional.of(command));

        var response = service.getFocus(authenticatedOperator);

        assertEquals(VoiceDomain.DEVELOPMENT, response.domain());
        assertEquals(7L, response.projectId());
        assertEquals("fomasys", response.projectName());
        assertEquals(12L, response.workSessionId());
        assertEquals("Conversacion activa", response.workSessionTitle());
        assertEquals(44L, response.activeCommandId());
    }

    @Test
    void updateFocusResolvesWorkSessionProjectAndStoresPlaybackCursor() {
        ProjectEntity project = new ProjectEntity();
        project.setId(7L);
        project.setName("fomasys");
        WorkSessionEntity session = new WorkSessionEntity();
        session.setId(12L);
        session.setTitle("Conversacion activa");
        session.setProject(project);
        when(operatorRepository.findById(4L)).thenReturn(Optional.of(operator));
        when(voiceFocusRepository.findById(4L)).thenReturn(Optional.empty());
        when(workSessionRepository.findWithProjectById(12L)).thenReturn(Optional.of(session));
        when(voiceFocusRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(voiceNoteRepository.findByOperatorIdAndStatusOrderByCreatedAtAsc(4L, VoiceNoteStatus.ACTIVE))
                .thenReturn(List.of());

        var response = service.updateFocus(authenticatedOperator, new UpdateMobileVoiceFocusRequest(
                null,
                null,
                12L,
                null,
                null,
                "reading_codex_response",
                new MobileVoicePlaybackRequest("SESSION_TURN", "turn-99", 2, 6)));

        assertEquals(VoiceDomain.DEVELOPMENT, response.domain());
        assertEquals(7L, response.projectId());
        assertEquals("fomasys", response.projectName());
        assertEquals(12L, response.workSessionId());
        assertEquals("reading_codex_response", response.activity());
        assertEquals(2, response.playback().segmentIndex());
        assertEquals(6, response.playback().segmentCount());
    }

    @Test
    void createNoteStoresActiveNoteWithFocusSnapshot() {
        VoiceFocusEntity focus = focus();
        when(operatorRepository.findById(4L)).thenReturn(Optional.of(operator));
        when(voiceFocusRepository.findById(4L)).thenReturn(Optional.of(focus));
        when(voiceNoteRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.createNote(authenticatedOperator, new CreateMobileVoiceNoteRequest(" revisar permisos "));

        assertEquals("revisar permisos", response.text());
        assertEquals(VoiceNoteStatus.ACTIVE, response.status());
        assertNotNull(response.focusSnapshotJson());
        org.assertj.core.api.Assertions.assertThat(response.focusSnapshotJson()).contains("\"domain\":\"DEVELOPMENT\"");
        org.assertj.core.api.Assertions.assertThat(response.focusSnapshotJson()).contains("\"projectName\":\"fomasys\"");
    }

    @Test
    void archiveNoteMarksActiveNoteAsArchived() {
        VoiceNoteEntity note = note("Nota basura");
        when(operatorRepository.findById(4L)).thenReturn(Optional.of(operator));
        when(voiceNoteRepository.findByIdAndOperatorIdAndStatus(9L, 4L, VoiceNoteStatus.ACTIVE))
                .thenReturn(Optional.of(note));
        when(voiceNoteRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.archiveNote(authenticatedOperator, 9L);

        assertEquals(VoiceNoteStatus.ARCHIVED, response.status());
        assertNotNull(response.consumedAt());
    }

    @Test
    void archiveLastActiveNoteUsesNewestActiveNote() {
        VoiceNoteEntity note = note("Ultima nota");
        when(operatorRepository.findById(4L)).thenReturn(Optional.of(operator));
        when(voiceNoteRepository.findFirstByOperatorIdAndStatusOrderByCreatedAtDesc(4L, VoiceNoteStatus.ACTIVE))
                .thenReturn(Optional.of(note));
        when(voiceNoteRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.archiveLastActiveNote(authenticatedOperator);

        assertEquals("Ultima nota", response.text());
        assertEquals(VoiceNoteStatus.ARCHIVED, response.status());
    }

    @Test
    void archiveActiveNotesMarksEveryActiveNoteAsArchived() {
        VoiceNoteEntity first = note("Primera");
        VoiceNoteEntity second = note("Segunda");
        when(operatorRepository.findById(4L)).thenReturn(Optional.of(operator));
        when(voiceNoteRepository.findByOperatorIdAndStatusOrderByCreatedAtAsc(4L, VoiceNoteStatus.ACTIVE))
                .thenReturn(List.of(first, second));
        when(voiceNoteRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.archiveActiveNotes(authenticatedOperator);

        assertEquals(2, response.notes().size());
        assertEquals(VoiceNoteStatus.ARCHIVED, response.notes().get(0).status());
        assertEquals(VoiceNoteStatus.ARCHIVED, response.notes().get(1).status());
    }

    @Test
    void createSendIntentPersistsDestinationSnapshotAndConfirmationPrompt() {
        VoiceFocusEntity focus = focus();
        VoiceNoteEntity note = note("Revisar permisos");
        when(operatorRepository.findById(4L)).thenReturn(Optional.of(operator));
        when(voiceNoteRepository.findByOperatorIdAndStatusOrderByCreatedAtAsc(4L, VoiceNoteStatus.ACTIVE))
                .thenReturn(List.of(note));
        when(voiceFocusRepository.findById(4L)).thenReturn(Optional.of(focus));
        when(voiceNoteSendIntentRepository.save(any())).thenAnswer(invocation -> {
            VoiceNoteSendIntentEntity intent = invocation.getArgument(0);
            intent.setId(99L);
            return intent;
        });

        var response = service.createSendIntent(
                authenticatedOperator,
                new CreateMobileVoiceNoteSendIntentRequest("prioriza lo urgente"));

        assertEquals(VoiceNoteSendIntentStatus.PENDING, response.status());
        assertEquals(VoiceNoteSendDestinationType.WORK_SESSION, response.destinationType());
        assertEquals(7L, response.projectId());
        assertEquals(12L, response.workSessionId());
        org.assertj.core.api.Assertions.assertThat(response.confirmationPrompt())
                .contains("fomasys")
                .contains("Conversacion activa")
                .contains("Atenea confirmo");
    }

    @Test
    void createSendIntentResolvesOpenWorkSessionFromProjectFocus() {
        VoiceFocusEntity focus = projectFocus();
        WorkSessionEntity session = focus().getWorkSession();
        VoiceNoteEntity note = note("Revisar permisos");
        when(operatorRepository.findById(4L)).thenReturn(Optional.of(operator));
        when(voiceNoteRepository.findByOperatorIdAndStatusOrderByCreatedAtAsc(4L, VoiceNoteStatus.ACTIVE))
                .thenReturn(List.of(note));
        when(voiceFocusRepository.findById(4L)).thenReturn(Optional.of(focus));
        when(workSessionRepository.findByProjectIdAndStatus(7L, WorkSessionStatus.OPEN)).thenReturn(Optional.of(session));
        when(voiceFocusRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(voiceNoteSendIntentRepository.save(any())).thenAnswer(invocation -> {
            VoiceNoteSendIntentEntity intent = invocation.getArgument(0);
            intent.setId(99L);
            return intent;
        });

        var response = service.createSendIntent(
                authenticatedOperator,
                new CreateMobileVoiceNoteSendIntentRequest(null));

        assertEquals(7L, response.projectId());
        assertEquals(12L, response.workSessionId());
        ArgumentCaptor<VoiceFocusEntity> focusCaptor = ArgumentCaptor.forClass(VoiceFocusEntity.class);
        verify(voiceFocusRepository).save(focusCaptor.capture());
        assertEquals(12L, focusCaptor.getValue().getWorkSession().getId());
        assertEquals("Conversacion activa", focusCaptor.getValue().getActivity());
    }

    @Test
    void confirmSendIntentCreatesWorkSessionTurnAndConsumesNotes() {
        VoiceFocusEntity focus = focus();
        VoiceNoteEntity note = note("Revisar permisos");
        VoiceNoteSendIntentEntity intent = sendIntent(focus);
        when(operatorRepository.findById(4L)).thenReturn(Optional.of(operator));
        when(voiceNoteSendIntentRepository.findByIdAndOperatorId(99L, 4L)).thenReturn(Optional.of(intent));
        when(voiceFocusRepository.findById(4L)).thenReturn(Optional.of(focus));
        when(voiceNoteRepository.findByOperatorIdAndStatusOrderByCreatedAtAsc(4L, VoiceNoteStatus.ACTIVE))
                .thenReturn(List.of(note));
        when(voiceNoteSendIntentRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(voiceNoteRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(sessionTurnService.createTurn(eq(12L), any())).thenReturn(new CreateSessionTurnResponse(
                new SessionTurnResponse(101L, SessionTurnActor.OPERATOR, "mensaje", Instant.parse("2026-05-15T00:00:00Z")),
                new AgentRunResponse(
                        202L,
                        12L,
                        101L,
                        null,
                        AgentRunStatus.RUNNING,
                        "/repo",
                        null,
                        Instant.parse("2026-05-15T00:00:00Z"),
                        null,
                        null,
                        null,
                        Instant.parse("2026-05-15T00:00:00Z")),
                null));

        var response = service.confirmSendIntent(authenticatedOperator, 99L);

        assertEquals(VoiceNoteSendIntentStatus.SENT, response.intent().status());
        assertEquals(VoiceNoteStatus.SENT, response.consumedNotes().getFirst().status());
        assertEquals(101L, response.operatorTurnId());
        assertEquals(202L, response.agentRunId());
        ArgumentCaptor<CreateSessionTurnRequest> requestCaptor = ArgumentCaptor.forClass(CreateSessionTurnRequest.class);
        verify(sessionTurnService).createTurn(eq(12L), requestCaptor.capture());
        org.assertj.core.api.Assertions.assertThat(requestCaptor.getValue().message())
                .contains("Revisar permisos")
                .contains("WorkSession");
    }

    @Test
    void latestCodexStatusReportsRunningRunFromLastSentVoiceIntent() {
        VoiceFocusEntity focus = focus();
        VoiceNoteSendIntentEntity intent = sendIntent(focus);
        intent.setStatus(VoiceNoteSendIntentStatus.SENT);
        intent.setSentAt(Instant.parse("2026-05-15T00:01:00Z"));
        AgentRunEntity run = agentRun(202L, focus.getWorkSession(), AgentRunStatus.RUNNING, null);
        intent.setAgentRun(run);
        when(operatorRepository.findById(4L)).thenReturn(Optional.of(operator));
        when(voiceNoteSendIntentRepository.findFirstByOperatorIdAndStatusOrderBySentAtDesc(
                4L,
                VoiceNoteSendIntentStatus.SENT)).thenReturn(Optional.of(intent));
        when(agentRunRepository.findWithSessionById(202L)).thenReturn(Optional.of(run));

        var response = service.getLatestCodexStatus(authenticatedOperator);

        assertEquals(202L, response.agentRunId());
        assertEquals(AgentRunStatus.RUNNING, response.runStatus());
        assertEquals(false, response.responseReady());
        org.assertj.core.api.Assertions.assertThat(response.message())
                .contains("Codex sigue trabajando")
                .contains("fomasys");
    }

    @Test
    void sendActiveNotesCreatesVoiceCoreCommandAndMarksNotesAsSent() {
        VoiceFocusEntity focus = focus();
        VoiceNoteEntity note = note("Revisar permisos");
        CoreCommandEntity consumedCommand = new CoreCommandEntity();
        consumedCommand.setId(44L);
        when(operatorRepository.findById(4L)).thenReturn(Optional.of(operator));
        when(voiceNoteRepository.findByOperatorIdAndStatusOrderByCreatedAtAsc(4L, VoiceNoteStatus.ACTIVE))
                .thenReturn(List.of(note));
        when(voiceFocusRepository.findById(4L)).thenReturn(Optional.of(focus));
        when(coreCommandService.createCommand(any())).thenReturn(new CoreCommandResponse(
                44L,
                CoreCommandStatus.SUCCEEDED,
                null,
                null,
                null,
                null,
                null,
                "ok",
                "ok"));
        when(coreCommandRepository.getReferenceById(44L)).thenReturn(consumedCommand);
        when(voiceNoteRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.sendActiveNotes(
                authenticatedOperator,
                new SendMobileVoiceNotesRequest("prioriza lo urgente"));

        assertEquals(44L, response.command().commandId());
        assertEquals(VoiceNoteStatus.SENT, response.consumedNotes().getFirst().status());
        assertEquals(44L, response.consumedNotes().getFirst().consumedByCommandId());
        ArgumentCaptor<com.atenea.api.core.CreateCoreCommandRequest> requestCaptor =
                ArgumentCaptor.forClass(com.atenea.api.core.CreateCoreCommandRequest.class);
        verify(coreCommandService).createCommand(requestCaptor.capture());
        org.assertj.core.api.Assertions.assertThat(requestCaptor.getValue().input())
                .contains("Revisar permisos")
                .contains("prioriza lo urgente");
        assertEquals(7L, requestCaptor.getValue().context().projectId());
        assertEquals(12L, requestCaptor.getValue().context().workSessionId());
    }

    @Test
    void sendActiveNotesResolvesOpenWorkSessionFromProjectFocus() {
        VoiceFocusEntity focus = projectFocus();
        WorkSessionEntity session = focus().getWorkSession();
        VoiceNoteEntity note = note("Revisar permisos");
        CoreCommandEntity consumedCommand = new CoreCommandEntity();
        consumedCommand.setId(44L);
        when(operatorRepository.findById(4L)).thenReturn(Optional.of(operator));
        when(voiceNoteRepository.findByOperatorIdAndStatusOrderByCreatedAtAsc(4L, VoiceNoteStatus.ACTIVE))
                .thenReturn(List.of(note));
        when(voiceFocusRepository.findById(4L)).thenReturn(Optional.of(focus));
        when(workSessionRepository.findByProjectIdAndStatus(7L, WorkSessionStatus.OPEN)).thenReturn(Optional.of(session));
        when(voiceFocusRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(coreCommandService.createCommand(any())).thenReturn(new CoreCommandResponse(
                44L,
                CoreCommandStatus.SUCCEEDED,
                null,
                null,
                null,
                null,
                null,
                "ok",
                "ok"));
        when(coreCommandRepository.getReferenceById(44L)).thenReturn(consumedCommand);
        when(voiceNoteRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.sendActiveNotes(authenticatedOperator, new SendMobileVoiceNotesRequest(null));

        ArgumentCaptor<com.atenea.api.core.CreateCoreCommandRequest> requestCaptor =
                ArgumentCaptor.forClass(com.atenea.api.core.CreateCoreCommandRequest.class);
        verify(coreCommandService).createCommand(requestCaptor.capture());
        assertEquals(7L, requestCaptor.getValue().context().projectId());
        assertEquals(12L, requestCaptor.getValue().context().workSessionId());
    }

    @Test
    void sendActiveNotesSynchronizesVoiceFocusFromWorkSessionCommandResult() {
        VoiceNoteEntity note = note("Revisar permisos");
        CoreCommandEntity consumedCommand = new CoreCommandEntity();
        consumedCommand.setId(44L);
        WorkSessionEntity session = focus().getWorkSession();
        CoreOperatorContextEntity context = new CoreOperatorContextEntity();
        context.setOperatorKey(CoreOperatorContextService.DEFAULT_OPERATOR_KEY);
        context.setActiveProjectId(7L);
        context.setActiveWorkSessionId(12L);
        context.setUpdatedAt(Instant.parse("2026-05-15T00:00:00Z"));
        when(operatorRepository.findById(4L)).thenReturn(Optional.of(operator));
        when(voiceNoteRepository.findByOperatorIdAndStatusOrderByCreatedAtAsc(4L, VoiceNoteStatus.ACTIVE))
                .thenReturn(List.of(note));
        when(voiceFocusRepository.findById(4L)).thenReturn(Optional.empty());
        when(coreOperatorContextService.getOrDefault(CoreOperatorContextService.DEFAULT_OPERATOR_KEY)).thenReturn(context);
        when(voiceFocusRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(coreCommandService.createCommand(any())).thenReturn(new CoreCommandResponse(
                44L,
                CoreCommandStatus.SUCCEEDED,
                null,
                null,
                new CoreCommandResultResponse(
                        CoreResultType.WORK_SESSION_CONVERSATION_VIEW,
                        CoreTargetType.WORK_SESSION,
                        12L,
                        null),
                null,
                null,
                "ok",
                "ok"));
        when(coreCommandRepository.getReferenceById(44L)).thenReturn(consumedCommand);
        when(workSessionRepository.findWithProjectById(12L)).thenReturn(Optional.of(session));
        when(voiceNoteRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.sendActiveNotes(authenticatedOperator, new SendMobileVoiceNotesRequest(null));

        ArgumentCaptor<VoiceFocusEntity> focusCaptor = ArgumentCaptor.forClass(VoiceFocusEntity.class);
        verify(voiceFocusRepository, times(2)).save(focusCaptor.capture());
        VoiceFocusEntity savedFocus = focusCaptor.getAllValues().getLast();
        assertEquals(VoiceDomain.DEVELOPMENT, savedFocus.getDomain());
        assertEquals(7L, savedFocus.getProject().getId());
        assertEquals(12L, savedFocus.getWorkSession().getId());
        assertEquals(44L, savedFocus.getActiveCommand().getId());
        assertEquals("Conversacion activa", savedFocus.getActivity());
    }

    private VoiceFocusEntity focus() {
        ProjectEntity project = new ProjectEntity();
        project.setId(7L);
        project.setName("fomasys");
        WorkSessionEntity session = new WorkSessionEntity();
        session.setId(12L);
        session.setTitle("Conversacion activa");
        session.setProject(project);
        VoiceFocusEntity focus = new VoiceFocusEntity();
        focus.setOperator(operator);
        focus.setDomain(VoiceDomain.DEVELOPMENT);
        focus.setProject(project);
        focus.setWorkSession(session);
        focus.setActivity("reading_codex_response");
        focus.setUpdatedAt(Instant.parse("2026-05-15T00:00:00Z"));
        return focus;
    }

    private VoiceFocusEntity projectFocus() {
        VoiceFocusEntity focus = focus();
        focus.setWorkSession(null);
        focus.setActivity("Proyecto activo");
        return focus;
    }

    private VoiceNoteEntity note(String text) {
        VoiceNoteEntity note = new VoiceNoteEntity();
        note.setId(9L);
        note.setOperator(operator);
        note.setText(text);
        note.setStatus(VoiceNoteStatus.ACTIVE);
        note.setCapturedAt(Instant.parse("2026-05-15T00:00:00Z"));
        note.setCreatedAt(Instant.parse("2026-05-15T00:00:00Z"));
        note.setUpdatedAt(Instant.parse("2026-05-15T00:00:00Z"));
        return note;
    }

    private VoiceNoteSendIntentEntity sendIntent(VoiceFocusEntity focus) {
        VoiceNoteSendIntentEntity intent = new VoiceNoteSendIntentEntity();
        intent.setId(99L);
        intent.setOperator(operator);
        intent.setStatus(VoiceNoteSendIntentStatus.PENDING);
        intent.setDestinationType(VoiceNoteSendDestinationType.WORK_SESSION);
        intent.setProject(focus.getProject());
        intent.setProjectName(focus.getProject().getName());
        intent.setWorkSession(focus.getWorkSession());
        intent.setWorkSessionTitle(focus.getWorkSession().getTitle());
        intent.setNoteIdsJson("[9]");
        intent.setConfirmationToken("token");
        intent.setConfirmationPrompt("Confirmacion requerida.");
        intent.setCreatedAt(Instant.parse("2026-05-15T00:00:00Z"));
        intent.setExpiresAt(Instant.now().plusSeconds(600));
        intent.setUpdatedAt(Instant.parse("2026-05-15T00:00:00Z"));
        return intent;
    }

    private AgentRunEntity agentRun(
            Long id,
            WorkSessionEntity session,
            AgentRunStatus status,
            SessionTurnEntity resultTurn
    ) {
        SessionTurnEntity originTurn = new SessionTurnEntity();
        originTurn.setId(101L);
        originTurn.setSession(session);
        originTurn.setActor(SessionTurnActor.OPERATOR);
        originTurn.setMessageText("mensaje");
        originTurn.setCreatedAt(Instant.parse("2026-05-15T00:00:00Z"));

        AgentRunEntity run = new AgentRunEntity();
        run.setId(id);
        run.setSession(session);
        run.setOriginTurn(originTurn);
        run.setResultTurn(resultTurn);
        run.setStatus(status);
        run.setTargetRepoPath("/repo");
        run.setStartedAt(Instant.parse("2026-05-15T00:00:00Z"));
        run.setCreatedAt(Instant.parse("2026-05-15T00:00:00Z"));
        return run;
    }
}
