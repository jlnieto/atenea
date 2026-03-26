package com.atenea.api.worksession;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atenea.api.ApiExceptionHandler;
import com.atenea.persistence.worksession.AgentRunStatus;
import com.atenea.service.worksession.AgentRunService;
import com.atenea.service.worksession.WorkSessionNotFoundException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class AgentRunControllerTest {

    @Mock
    private AgentRunService agentRunService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new AgentRunController(agentRunService))
                .setControllerAdvice(new ApiExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(
                        Jackson2ObjectMapperBuilder.json().build()))
                .build();
    }

    @Test
    void getRunsReturnsRunsForSession() throws Exception {
        when(agentRunService.getRuns(12L)).thenReturn(List.of(
                new AgentRunResponse(
                        55L,
                        12L,
                        101L,
                        null,
                        AgentRunStatus.SUCCEEDED,
                        "/workspace/repos/internal/atenea",
                        "turn_123",
                        Instant.parse("2026-03-25T10:06:00Z"),
                        Instant.parse("2026-03-25T10:07:00Z"),
                        "Completed successfully",
                        null,
                        Instant.parse("2026-03-25T10:06:00Z"))));

        mockMvc.perform(get("/api/sessions/12/runs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(55))
                .andExpect(jsonPath("$[0].sessionId").value(12))
                .andExpect(jsonPath("$[0].status").value("SUCCEEDED"))
                .andExpect(jsonPath("$[0].externalTurnId").value("turn_123"))
                .andExpect(jsonPath("$[0].targetRepoPath").value("/workspace/repos/internal/atenea"));
    }

    @Test
    void getRunsReturnsNotFoundWhenSessionDoesNotExist() throws Exception {
        when(agentRunService.getRuns(12L)).thenThrow(new WorkSessionNotFoundException(12L));

        mockMvc.perform(get("/api/sessions/12/runs"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("WorkSession with id '12' was not found"));
    }
}
