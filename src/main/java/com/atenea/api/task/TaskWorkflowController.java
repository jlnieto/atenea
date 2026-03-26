package com.atenea.api.task;

import com.atenea.service.task.TaskWorkflowService;
import com.atenea.service.task.TaskGitHubService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tasks/{taskId}")
public class TaskWorkflowController {

    private final TaskWorkflowService taskWorkflowService;
    private final TaskGitHubService taskGitHubService;

    public TaskWorkflowController(TaskWorkflowService taskWorkflowService, TaskGitHubService taskGitHubService) {
        this.taskWorkflowService = taskWorkflowService;
        this.taskGitHubService = taskGitHubService;
    }

    @PostMapping("/review-pending")
    public TaskResponse markReviewPending(@PathVariable Long taskId) {
        return taskWorkflowService.markReviewPending(taskId);
    }

    @PostMapping("/close-branch")
    public TaskResponse closeBranch(@PathVariable Long taskId) {
        return taskWorkflowService.closeBranch(taskId);
    }

    @PostMapping("/abandon")
    public TaskResponse abandon(@PathVariable Long taskId) {
        return taskWorkflowService.abandon(taskId);
    }

    @PostMapping("/pull-request")
    public TaskResponse updatePullRequest(
            @PathVariable Long taskId,
            @Valid @RequestBody UpdateTaskPullRequestRequest request
    ) {
        return taskWorkflowService.updatePullRequest(taskId, request);
    }

    @PostMapping("/pull-request/create")
    public TaskResponse createPullRequest(@PathVariable Long taskId) {
        return taskGitHubService.createPullRequest(taskId);
    }

    @PostMapping("/pull-request/sync")
    public TaskResponse syncPullRequest(@PathVariable Long taskId) {
        return taskGitHubService.syncPullRequest(taskId);
    }

    @PostMapping("/review-outcome")
    public TaskResponse updateReviewOutcome(
            @PathVariable Long taskId,
            @Valid @RequestBody UpdateTaskReviewOutcomeRequest request
    ) {
        return taskWorkflowService.updateReviewOutcome(taskId, request);
    }
}
