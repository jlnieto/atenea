package com.atenea.service.task;

import com.atenea.api.task.TaskResponse;
import com.atenea.github.GitHubClient;
import com.atenea.github.GitHubIntegrationException;
import com.atenea.github.GitHubPullRequest;
import com.atenea.github.GitHubRepositoryRef;
import com.atenea.persistence.task.TaskBranchStatus;
import com.atenea.persistence.task.TaskEntity;
import com.atenea.persistence.task.TaskPullRequestStatus;
import com.atenea.persistence.task.TaskRepository;
import com.atenea.service.taskexecution.GitRepositoryService;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TaskGitHubService {

    private final TaskRepository taskRepository;
    private final GitRepositoryService gitRepositoryService;
    private final GitHubClient gitHubClient;
    private final TaskResponseMapper taskResponseMapper;

    public TaskGitHubService(
            TaskRepository taskRepository,
            GitRepositoryService gitRepositoryService,
            GitHubClient gitHubClient,
            TaskResponseMapper taskResponseMapper
    ) {
        this.taskRepository = taskRepository;
        this.gitRepositoryService = gitRepositoryService;
        this.gitHubClient = gitHubClient;
        this.taskResponseMapper = taskResponseMapper;
    }

    @Transactional
    public TaskResponse createPullRequest(Long taskId) {
        TaskEntity task = findTask(taskId);
        ensureReviewPending(taskId, task);
        if (task.getPullRequestStatus() != TaskPullRequestStatus.NOT_CREATED) {
            throw new TaskWorkflowTransitionNotAllowedException(taskId,
                    "pull request creation requires status NOT_CREATED");
        }
        if (!gitRepositoryService.hasReviewableChanges(
                task.getProject().getRepoPath(),
                task.getBaseBranch(),
                task.getBranchName())) {
            throw new TaskWorkflowTransitionNotAllowedException(taskId,
                    "pull request creation requires reviewable changes in the task branch");
        }

        GitHubRepositoryRef repository = resolveRepository(task);
        GitHubPullRequest pullRequest = gitHubClient.createPullRequest(
                repository,
                task.getTitle(),
                buildPullRequestBody(task),
                task.getBranchName(),
                task.getBaseBranch()
        );

        task.setPullRequestUrl(pullRequest.htmlUrl());
        task.setPullRequestStatus(mapPullRequestStatus(pullRequest));
        task.setUpdatedAt(Instant.now());
        return taskResponseMapper.toResponse(taskRepository.save(task));
    }

    @Transactional
    public TaskResponse syncPullRequest(Long taskId) {
        TaskEntity task = findTask(taskId);
        if (task.getPullRequestUrl() == null || task.getPullRequestUrl().isBlank()) {
            throw new TaskWorkflowTransitionNotAllowedException(taskId,
                    "pull request synchronization requires a pullRequestUrl");
        }

        GitHubRepositoryRef repository = resolveRepository(task);
        long pullRequestNumber = gitHubClient.extractPullRequestNumber(task.getPullRequestUrl());
        GitHubPullRequest pullRequest = gitHubClient.getPullRequest(repository, pullRequestNumber);

        task.setPullRequestUrl(pullRequest.htmlUrl());
        task.setPullRequestStatus(mapPullRequestStatus(pullRequest));
        task.setUpdatedAt(Instant.now());
        return taskResponseMapper.toResponse(taskRepository.save(task));
    }

    private TaskEntity findTask(Long taskId) {
        return taskRepository.findWithProjectById(taskId)
                .orElseThrow(() -> new com.atenea.service.taskexecution.TaskNotFoundException(taskId));
    }

    private void ensureReviewPending(Long taskId, TaskEntity task) {
        if (task.getBranchStatus() != TaskBranchStatus.REVIEW_PENDING) {
            throw new TaskWorkflowTransitionNotAllowedException(taskId,
                    "pull request creation requires branch status REVIEW_PENDING");
        }
    }

    private GitHubRepositoryRef resolveRepository(TaskEntity task) {
        String repoPath = task.getProject().getRepoPath();
        try {
            String remoteUrl = gitRepositoryService.getOriginRemoteUrl(repoPath);
            return gitHubClient.resolveRepository(remoteUrl);
        } catch (GitHubIntegrationException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new GitHubIntegrationException("Failed to resolve GitHub repository for task '" + task.getId()
                    + "': " + exception.getMessage(), exception);
        }
    }

    private static String buildPullRequestBody(TaskEntity task) {
        StringBuilder body = new StringBuilder();
        body.append("Created by Atenea").append(System.lineSeparator())
                .append(System.lineSeparator())
                .append("Task id: ").append(task.getId()).append(System.lineSeparator())
                .append("Task title: ").append(task.getTitle());
        if (task.getDescription() != null) {
            body.append(System.lineSeparator())
                    .append(System.lineSeparator())
                    .append(task.getDescription());
        }
        return body.toString();
    }

    private static TaskPullRequestStatus mapPullRequestStatus(GitHubPullRequest pullRequest) {
        if (pullRequest.merged()) {
            return TaskPullRequestStatus.MERGED;
        }
        if ("open".equalsIgnoreCase(pullRequest.state())) {
            return TaskPullRequestStatus.OPEN;
        }
        return TaskPullRequestStatus.DECLINED;
    }

}
