package com.atenea.api.task;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atenea.api.ApiExceptionHandler;
import com.atenea.github.GitHubIntegrationException;
import com.atenea.persistence.task.TaskBranchStatus;
import com.atenea.persistence.task.TaskPriority;
import com.atenea.persistence.task.TaskPullRequestStatus;
import com.atenea.persistence.task.TaskReviewOutcome;
import com.atenea.persistence.task.TaskStatus;
import com.atenea.api.task.UpdateTaskPullRequestRequest;
import com.atenea.api.task.UpdateTaskReviewOutcomeRequest;
import com.atenea.service.task.TaskWorkflowService;
import com.atenea.service.task.TaskGitHubService;
import com.atenea.service.task.TaskWorkflowTransitionNotAllowedException;
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
class TaskWorkflowControllerTest {

    @Mock
    private TaskWorkflowService taskWorkflowService;

    @Mock
    private TaskGitHubService taskGitHubService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new TaskWorkflowController(taskWorkflowService, taskGitHubService))
                .setControllerAdvice(new ApiExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(
                        Jackson2ObjectMapperBuilder.json().build()))
                .build();
    }

    @Test
    void markReviewPendingReturnsUpdatedTask() throws Exception {
        when(taskWorkflowService.markReviewPending(42L)).thenReturn(taskResponse(TaskStatus.DONE, TaskBranchStatus.REVIEW_PENDING));

        mockMvc.perform(post("/api/tasks/42/review-pending"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"))
                .andExpect(jsonPath("$.branchStatus").value("REVIEW_PENDING"));
    }

    @Test
    void closeBranchReturnsUpdatedTask() throws Exception {
        when(taskWorkflowService.closeBranch(42L)).thenReturn(taskResponse(TaskStatus.DONE, TaskBranchStatus.CLOSED));

        mockMvc.perform(post("/api/tasks/42/close-branch"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.branchStatus").value("CLOSED"));
    }

    @Test
    void abandonReturnsUpdatedTask() throws Exception {
        when(taskWorkflowService.abandon(42L)).thenReturn(taskResponse(TaskStatus.CANCELLED, TaskBranchStatus.CLOSED));

        mockMvc.perform(post("/api/tasks/42/abandon"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.branchStatus").value("CLOSED"));
    }

    @Test
    void workflowTransitionReturnsConflictWhenNotAllowed() throws Exception {
        when(taskWorkflowService.closeBranch(42L))
                .thenThrow(new TaskWorkflowTransitionNotAllowedException(42L, "close branch requires REVIEW_PENDING + DONE"));

        mockMvc.perform(post("/api/tasks/42/close-branch"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        "Task '42' cannot change workflow state: close branch requires REVIEW_PENDING + DONE"));
    }

    @Test
    void updatePullRequestReturnsUpdatedTask() throws Exception {
        when(taskWorkflowService.updatePullRequest(42L, new UpdateTaskPullRequestRequest(
                "https://git.example/pr/42",
                TaskPullRequestStatus.OPEN
        ))).thenReturn(taskResponse(TaskStatus.DONE, TaskBranchStatus.REVIEW_PENDING,
                "https://git.example/pr/42", TaskPullRequestStatus.OPEN));

        mockMvc.perform(post("/api/tasks/42/pull-request")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "pullRequestUrl": "https://git.example/pr/42",
                                  "pullRequestStatus": "OPEN"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pullRequestUrl").value("https://git.example/pr/42"))
                .andExpect(jsonPath("$.pullRequestStatus").value("OPEN"));
    }

    @Test
    void updateReviewOutcomeReturnsUpdatedTask() throws Exception {
        when(taskWorkflowService.updateReviewOutcome(42L, new UpdateTaskReviewOutcomeRequest(
                TaskReviewOutcome.APPROVED_FOR_CLOSURE,
                "Operator validated and approved closure."
        ))).thenReturn(taskResponse(
                TaskStatus.DONE,
                TaskBranchStatus.REVIEW_PENDING,
                "https://git.example/pr/42",
                TaskPullRequestStatus.APPROVED,
                TaskReviewOutcome.APPROVED_FOR_CLOSURE,
                "Operator validated and approved closure."));

        mockMvc.perform(post("/api/tasks/42/review-outcome")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "reviewOutcome": "APPROVED_FOR_CLOSURE",
                                  "reviewNotes": "Operator validated and approved closure."
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewOutcome").value("APPROVED_FOR_CLOSURE"))
                .andExpect(jsonPath("$.reviewNotes").value("Operator validated and approved closure."));
    }

    @Test
    void createPullRequestReturnsUpdatedTask() throws Exception {
        when(taskGitHubService.createPullRequest(42L)).thenReturn(taskResponse(
                TaskStatus.DONE,
                TaskBranchStatus.REVIEW_PENDING,
                "https://github.com/acme/atenea/pull/42",
                TaskPullRequestStatus.OPEN,
                TaskReviewOutcome.PENDING,
                null));

        mockMvc.perform(post("/api/tasks/42/pull-request/create"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pullRequestUrl").value("https://github.com/acme/atenea/pull/42"))
                .andExpect(jsonPath("$.pullRequestStatus").value("OPEN"));
    }

    @Test
    void syncPullRequestReturnsBadGatewayWhenGitHubTokenFails() throws Exception {
        when(taskGitHubService.syncPullRequest(42L))
                .thenThrow(new GitHubIntegrationException("GitHub token is invalid or expired: Bad credentials"));

        mockMvc.perform(post("/api/tasks/42/pull-request/sync"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.message").value("GitHub token is invalid or expired: Bad credentials"));
    }

    private static TaskResponse taskResponse(
            TaskStatus status,
            TaskBranchStatus branchStatus,
            String pullRequestUrl,
            TaskPullRequestStatus pullRequestStatus,
            TaskReviewOutcome reviewOutcome,
            String reviewNotes
    ) {
        return new TaskResponse(
                42L,
                7L,
                "Fix launch flow",
                "desc",
                "main",
                "task/42-fix-launch-flow",
                branchStatus,
                pullRequestUrl,
                pullRequestStatus,
                reviewOutcome,
                reviewNotes,
                branchStatus != TaskBranchStatus.CLOSED,
                false,
                false,
                true,
                "ready",
                branchStatus == TaskBranchStatus.CLOSED ? "none" : "review_pending",
                branchStatus == TaskBranchStatus.CLOSED ? "none" : "complete_review",
                "none",
                status,
                TaskPriority.NORMAL,
                Instant.parse("2026-03-22T10:00:00Z"),
                Instant.parse("2026-03-22T10:05:00Z"));
    }

    private static TaskResponse taskResponse(TaskStatus status, TaskBranchStatus branchStatus) {
        return taskResponse(
                status,
                branchStatus,
                null,
                TaskPullRequestStatus.NOT_CREATED,
                TaskReviewOutcome.PENDING,
                null);
    }

    private static TaskResponse taskResponse(
            TaskStatus status,
            TaskBranchStatus branchStatus,
            String pullRequestUrl,
            TaskPullRequestStatus pullRequestStatus
    ) {
        return taskResponse(status, branchStatus, pullRequestUrl, pullRequestStatus, TaskReviewOutcome.PENDING, null);
    }
}
