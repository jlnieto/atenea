package com.atenea.api.task;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atenea.api.ApiExceptionHandler;
import com.atenea.api.taskexecution.TaskExecutionResponse;
import com.atenea.persistence.taskexecution.TaskExecutionRunnerType;
import com.atenea.persistence.taskexecution.TaskExecutionStatus;
import com.atenea.service.taskexecution.TaskExecutionService;
import com.atenea.service.taskexecution.TaskLaunchBlockedException;
import com.atenea.service.taskexecution.TaskNotFoundException;
import com.atenea.service.taskexecution.TaskRelaunchNotAllowedException;
import java.time.Instant;
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
class TaskLaunchControllerTest {

    @Mock
    private TaskExecutionService taskExecutionService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new TaskLaunchController(taskExecutionService))
                .setControllerAdvice(new ApiExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(
                        Jackson2ObjectMapperBuilder.json().build()))
                .build();
    }

    @Test
    void launchTaskReturnsCreatedExecutionWithExternalIds() throws Exception {
        when(taskExecutionService.launchTask(42L)).thenReturn(new TaskExecutionResponse(
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

        mockMvc.perform(post("/api/tasks/42/launch"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1001))
                .andExpect(jsonPath("$.taskId").value(42))
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.runnerType").value("CODEX"))
                .andExpect(jsonPath("$.targetRepoPath").value("/workspace/repos/sandboxes/internal/atenea-preview"))
                .andExpect(jsonPath("$.outputSummary").value("Applied the requested change."))
                .andExpect(jsonPath("$.errorSummary").value(nullValue()))
                .andExpect(jsonPath("$.externalThreadId").value("thread-123"))
                .andExpect(jsonPath("$.externalTurnId").value("turn-456"));
    }

    @Test
    void launchTaskReturnsNotFoundWhenTaskDoesNotExist() throws Exception {
        when(taskExecutionService.launchTask(42L)).thenThrow(new TaskNotFoundException(42L));

        mockMvc.perform(post("/api/tasks/42/launch"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Task with id '42' was not found"))
                .andExpect(jsonPath("$.details").isArray());
    }

    @Test
    void launchTaskReturnsConflictWhenLaunchIsBlocked() throws Exception {
        when(taskExecutionService.launchTask(42L))
                .thenThrow(new TaskLaunchBlockedException("Project 'Atenea' is locked by task '41'"));

        mockMvc.perform(post("/api/tasks/42/launch"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Project 'Atenea' is locked by task '41'"));
    }

    @Test
    void launchTaskReturnsConflictWhenTaskNeedsClarification() throws Exception {
        when(taskExecutionService.launchTask(42L))
                .thenThrow(new TaskLaunchBlockedException(
                        "Task requires clarification before launch: task description is required for automatic execution"));

        mockMvc.perform(post("/api/tasks/42/launch"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        "Task requires clarification before launch: task description is required for automatic execution"));
    }

    @Test
    void relaunchTaskReturnsCreatedExecution() throws Exception {
        when(taskExecutionService.relaunchTask(42L)).thenReturn(new TaskExecutionResponse(
                1002L,
                42L,
                TaskExecutionStatus.SUCCEEDED,
                TaskExecutionRunnerType.CODEX,
                "/workspace/repos/internal/atenea",
                Instant.parse("2026-03-22T11:00:00Z"),
                Instant.parse("2026-03-22T11:05:00Z"),
                "Retried the same task branch.",
                null,
                "thread-789",
                "turn-987",
                Instant.parse("2026-03-22T11:00:00Z")));

        mockMvc.perform(post("/api/tasks/42/relaunch"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1002))
                .andExpect(jsonPath("$.outputSummary").value("Retried the same task branch."));
    }

    @Test
    void relaunchTaskReturnsConflictWhenNoPreviousExecutionExists() throws Exception {
        when(taskExecutionService.relaunchTask(42L))
                .thenThrow(new TaskRelaunchNotAllowedException(42L, "no previous execution exists"));

        mockMvc.perform(post("/api/tasks/42/relaunch"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Task '42' cannot be relaunched: no previous execution exists"));
    }
}
