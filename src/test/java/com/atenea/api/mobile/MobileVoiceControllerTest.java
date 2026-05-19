package com.atenea.api.mobile;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atenea.api.ApiExceptionHandler;
import com.atenea.auth.AuthenticatedOperator;
import com.atenea.persistence.voice.VoiceDomain;
import com.atenea.persistence.voice.VoiceNoteSendDestinationType;
import com.atenea.persistence.voice.VoiceNoteSendIntentStatus;
import com.atenea.persistence.voice.VoiceNoteStatus;
import com.atenea.persistence.worksession.AgentRunStatus;
import com.atenea.service.core.CoreSpeechAudioResponse;
import com.atenea.service.core.CoreSpeechSynthesisService;
import com.atenea.service.voice.MobileVoiceRealtimeSessionService;
import com.atenea.service.voice.VoiceCommandTelemetryService;
import com.atenea.service.voice.VoiceEngineService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class MobileVoiceControllerTest {

    @Mock
    private VoiceEngineService voiceEngineService;

    @Mock
    private CoreSpeechSynthesisService coreSpeechSynthesisService;

    @Mock
    private MobileVoiceRealtimeSessionService mobileVoiceRealtimeSessionService;

    @Mock
    private VoiceCommandTelemetryService voiceCommandTelemetryService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new MobileVoiceController(
                        voiceEngineService,
                        coreSpeechSynthesisService,
                        mobileVoiceRealtimeSessionService,
                        voiceCommandTelemetryService))
                .setControllerAdvice(new ApiExceptionHandler())
                .setMessageConverters(
                        new ByteArrayHttpMessageConverter(),
                        new MappingJackson2HttpMessageConverter(Jackson2ObjectMapperBuilder.json().build()))
                .build();
    }

    @Test
    void recordsVoiceCommandTelemetry() throws Exception {
        when(voiceCommandTelemetryService.record(any(), any())).thenReturn(new MobileVoiceCommandTelemetryResponse(
                21L,
                "client-1",
                "android_realtime",
                "UNRECOGNIZED",
                "empty_intent",
                "Atenea, li nota uno",
                "atenea, li nota uno",
                true,
                true,
                "Empty",
                "DEVELOPMENT",
                7L,
                "fomasys",
                12L,
                "Sesion",
                44L,
                2,
                null,
                true,
                "IDLE",
                Instant.parse("2026-05-19T10:00:00Z")));

        mockMvc.perform(post("/api/mobile/voice/command-telemetry")
                        .with(operator())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "clientEventId": "client-1",
                                  "source": "android_realtime",
                                  "outcome": "UNRECOGNIZED",
                                  "reason": "empty_intent",
                                  "transcript": "Atenea, li nota uno",
                                  "normalizedTranscript": "atenea, li nota uno",
                                  "wakeWordDetected": true,
                                  "startsWithWakeWord": true,
                                  "intentType": "Empty",
                                  "domain": "DEVELOPMENT",
                                  "projectId": 7,
                                  "projectName": "fomasys",
                                  "workSessionId": 12,
                                  "workSessionTitle": "Sesion",
                                  "activeCommandId": 44,
                                  "activeNoteCount": 2,
                                  "realtimeConnected": true,
                                  "voiceState": "IDLE"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(21))
                .andExpect(jsonPath("$.reason").value("empty_intent"))
                .andExpect(jsonPath("$.transcript").value("Atenea, li nota uno"))
                .andExpect(jsonPath("$.activeNoteCount").value(2));
    }

    @Test
    void getFocusReturnsCurrentVoiceFocus() throws Exception {
        when(voiceEngineService.getFocus(any())).thenReturn(new MobileVoiceFocusResponse(
                4L,
                VoiceDomain.DEVELOPMENT,
                7L,
                "fomasys",
                12L,
                "Conversacion activa",
                44L,
                44L,
                true,
                null,
                null,
                "reading_codex_response",
                new MobileVoicePlaybackResponse("SESSION_TURN", "turn-99", 2, 6),
                1,
                Instant.parse("2026-05-15T00:00:00Z")));

        mockMvc.perform(get("/api/mobile/voice/focus").with(operator()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.domain").value("DEVELOPMENT"))
                .andExpect(jsonPath("$.projectName").value("fomasys"))
                .andExpect(jsonPath("$.playback.segmentIndex").value(2))
                .andExpect(jsonPath("$.activeNoteCount").value(1));
    }

    @Test
    void createNoteReturnsStoredVoiceNote() throws Exception {
        when(voiceEngineService.createNote(any(), any())).thenReturn(new MobileVoiceNoteResponse(
                9L,
                "Revisar permisos",
                VoiceNoteStatus.ACTIVE,
                "{\"domain\":\"DEVELOPMENT\"}",
                null,
                Instant.parse("2026-05-15T00:00:00Z"),
                null,
                Instant.parse("2026-05-15T00:00:00Z")));

        mockMvc.perform(post("/api/mobile/voice/notes")
                        .with(operator())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"Revisar permisos\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(9))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.text").value("Revisar permisos"));
    }

    @Test
    void activeNotesReturnsGlobalVoiceNotes() throws Exception {
        when(voiceEngineService.getActiveNotes(any())).thenReturn(new MobileVoiceNotesResponse(List.of(
                new MobileVoiceNoteResponse(
                        9L,
                        "Revisar permisos",
                        VoiceNoteStatus.ACTIVE,
                        "{\"domain\":\"DEVELOPMENT\"}",
                        null,
                        Instant.parse("2026-05-15T00:00:00Z"),
                        null,
                        Instant.parse("2026-05-15T00:00:00Z")))));

        mockMvc.perform(get("/api/mobile/voice/notes/active").with(operator()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notes[0].text").value("Revisar permisos"));
    }

    @Test
    void notesStateReturnsFocusNotesAndPendingSendIntent() throws Exception {
        when(voiceEngineService.getNotesState(any())).thenReturn(new MobileVoiceNotesStateResponse(
                new MobileVoiceFocusResponse(
                        4L,
                        VoiceDomain.DEVELOPMENT,
                        7L,
                        "fomasys",
                        12L,
                        "Conversacion activa",
                        null,
                        null,
                        true,
                        null,
                        null,
                        "Conversacion activa",
                        null,
                        1,
                        Instant.parse("2026-05-15T00:00:00Z")),
                List.of(new MobileVoiceNoteResponse(
                        9L,
                        "Revisar permisos",
                        VoiceNoteStatus.ACTIVE,
                        "{\"domain\":\"DEVELOPMENT\"}",
                        null,
                        Instant.parse("2026-05-15T00:00:00Z"),
                        null,
                        Instant.parse("2026-05-15T00:00:00Z"))),
                new MobileVoiceNoteSendIntentResponse(
                        77L,
                        VoiceNoteSendIntentStatus.PENDING,
                        VoiceNoteSendDestinationType.WORK_SESSION,
                        7L,
                        "fomasys",
                        12L,
                        "Conversacion activa",
                        List.of(9L),
                        1,
                        null,
                        "Confirmacion requerida",
                        null,
                        null,
                        Instant.parse("2026-05-15T00:00:00Z"),
                        Instant.parse("2026-05-15T00:10:00Z"),
                        Instant.parse("2026-05-15T00:00:00Z"))));

        mockMvc.perform(get("/api/mobile/voice/notes/state").with(operator()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.focus.projectName").value("fomasys"))
                .andExpect(jsonPath("$.notes[0].text").value("Revisar permisos"))
                .andExpect(jsonPath("$.pendingSendIntent.status").value("PENDING"))
                .andExpect(jsonPath("$.pendingSendIntent.workSessionTitle").value("Conversacion activa"));
    }

    @Test
    void archiveNoteReturnsArchivedVoiceNote() throws Exception {
        when(voiceEngineService.archiveNote(any(), eq(9L))).thenReturn(new MobileVoiceNoteResponse(
                9L,
                "Revisar permisos",
                VoiceNoteStatus.ARCHIVED,
                "{\"domain\":\"DEVELOPMENT\"}",
                null,
                Instant.parse("2026-05-15T00:00:00Z"),
                Instant.parse("2026-05-15T00:01:00Z"),
                Instant.parse("2026-05-15T00:01:00Z")));

        mockMvc.perform(post("/api/mobile/voice/notes/9/archive").with(operator()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(9))
                .andExpect(jsonPath("$.status").value("ARCHIVED"));
    }

    @Test
    void archiveLastNoteReturnsArchivedVoiceNote() throws Exception {
        when(voiceEngineService.archiveLastActiveNote(any())).thenReturn(new MobileVoiceNoteResponse(
                10L,
                "Nota basura",
                VoiceNoteStatus.ARCHIVED,
                "{\"domain\":\"DEVELOPMENT\"}",
                null,
                Instant.parse("2026-05-15T00:00:00Z"),
                Instant.parse("2026-05-15T00:01:00Z"),
                Instant.parse("2026-05-15T00:01:00Z")));

        mockMvc.perform(post("/api/mobile/voice/notes/archive-last").with(operator()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(10))
                .andExpect(jsonPath("$.status").value("ARCHIVED"));
    }

    @Test
    void archiveActiveNotesReturnsArchivedVoiceNotes() throws Exception {
        when(voiceEngineService.archiveActiveNotes(any())).thenReturn(new MobileVoiceNotesResponse(List.of(
                new MobileVoiceNoteResponse(
                        9L,
                        "Revisar permisos",
                        VoiceNoteStatus.ARCHIVED,
                        "{\"domain\":\"DEVELOPMENT\"}",
                        null,
                        Instant.parse("2026-05-15T00:00:00Z"),
                        Instant.parse("2026-05-15T00:01:00Z"),
                        Instant.parse("2026-05-15T00:01:00Z")))));

        mockMvc.perform(post("/api/mobile/voice/notes/archive-active").with(operator()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notes[0].id").value(9))
                .andExpect(jsonPath("$.notes[0].status").value("ARCHIVED"));
    }

    @Test
    void createNoteSendIntentReturnsDestinationConfirmation() throws Exception {
        when(voiceEngineService.createSendIntent(any(), any())).thenReturn(new MobileVoiceNoteSendIntentResponse(
                77L,
                VoiceNoteSendIntentStatus.PENDING,
                VoiceNoteSendDestinationType.WORK_SESSION,
                7L,
                "fomasys",
                12L,
                "Conversacion activa",
                List.of(9L),
                1,
                "prioriza lo urgente",
                "Confirmacion requerida",
                null,
                null,
                Instant.parse("2026-05-15T00:00:00Z"),
                Instant.parse("2026-05-15T00:10:00Z"),
                Instant.parse("2026-05-15T00:00:00Z")));

        mockMvc.perform(post("/api/mobile/voice/notes/send-intents")
                        .with(operator())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"instruction\":\"prioriza lo urgente\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(77))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.projectName").value("fomasys"))
                .andExpect(jsonPath("$.workSessionTitle").value("Conversacion activa"));
    }

    @Test
    void confirmNoteSendIntentReturnsCodexRunTracking() throws Exception {
        when(voiceEngineService.confirmSendIntent(any(), eq(77L))).thenReturn(new MobileVoiceNoteSendConfirmResponse(
                new MobileVoiceNoteSendIntentResponse(
                        77L,
                        VoiceNoteSendIntentStatus.SENT,
                        VoiceNoteSendDestinationType.WORK_SESSION,
                        7L,
                        "fomasys",
                        12L,
                        "Conversacion activa",
                        List.of(9L),
                        1,
                        null,
                        "Confirmacion requerida",
                        null,
                        88L,
                        Instant.parse("2026-05-15T00:00:00Z"),
                        Instant.parse("2026-05-15T00:10:00Z"),
                        Instant.parse("2026-05-15T00:01:00Z")),
                List.of(new MobileVoiceNoteResponse(
                        9L,
                        "Revisar permisos",
                        VoiceNoteStatus.SENT,
                        "{\"domain\":\"DEVELOPMENT\"}",
                        null,
                        Instant.parse("2026-05-15T00:00:00Z"),
                        Instant.parse("2026-05-15T00:01:00Z"),
                        Instant.parse("2026-05-15T00:01:00Z"))),
                55L,
                88L,
                "Notas enviadas a Codex en fomasys, sesion Conversacion activa. Estoy esperando respuesta."));

        mockMvc.perform(post("/api/mobile/voice/notes/send-intents/77/confirm").with(operator()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.intent.status").value("SENT"))
                .andExpect(jsonPath("$.consumedNotes[0].status").value("SENT"))
                .andExpect(jsonPath("$.operatorTurnId").value(55))
                .andExpect(jsonPath("$.agentRunId").value(88));
    }

    @Test
    void latestCodexStatusReturnsTrackedRunState() throws Exception {
        when(voiceEngineService.getLatestCodexStatus(any())).thenReturn(new MobileVoiceCodexStatusResponse(
                7L,
                "fomasys",
                12L,
                "Conversacion activa",
                88L,
                AgentRunStatus.RUNNING,
                false,
                false,
                "Codex sigue trabajando en fomasys, sesion Conversacion activa.",
                Instant.parse("2026-05-15T00:01:00Z")));

        mockMvc.perform(get("/api/mobile/voice/codex/latest-status").with(operator()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectName").value("fomasys"))
                .andExpect(jsonPath("$.agentRunId").value(88))
                .andExpect(jsonPath("$.runStatus").value("RUNNING"))
                .andExpect(jsonPath("$.responseReady").value(false))
                .andExpect(jsonPath("$.message").value("Codex sigue trabajando en fomasys, sesion Conversacion activa."));
    }

    @Test
    void createSpeechReturnsPremiumAudio() throws Exception {
        when(coreSpeechSynthesisService.synthesizeText("Hola desde Atenea", "marin", 1.05d)).thenReturn(new CoreSpeechAudioResponse(
                "premium-mp3".getBytes(),
                MediaType.parseMediaType("audio/mpeg")));

        mockMvc.perform(post("/api/mobile/voice/speech")
                        .with(operator())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"Hola desde Atenea\",\"voice\":\"marin\",\"speed\":1.05}"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().contentType("audio/mpeg"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().bytes("premium-mp3".getBytes()));
    }

    @Test
    void createRealtimeSessionReturnsEphemeralClientSecret() throws Exception {
        when(voiceEngineService.getFocus(any())).thenReturn(new MobileVoiceFocusResponse(
                4L,
                VoiceDomain.DEVELOPMENT,
                7L,
                "fomasys",
                12L,
                "Conversacion activa",
                44L,
                44L,
                true,
                null,
                null,
                "reading_codex_response",
                new MobileVoicePlaybackResponse("SESSION_TURN", "turn-99", 2, 6),
                0,
                Instant.parse("2026-05-15T00:00:00Z")));
        when(voiceEngineService.getActiveNotes(any())).thenReturn(new MobileVoiceNotesResponse(List.of()));
        when(mobileVoiceRealtimeSessionService.createSession(anyString(), any(), any())).thenReturn(new MobileVoiceRealtimeSessionResponse(
                "openai",
                "realtime",
                "gpt-realtime",
                "marin",
                "eph_secret",
                1778810000L,
                "ready"));

        mockMvc.perform(post("/api/mobile/voice/realtime/session").with(operator()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.provider").value("openai"))
                .andExpect(jsonPath("$.model").value("gpt-realtime"))
                .andExpect(jsonPath("$.clientSecret").value("eph_secret"));
    }

    @Test
    void createRealtimeSessionIncludesClientContextWhenProvided() throws Exception {
        when(voiceEngineService.getFocus(any())).thenReturn(new MobileVoiceFocusResponse(
                4L,
                VoiceDomain.DEVELOPMENT,
                7L,
                "fomasys",
                12L,
                "Conversacion activa",
                44L,
                44L,
                true,
                null,
                null,
                "reading_codex_response",
                null,
                0,
                Instant.parse("2026-05-15T00:00:00Z")));
        when(voiceEngineService.getActiveNotes(any())).thenReturn(new MobileVoiceNotesResponse(List.of()));
        when(mobileVoiceRealtimeSessionService.createSession(anyString(), any(), any())).thenReturn(new MobileVoiceRealtimeSessionResponse(
                "openai",
                "realtime",
                "gpt-realtime",
                "marin",
                "eph_secret",
                1778810000L,
                "ready"));

        mockMvc.perform(post("/api/mobile/voice/realtime/session")
                        .with(operator())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientContext\":\"Lectura actual: segmento 1 de 3.\"}"))
                .andExpect(status().isOk());

        verify(mobileVoiceRealtimeSessionService).createSession(
                contains("Lectura actual: segmento 1 de 3."),
                any(),
                any());
    }

    private RequestPostProcessor operator() {
        return SecurityMockMvcRequestPostProcessors.authentication(
                new UsernamePasswordAuthenticationToken(
                        new AuthenticatedOperator(4L, "operator@atenea.local", "Operator"),
                        null));
    }
}
