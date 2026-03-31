package com.atenea.api.core;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atenea.api.ApiExceptionHandler;
import com.atenea.persistence.core.CoreChannel;
import com.atenea.persistence.core.CoreCommandStatus;
import com.atenea.persistence.core.CoreDomain;
import com.atenea.persistence.core.CoreInterpreterSource;
import com.atenea.persistence.core.CoreResultType;
import com.atenea.persistence.core.CoreRiskLevel;
import com.atenea.persistence.core.CoreTargetType;
import com.atenea.service.core.CoreCommandService;
import com.atenea.service.core.CoreStreamService;
import com.atenea.service.core.CoreVoiceCommandService;
import com.atenea.service.core.CoreUnknownIntentException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

@ExtendWith(MockitoExtension.class)
class CoreControllerTest {

    @Mock
    private CoreCommandService coreCommandService;

    @Mock
    private CoreStreamService coreStreamService;

    @Mock
    private CoreVoiceCommandService coreVoiceCommandService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new CoreController(
                        coreCommandService,
                        coreVoiceCommandService,
                        coreStreamService))
                .setControllerAdvice(new ApiExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(
                        Jackson2ObjectMapperBuilder.json().build()))
                .build();
    }

    @Test
    void createCommandReturnsCreatedResponse() throws Exception {
        CreateCoreCommandRequest request = new CreateCoreCommandRequest(
                "continua la sesion",
                CoreChannel.TEXT,
                new CoreRequestContext(7L, 12L),
                new CoreConfirmationRequest(false, null));
        when(coreCommandService.createCommand(request)).thenReturn(new CoreCommandResponse(
                101L,
                CoreCommandStatus.SUCCEEDED,
                new CoreInterpretationResponse(CoreInterpreterSource.DETERMINISTIC, "explicit_work_session_context"),
                new CoreIntentResponse(
                        "CONTINUE_WORK_SESSION",
                        CoreDomain.DEVELOPMENT,
                        "continue_work_session",
                        CoreRiskLevel.READ,
                        false,
                        BigDecimal.valueOf(0.99)),
                new CoreCommandResultResponse(
                        CoreResultType.WORK_SESSION_CONVERSATION_VIEW,
                        CoreTargetType.WORK_SESSION,
                        12L,
                        new CreateSessionTurnPayload("ok")),
                null,
                null,
                "The active WorkSession was continued successfully.",
                "The active work session was continued successfully."));

        mockMvc.perform(post("/api/core/commands")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "input": "continua la sesion",
                                  "channel": "TEXT",
                                  "context": {
                                    "projectId": 7,
                                    "workSessionId": 12
                                  },
                                  "confirmation": {
                                    "confirmed": false
                                  }
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.commandId").value(101))
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.interpretation.source").value("DETERMINISTIC"))
                .andExpect(jsonPath("$.intent.domain").value("DEVELOPMENT"))
                .andExpect(jsonPath("$.result.targetId").value(12));
    }

    @Test
    void createCommandReturnsBadRequestWhenIntentCannotBeDetermined() throws Exception {
        CreateCoreCommandRequest request = new CreateCoreCommandRequest(
                "haz algo",
                CoreChannel.TEXT,
                null,
                null);
        when(coreCommandService.createCommand(request)).thenThrow(new CoreUnknownIntentException(
                "Atenea Core could not determine a supported development intent for the current request"));

        mockMvc.perform(post("/api/core/commands")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "input": "haz algo",
                                  "channel": "TEXT"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        "Atenea Core could not determine a supported development intent for the current request"))
                .andExpect(jsonPath("$.details[0]").value("UNKNOWN_INTENT"));
    }

    @Test
    void getCommandsReturnsFilteredCommandSummaries() throws Exception {
        when(coreCommandService.getCommands(
                CoreCommandStatus.SUCCEEDED,
                CoreDomain.DEVELOPMENT,
                CoreInterpreterSource.LLM,
                "atenea")).thenReturn(new CoreCommandListResponse(List.of(
                        new CoreCommandSummaryResponse(
                                101L,
                                CoreCommandStatus.SUCCEEDED,
                                new CoreInterpretationResponse(CoreInterpreterSource.LLM, "llm_structured_classification"),
                                new CoreIntentResponse(
                                        "CONTINUE_WORK_SESSION",
                                        CoreDomain.DEVELOPMENT,
                                        "continue_work_session",
                                        CoreRiskLevel.READ,
                                        false,
                                        BigDecimal.valueOf(0.88)),
                                "continua con la sesion de Atenea",
                                "Resolved conversation",
                                null,
                                null,
                                "ok",
                                "ok",
                                Instant.parse("2026-03-30T20:00:00Z"),
                                Instant.parse("2026-03-30T20:00:01Z")))));

        mockMvc.perform(get("/api/core/commands")
                        .param("status", "SUCCEEDED")
                        .param("domain", "DEVELOPMENT")
                        .param("interpreterSource", "LLM")
                        .param("q", "atenea"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].commandId").value(101))
                .andExpect(jsonPath("$.items[0].interpretation.source").value("LLM"))
                .andExpect(jsonPath("$.items[0].intent.capability").value("continue_work_session"));
    }

    @Test
    void getCommandReturnsDetailedTrace() throws Exception {
        when(coreCommandService.getCommand(101L)).thenReturn(new CoreCommandDetailResponse(
                101L,
                "continua con la sesion de Atenea",
                CoreChannel.TEXT,
                CoreCommandStatus.SUCCEEDED,
                new CoreInterpretationResponse(CoreInterpreterSource.LLM, "llm_structured_classification"),
                CoreDomain.DEVELOPMENT,
                "CONTINUE_WORK_SESSION",
                "continue_work_session",
                CoreRiskLevel.READ,
                false,
                false,
                null,
                BigDecimal.valueOf(0.88),
                Jackson2ObjectMapperBuilder.json().build().readTree("{\"projectId\":7}"),
                Jackson2ObjectMapperBuilder.json().build().readTree("{\"workSessionId\":44}"),
                Jackson2ObjectMapperBuilder.json().build().readTree("{\"intent\":\"CONTINUE_WORK_SESSION\"}"),
                null,
                CoreResultType.WORK_SESSION_CONVERSATION_VIEW,
                CoreTargetType.WORK_SESSION,
                44L,
                "Resolved conversation",
                null,
                null,
                "ok",
                "ok",
                Instant.parse("2026-03-30T20:00:00Z"),
                Instant.parse("2026-03-30T20:00:01Z")));

        mockMvc.perform(get("/api/core/commands/101"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.commandId").value(101))
                .andExpect(jsonPath("$.requestContext.projectId").value(7))
                .andExpect(jsonPath("$.parameters.workSessionId").value(44))
                .andExpect(jsonPath("$.targetId").value(44));
    }

    @Test
    void confirmCommandReturnsExecutedResponse() throws Exception {
        ConfirmCoreCommandRequest request = new ConfirmCoreCommandRequest("token-123");
        when(coreCommandService.confirmCommand(101L, request)).thenReturn(new CoreCommandResponse(
                101L,
                CoreCommandStatus.SUCCEEDED,
                new CoreInterpretationResponse(CoreInterpreterSource.LLM, "llm_structured_classification"),
                new CoreIntentResponse(
                        "CONTINUE_WORK_SESSION",
                        CoreDomain.DEVELOPMENT,
                        "continue_work_session",
                        CoreRiskLevel.READ,
                        true,
                        BigDecimal.valueOf(0.88)),
                new CoreCommandResultResponse(
                        CoreResultType.WORK_SESSION_CONVERSATION_VIEW,
                        CoreTargetType.WORK_SESSION,
                        44L,
                        new CreateSessionTurnPayload("ok")),
                null,
                null,
                "The active WorkSession was continued successfully.",
                "The active work session was continued successfully."));

        mockMvc.perform(post("/api/core/commands/101/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "confirmationToken": "token-123"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.commandId").value(101))
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.result.targetId").value(44));
    }

    @Test
    void getCommandEventsReturnsTimeline() throws Exception {
        when(coreCommandService.getCommandEvents(101L)).thenReturn(new CoreCommandEventsResponse(
                101L,
                List.of(new CoreCommandEventResponse(
                        1L,
                        com.atenea.persistence.core.CoreCommandEventPhase.INTERPRETING,
                        "Interpreting command intent",
                        null,
                        Instant.parse("2026-03-31T07:00:00Z"))),
                Instant.parse("2026-03-31T07:00:01Z")));

        mockMvc.perform(get("/api/core/commands/101/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.commandId").value(101))
                .andExpect(jsonPath("$.events[0].phase").value("INTERPRETING"));
    }

    @Test
    void createVoiceCommandReturnsCreatedResponse() throws Exception {
        MockMultipartFile audio = new MockMultipartFile(
                "audio",
                "voice-command.m4a",
                "audio/mp4",
                "fake-audio".getBytes());
        when(coreVoiceCommandService.createVoiceCommand(audio, 7L, 12L, "operator@atenea.local"))
                .thenReturn(new CoreVoiceCommandResponse(
                        "continua con la sesion",
                        new CoreCommandResponse(
                                101L,
                                CoreCommandStatus.SUCCEEDED,
                                new CoreInterpretationResponse(CoreInterpreterSource.DETERMINISTIC, "explicit_work_session_context"),
                                new CoreIntentResponse(
                                        "CONTINUE_WORK_SESSION",
                                        CoreDomain.DEVELOPMENT,
                                        "continue_work_session",
                                        CoreRiskLevel.READ,
                                        false,
                                        BigDecimal.valueOf(0.99)),
                                new CoreCommandResultResponse(
                                        CoreResultType.WORK_SESSION_CONVERSATION_VIEW,
                                        CoreTargetType.WORK_SESSION,
                                        12L,
                                        new CreateSessionTurnPayload("ok")),
                                null,
                                null,
                                "The active WorkSession was continued successfully.",
                                "The active work session was continued successfully.")));

        mockMvc.perform(multipart("/api/core/voice/commands")
                        .file(audio)
                        .param("projectId", "7")
                        .param("workSessionId", "12")
                        .param("operatorKey", "operator@atenea.local"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.transcript").value("continua con la sesion"))
                .andExpect(jsonPath("$.command.commandId").value(101))
                .andExpect(jsonPath("$.command.result.targetId").value(12));
    }

    private record CreateSessionTurnPayload(String state) {
    }
}
