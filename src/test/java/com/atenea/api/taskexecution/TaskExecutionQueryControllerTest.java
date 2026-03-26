package com.atenea.api.taskexecution;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atenea.api.ApiExceptionHandler;
import com.atenea.persistence.taskexecution.TaskExecutionRunnerType;
import com.atenea.persistence.taskexecution.TaskExecutionStatus;
import com.atenea.persistence.task.TaskBranchStatus;
import com.atenea.persistence.task.TaskPullRequestStatus;
import com.atenea.persistence.task.TaskReviewOutcome;
import com.atenea.service.taskexecution.TaskExecutionQueryService;
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
class TaskExecutionQueryControllerTest {

    @Mock
    private TaskExecutionQueryService taskExecutionQueryService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new TaskExecutionQueryController(taskExecutionQueryService))
                .setControllerAdvice(new ApiExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(
                        Jackson2ObjectMapperBuilder.json().build()))
                .build();
    }

    @Test
    void getExecutionsReturnsFilteredExecutionList() throws Exception {
        when(taskExecutionQueryService.getExecutions(TaskExecutionStatus.SUCCEEDED, 7L, 5)).thenReturn(List.of(
                new TaskExecutionListItemResponse(
                        100L,
                        42L,
                        "Fix launch flow",
                        7L,
                        "Atenea",
                        TaskBranchStatus.REVIEW_PENDING,
                        TaskPullRequestStatus.OPEN,
                        TaskReviewOutcome.PENDING,
                        true,
                        true,
                        false,
                        true,
                        "ready",
                        "review_pending",
                        "complete_review",
                        "none",
                        TaskExecutionStatus.SUCCEEDED,
                        TaskExecutionRunnerType.CODEX,
                        "/workspace/repos/internal/atenea",
                        "ok",
                        null,
                        "thread-1",
                        "turn-1",
                        Instant.parse("2026-03-22T10:02:00Z"),
                        Instant.parse("2026-03-22T10:03:00Z"),
                        Instant.parse("2026-03-22T10:02:00Z"))));

        mockMvc.perform(get("/api/executions")
                        .param("status", "SUCCEEDED")
                        .param("projectId", "7")
                        .param("limit", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].projectName").value("Atenea"))
                .andExpect(jsonPath("$[0].taskTitle").value("Fix launch flow"))
                .andExpect(jsonPath("$[0].branchStatus").value("REVIEW_PENDING"))
                .andExpect(jsonPath("$[0].nextAction").value("complete_review"))
                .andExpect(jsonPath("$[0].status").value("SUCCEEDED"));
    }

    @Test
    void getExecutionsReturnsBadRequestWhenLimitIsInvalid() throws Exception {
        when(taskExecutionQueryService.getExecutions(null, null, 0))
                .thenThrow(new IllegalArgumentException("Query parameter 'limit' must be greater than 0"));

        mockMvc.perform(get("/api/executions").param("limit", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Query parameter 'limit' must be greater than 0"));
    }
}
