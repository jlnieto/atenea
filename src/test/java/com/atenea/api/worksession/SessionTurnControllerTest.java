package com.atenea.api.worksession;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atenea.api.ApiExceptionHandler;
import com.atenea.persistence.worksession.AgentRunStatus;
import com.atenea.persistence.worksession.SessionTurnActor;
import com.atenea.persistence.worksession.WorkSessionStatus;
import com.atenea.service.worksession.AgentRunAlreadyRunningException;
import com.atenea.service.worksession.SessionTurnService;
import com.atenea.service.worksession.WorkSessionNotOpenException;
import com.atenea.service.worksession.WorkSessionNotFoundException;
import com.atenea.service.worksession.WorkSessionTurnExecutionFailedException;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class SessionTurnControllerTest {

    @Mock
    private SessionTurnService sessionTurnService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new SessionTurnController(sessionTurnService))
                .setControllerAdvice(new ApiExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(
                        Jackson2ObjectMapperBuilder.json().build()))
                .build();
    }

    @Test
    void getTurnsReturnsVisibleConversationHistory() throws Exception {
        when(sessionTurnService.getTurns(12L)).thenReturn(List.of(
                new SessionTurnResponse(
                        101L,
                        SessionTurnActor.OPERATOR,
                        "Explain the current implementation",
                        Instant.parse("2026-03-25T10:05:00Z")),
                new SessionTurnResponse(
                        102L,
                        SessionTurnActor.CODEX,
                        "The implementation is split into services",
                        Instant.parse("2026-03-25T10:06:00Z"))));

        mockMvc.perform(get("/api/sessions/12/turns"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(101))
                .andExpect(jsonPath("$[0].actor").value("OPERATOR"))
                .andExpect(jsonPath("$[0].messageText").value("Explain the current implementation"))
                .andExpect(jsonPath("$[1].id").value(102))
                .andExpect(jsonPath("$[1].actor").value("CODEX"));
    }

    @Test
    void getTurnsReturnsNotFoundWhenSessionDoesNotExist() throws Exception {
        when(sessionTurnService.getTurns(12L)).thenThrow(new WorkSessionNotFoundException(12L));

        mockMvc.perform(get("/api/sessions/12/turns"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("WorkSession with id '12' was not found"));
    }

    @Test
    void createTurnReturnsCreatedConversationTurn() throws Exception {
        when(sessionTurnService.createTurn(12L, new CreateSessionTurnRequest("Inspect the project")))
                .thenReturn(new CreateSessionTurnResponse(
                        new SessionTurnResponse(
                                101L,
                                SessionTurnActor.OPERATOR,
                                "Inspect the project",
                                Instant.parse("2026-03-25T10:05:00Z")),
                        new AgentRunResponse(
                                55L,
                                12L,
                                101L,
                                102L,
                                AgentRunStatus.SUCCEEDED,
                                "/workspace/repos/internal/atenea",
                                "turn-1",
                                Instant.parse("2026-03-25T10:05:01Z"),
                                Instant.parse("2026-03-25T10:05:02Z"),
                                "Current status summary",
                                null,
                                Instant.parse("2026-03-25T10:05:01Z")),
                        new SessionTurnResponse(
                                102L,
                                SessionTurnActor.CODEX,
                                "Current status summary",
                                Instant.parse("2026-03-25T10:05:02Z"))));

        mockMvc.perform(post("/api/sessions/12/turns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "message": "Inspect the project"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.operatorTurn.id").value(101))
                .andExpect(jsonPath("$.operatorTurn.actor").value("OPERATOR"))
                .andExpect(jsonPath("$.run.id").value(55))
                .andExpect(jsonPath("$.run.externalTurnId").value("turn-1"))
                .andExpect(jsonPath("$.codexTurn.actor").value("CODEX"));
    }

    @Test
    void createTurnReturnsConflictWhenSessionIsNotOpen() throws Exception {
        when(sessionTurnService.createTurn(12L, new CreateSessionTurnRequest("Inspect the project")))
                .thenThrow(new WorkSessionNotOpenException(12L, WorkSessionStatus.CLOSED));

        mockMvc.perform(post("/api/sessions/12/turns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "message": "Inspect the project"
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        "WorkSession with id '12' is not OPEN (current status: CLOSED)"));
    }

    @Test
    void createTurnReturnsConflictWhenRunIsAlreadyRunning() throws Exception {
        when(sessionTurnService.createTurn(12L, new CreateSessionTurnRequest("Inspect the project")))
                .thenThrow(new AgentRunAlreadyRunningException(12L));

        mockMvc.perform(post("/api/sessions/12/turns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "message": "Inspect the project"
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        "WorkSession with id '12' already has a running AgentRun"));
    }

    @Test
    void createTurnReturnsBadGatewayWhenCodexExecutionFails() throws Exception {
        when(sessionTurnService.createTurn(12L, new CreateSessionTurnRequest("Inspect the project")))
                .thenThrow(new WorkSessionTurnExecutionFailedException("Codex execution failed for WorkSession turn"));

        mockMvc.perform(post("/api/sessions/12/turns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "message": "Inspect the project"
                                }
                                """))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.message").value("Codex execution failed for WorkSession turn"));
    }
}
