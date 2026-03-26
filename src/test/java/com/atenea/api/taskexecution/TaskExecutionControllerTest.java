package com.atenea.api.taskexecution;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atenea.api.ApiExceptionHandler;
import com.atenea.persistence.taskexecution.TaskExecutionRunnerType;
import com.atenea.persistence.taskexecution.TaskExecutionStatus;
import com.atenea.service.taskexecution.TaskExecutionService;
import com.atenea.service.taskexecution.TaskNotFoundException;
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
class TaskExecutionControllerTest {

    @Mock
    private TaskExecutionService taskExecutionService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new TaskExecutionController(taskExecutionService))
                .setControllerAdvice(new ApiExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(
                        Jackson2ObjectMapperBuilder.json().build()))
                .build();
    }

    @Test
    void getExecutionsReturnsExecutionHistoryIncludingExternalIds() throws Exception {
        when(taskExecutionService.getExecutions(42L)).thenReturn(List.of(
                new TaskExecutionResponse(
                        1001L,
                        42L,
                        TaskExecutionStatus.SUCCEEDED,
                        TaskExecutionRunnerType.CODEX,
                        "/workspace/repos/sandboxes/internal/atenea-preview",
                        Instant.parse("2026-03-22T10:00:00Z"),
                        Instant.parse("2026-03-22T10:05:00Z"),
                        "Applied the requested change.",
                        null,
                        "thread-123",
                        "turn-456",
                        Instant.parse("2026-03-22T10:00:00Z")),
                new TaskExecutionResponse(
                        1000L,
                        42L,
                        TaskExecutionStatus.FAILED,
                        TaskExecutionRunnerType.CODEX,
                        "/workspace/repos/internal/atenea",
                        Instant.parse("2026-03-22T09:00:00Z"),
                        Instant.parse("2026-03-22T09:03:00Z"),
                        null,
                        "Timed out",
                        "thread-111",
                        "turn-222",
                        Instant.parse("2026-03-22T09:00:00Z"))));

        mockMvc.perform(get("/api/tasks/42/executions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1001))
                .andExpect(jsonPath("$[0].status").value("SUCCEEDED"))
                .andExpect(jsonPath("$[0].externalThreadId").value("thread-123"))
                .andExpect(jsonPath("$[0].externalTurnId").value("turn-456"))
                .andExpect(jsonPath("$[1].id").value(1000))
                .andExpect(jsonPath("$[1].status").value("FAILED"))
                .andExpect(jsonPath("$[1].errorSummary").value("Timed out"));
    }

    @Test
    void getExecutionsReturnsNotFoundWhenTaskDoesNotExist() throws Exception {
        when(taskExecutionService.getExecutions(42L)).thenThrow(new TaskNotFoundException(42L));

        mockMvc.perform(get("/api/tasks/42/executions"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Task with id '42' was not found"))
                .andExpect(jsonPath("$.details").isArray());
    }

    @Test
    void getExecutionReturnsExecutionDetail() throws Exception {
        when(taskExecutionService.getExecution(42L, 1001L)).thenReturn(
                new TaskExecutionResponse(
                        1001L,
                        42L,
                        TaskExecutionStatus.SUCCEEDED,
                        TaskExecutionRunnerType.CODEX,
                        "/workspace/repos/sandboxes/internal/atenea-preview",
                        Instant.parse("2026-03-22T10:00:00Z"),
                        Instant.parse("2026-03-22T10:05:00Z"),
                        "Applied the requested change.",
                        null,
                        "thread-123",
                        "turn-456",
                        Instant.parse("2026-03-22T10:00:00Z")));

        mockMvc.perform(get("/api/tasks/42/executions/1001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1001))
                .andExpect(jsonPath("$.externalThreadId").value("thread-123"));
    }

    @Test
    void getExecutionReturnsNotFoundWhenExecutionDoesNotExist() throws Exception {
        when(taskExecutionService.getExecution(42L, 1001L))
                .thenThrow(new com.atenea.service.taskexecution.TaskExecutionNotFoundException(42L, 1001L));

        mockMvc.perform(get("/api/tasks/42/executions/1001"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Task execution with id '1001' was not found for task '42'"));
    }
}
