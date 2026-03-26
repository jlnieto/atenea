package com.atenea.service.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.atenea.api.task.TaskResponse;
import com.atenea.github.GitHubClient;
import com.atenea.github.GitHubIntegrationException;
import com.atenea.github.GitHubPullRequest;
import com.atenea.github.GitHubRepositoryRef;
import com.atenea.persistence.project.ProjectEntity;
import com.atenea.persistence.task.TaskBranchStatus;
import com.atenea.persistence.task.TaskEntity;
import com.atenea.persistence.task.TaskPullRequestStatus;
import com.atenea.persistence.task.TaskPriority;
import com.atenea.persistence.task.TaskRepository;
import com.atenea.persistence.task.TaskReviewOutcome;
import com.atenea.persistence.task.TaskStatus;
import com.atenea.service.taskexecution.GitRepositoryService;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TaskGitHubServiceTest {

    @Mock
    private TaskRepository taskRepository;

    @Mock
    private GitRepositoryService gitRepositoryService;

    @Mock
    private GitHubClient gitHubClient;

    @Mock
    private TaskResponseMapper taskResponseMapper;

    @InjectMocks
    private TaskGitHubService taskGitHubService;

    @Test
    void createPullRequestCreatesGitHubPullRequestAndPersistsMetadata() {
        TaskEntity task = buildTask();
        when(taskRepository.findWithProjectById(42L)).thenReturn(Optional.of(task));
        when(gitRepositoryService.hasReviewableChanges("/workspace/repos/internal/atenea", "main", "task/42-fix-launch-flow"))
                .thenReturn(true);
        when(gitRepositoryService.getOriginRemoteUrl("/workspace/repos/internal/atenea"))
                .thenReturn("git@github.com:acme/atenea.git");
        when(gitHubClient.resolveRepository("git@github.com:acme/atenea.git"))
                .thenReturn(new GitHubRepositoryRef("acme", "atenea"));
        when(gitHubClient.createPullRequest(
                new GitHubRepositoryRef("acme", "atenea"),
                "Fix launch flow",
                """
                        Created by Atenea

                        Task id: 42
                        Task title: Fix launch flow

                        desc
                        """.stripTrailing(),
                "task/42-fix-launch-flow",
                "main"
        )).thenReturn(new GitHubPullRequest(42L, "https://github.com/acme/atenea/pull/42", "open", false));
        when(taskRepository.save(any(TaskEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(taskResponseMapper.toResponse(any(TaskEntity.class))).thenAnswer(invocation -> responseFor(invocation.getArgument(0)));

        var response = taskGitHubService.createPullRequest(42L);

        assertEquals("https://github.com/acme/atenea/pull/42", response.pullRequestUrl());
        assertEquals(TaskPullRequestStatus.OPEN, response.pullRequestStatus());
        assertEquals("complete_review", response.nextAction());
    }

    @Test
    void syncPullRequestMarksMergedPullRequest() {
        TaskEntity task = buildTask();
        task.setPullRequestUrl("https://github.com/acme/atenea/pull/42");
        task.setPullRequestStatus(TaskPullRequestStatus.OPEN);
        task.setReviewOutcome(TaskReviewOutcome.APPROVED_FOR_CLOSURE);
        when(taskRepository.findWithProjectById(42L)).thenReturn(Optional.of(task));
        when(gitRepositoryService.getOriginRemoteUrl("/workspace/repos/internal/atenea"))
                .thenReturn("git@github.com:acme/atenea.git");
        when(gitHubClient.resolveRepository("git@github.com:acme/atenea.git"))
                .thenReturn(new GitHubRepositoryRef("acme", "atenea"));
        when(gitHubClient.extractPullRequestNumber("https://github.com/acme/atenea/pull/42")).thenReturn(42L);
        when(gitHubClient.getPullRequest(new GitHubRepositoryRef("acme", "atenea"), 42L))
                .thenReturn(new GitHubPullRequest(42L, "https://github.com/acme/atenea/pull/42", "closed", true));
        when(taskRepository.save(any(TaskEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(taskResponseMapper.toResponse(any(TaskEntity.class))).thenAnswer(invocation -> responseFor(invocation.getArgument(0)));

        var response = taskGitHubService.syncPullRequest(42L);

        assertEquals(TaskPullRequestStatus.MERGED, response.pullRequestStatus());
        assertEquals("close_branch", response.nextAction());
    }

    @Test
    void createPullRequestPropagatesClearGitHubCredentialError() {
        TaskEntity task = buildTask();
        when(taskRepository.findWithProjectById(42L)).thenReturn(Optional.of(task));
        when(gitRepositoryService.hasReviewableChanges("/workspace/repos/internal/atenea", "main", "task/42-fix-launch-flow"))
                .thenReturn(true);
        when(gitRepositoryService.getOriginRemoteUrl("/workspace/repos/internal/atenea"))
                .thenReturn("git@github.com:acme/atenea.git");
        when(gitHubClient.resolveRepository("git@github.com:acme/atenea.git"))
                .thenReturn(new GitHubRepositoryRef("acme", "atenea"));
        when(gitHubClient.createPullRequest(any(), any(), any(), any(), any()))
                .thenThrow(new GitHubIntegrationException("GitHub token is invalid or expired: Bad credentials"));

        GitHubIntegrationException exception = assertThrows(
                GitHubIntegrationException.class,
                () -> taskGitHubService.createPullRequest(42L));

        assertEquals("GitHub token is invalid or expired: Bad credentials", exception.getMessage());
    }

    private static TaskResponse responseFor(TaskEntity task) {
        return new TaskResponse(
                task.getId(),
                task.getProject().getId(),
                task.getTitle(),
                task.getDescription(),
                task.getBaseBranch(),
                task.getBranchName(),
                task.getBranchStatus(),
                task.getPullRequestUrl(),
                task.getPullRequestStatus(),
                task.getReviewOutcome(),
                task.getReviewNotes(),
                true,
                true,
                false,
                true,
                "ready",
                "review_pending",
                task.getReviewOutcome() == TaskReviewOutcome.APPROVED_FOR_CLOSURE
                        && task.getPullRequestStatus() == TaskPullRequestStatus.MERGED
                        ? "close_branch"
                        : "complete_review",
                "none",
                task.getStatus(),
                task.getPriority(),
                task.getCreatedAt(),
                task.getUpdatedAt()
        );
    }

    private static TaskEntity buildTask() {
        ProjectEntity project = new ProjectEntity();
        project.setId(7L);
        project.setName("Atenea");
        project.setRepoPath("/workspace/repos/internal/atenea");
        project.setCreatedAt(Instant.now());
        project.setUpdatedAt(Instant.now());

        TaskEntity task = new TaskEntity();
        task.setId(42L);
        task.setProject(project);
        task.setTitle("Fix launch flow");
        task.setDescription("desc");
        task.setBaseBranch("main");
        task.setBranchName("task/42-fix-launch-flow");
        task.setBranchStatus(TaskBranchStatus.REVIEW_PENDING);
        task.setPullRequestUrl(null);
        task.setPullRequestStatus(TaskPullRequestStatus.NOT_CREATED);
        task.setReviewOutcome(TaskReviewOutcome.PENDING);
        task.setReviewNotes(null);
        task.setStatus(TaskStatus.DONE);
        task.setPriority(TaskPriority.NORMAL);
        task.setCreatedAt(Instant.now());
        task.setUpdatedAt(Instant.now());
        return task;
    }
}
