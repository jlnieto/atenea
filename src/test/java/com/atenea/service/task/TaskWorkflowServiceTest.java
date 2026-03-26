package com.atenea.service.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.atenea.api.task.TaskResponse;
import com.atenea.persistence.project.ProjectEntity;
import com.atenea.persistence.task.TaskBranchStatus;
import com.atenea.persistence.task.TaskEntity;
import com.atenea.persistence.task.TaskPullRequestStatus;
import com.atenea.persistence.task.TaskPriority;
import com.atenea.persistence.task.TaskReviewOutcome;
import com.atenea.persistence.task.TaskRepository;
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
class TaskWorkflowServiceTest {

    @Mock
    private TaskRepository taskRepository;

    @Mock
    private TaskResponseMapper taskResponseMapper;

    @Mock
    private GitRepositoryService gitRepositoryService;

    @InjectMocks
    private TaskWorkflowService taskWorkflowService;

    @Test
    void markReviewPendingMovesTaskToDoneAndReviewPending() {
        TaskEntity task = buildTask(TaskStatus.IN_PROGRESS, TaskBranchStatus.ACTIVE);
        when(taskRepository.findWithProjectById(42L)).thenReturn(Optional.of(task));
        when(taskRepository.save(any(TaskEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(gitRepositoryService.hasReviewableChanges("/workspace/repos/internal/atenea", "main", "task/42-fix-launch-flow"))
                .thenReturn(true);
        when(taskResponseMapper.toResponse(any(TaskEntity.class))).thenAnswer(invocation -> responseFor(invocation.getArgument(0)));

        TaskResponse response = taskWorkflowService.markReviewPending(42L);

        assertEquals(TaskStatus.DONE, response.status());
        assertEquals(TaskBranchStatus.REVIEW_PENDING, response.branchStatus());
        assertEquals(TaskReviewOutcome.PENDING, response.reviewOutcome());
    }

    @Test
    void markReviewPendingRejectsNonActiveBranch() {
        TaskEntity task = buildTask(TaskStatus.IN_PROGRESS, TaskBranchStatus.PLANNED);
        when(taskRepository.findWithProjectById(42L)).thenReturn(Optional.of(task));

        TaskWorkflowTransitionNotAllowedException exception = assertThrows(
                TaskWorkflowTransitionNotAllowedException.class,
                () -> taskWorkflowService.markReviewPending(42L));

        assertEquals("Task '42' cannot change workflow state: review pending requires branch status ACTIVE",
                exception.getMessage());
    }

    @Test
    void markReviewPendingRejectsTaskWithoutReviewableChanges() {
        TaskEntity task = buildTask(TaskStatus.IN_PROGRESS, TaskBranchStatus.ACTIVE);
        when(taskRepository.findWithProjectById(42L)).thenReturn(Optional.of(task));
        when(gitRepositoryService.hasReviewableChanges("/workspace/repos/internal/atenea", "main", "task/42-fix-launch-flow"))
                .thenReturn(false);

        TaskWorkflowTransitionNotAllowedException exception = assertThrows(
                TaskWorkflowTransitionNotAllowedException.class,
                () -> taskWorkflowService.markReviewPending(42L));

        assertEquals("Task '42' cannot change workflow state: review pending requires reviewable changes in the task branch",
                exception.getMessage());
    }

    @Test
    void closeBranchMovesReviewPendingDoneTaskToClosed() {
        TaskEntity task = buildTask(TaskStatus.DONE, TaskBranchStatus.REVIEW_PENDING);
        task.setReviewOutcome(TaskReviewOutcome.APPROVED_FOR_CLOSURE);
        task.setPullRequestStatus(TaskPullRequestStatus.MERGED);
        task.setPullRequestUrl("https://git.example/pr/42");
        when(taskRepository.findWithProjectById(42L)).thenReturn(Optional.of(task));
        when(taskRepository.save(any(TaskEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(taskResponseMapper.toResponse(any(TaskEntity.class))).thenAnswer(invocation -> responseFor(invocation.getArgument(0)));

        TaskResponse response = taskWorkflowService.closeBranch(42L);

        assertEquals(TaskBranchStatus.CLOSED, response.branchStatus());
        assertEquals(TaskStatus.DONE, response.status());
    }

    @Test
    void closeBranchRejectsNonMergedPullRequest() {
        TaskEntity task = buildTask(TaskStatus.DONE, TaskBranchStatus.REVIEW_PENDING);
        task.setPullRequestUrl("https://git.example/pr/42");
        task.setPullRequestStatus(TaskPullRequestStatus.OPEN);
        task.setReviewOutcome(TaskReviewOutcome.APPROVED_FOR_CLOSURE);
        when(taskRepository.findWithProjectById(42L)).thenReturn(Optional.of(task));

        TaskWorkflowTransitionNotAllowedException exception = assertThrows(
                TaskWorkflowTransitionNotAllowedException.class,
                () -> taskWorkflowService.closeBranch(42L));

        assertEquals(
                "Task '42' cannot change workflow state: close branch requires pull request status MERGED",
                exception.getMessage());
    }

    @Test
    void updatePullRequestPersistsMetadataForReviewPendingTask() {
        TaskEntity task = buildTask(TaskStatus.DONE, TaskBranchStatus.REVIEW_PENDING);
        when(taskRepository.findWithProjectById(42L)).thenReturn(Optional.of(task));
        when(taskRepository.save(any(TaskEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(taskResponseMapper.toResponse(any(TaskEntity.class))).thenAnswer(invocation -> responseFor(invocation.getArgument(0)));

        TaskResponse response = taskWorkflowService.updatePullRequest(
                42L,
                new com.atenea.api.task.UpdateTaskPullRequestRequest(
                        "https://git.example/pr/42",
                        TaskPullRequestStatus.APPROVED));

        assertEquals("https://git.example/pr/42", response.pullRequestUrl());
        assertEquals(TaskPullRequestStatus.APPROVED, response.pullRequestStatus());
    }

    @Test
    void updateReviewOutcomePersistsExplicitClosureApproval() {
        TaskEntity task = buildTask(TaskStatus.DONE, TaskBranchStatus.REVIEW_PENDING);
        when(taskRepository.findWithProjectById(42L)).thenReturn(Optional.of(task));
        when(taskRepository.save(any(TaskEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(taskResponseMapper.toResponse(any(TaskEntity.class))).thenAnswer(invocation -> responseFor(invocation.getArgument(0)));

        TaskResponse response = taskWorkflowService.updateReviewOutcome(
                42L,
                new com.atenea.api.task.UpdateTaskReviewOutcomeRequest(
                        TaskReviewOutcome.APPROVED_FOR_CLOSURE,
                        "Operator approved closure."));

        assertEquals(TaskReviewOutcome.APPROVED_FOR_CLOSURE, response.reviewOutcome());
        assertEquals("Operator approved closure.", response.reviewNotes());
    }

    @Test
    void updateReviewOutcomeRejectsMissingNotesForChangesRequested() {
        TaskEntity task = buildTask(TaskStatus.DONE, TaskBranchStatus.REVIEW_PENDING);
        when(taskRepository.findWithProjectById(42L)).thenReturn(Optional.of(task));

        TaskWorkflowTransitionNotAllowedException exception = assertThrows(
                TaskWorkflowTransitionNotAllowedException.class,
                () -> taskWorkflowService.updateReviewOutcome(
                        42L,
                        new com.atenea.api.task.UpdateTaskReviewOutcomeRequest(
                                TaskReviewOutcome.CHANGES_REQUESTED,
                                "   ")));

        assertEquals(
                "Task '42' cannot change workflow state: review notes are required for outcome 'CHANGES_REQUESTED'",
                exception.getMessage());
    }

    @Test
    void updatePullRequestRejectsMissingUrlForOpenStatus() {
        TaskEntity task = buildTask(TaskStatus.DONE, TaskBranchStatus.REVIEW_PENDING);
        when(taskRepository.findWithProjectById(42L)).thenReturn(Optional.of(task));

        TaskWorkflowTransitionNotAllowedException exception = assertThrows(
                TaskWorkflowTransitionNotAllowedException.class,
                () -> taskWorkflowService.updatePullRequest(
                        42L,
                        new com.atenea.api.task.UpdateTaskPullRequestRequest("   ", TaskPullRequestStatus.OPEN)));

        assertEquals(
                "Task '42' cannot change workflow state: pull request url is required for status 'OPEN'",
                exception.getMessage());
    }

    @Test
    void closeBranchAllowsCancelledTaskToReleaseProject() {
        TaskEntity task = buildTask(TaskStatus.CANCELLED, TaskBranchStatus.ACTIVE);
        task.setReviewOutcome(TaskReviewOutcome.CLOSED_WITHOUT_REVIEW);
        when(taskRepository.findWithProjectById(42L)).thenReturn(Optional.of(task));
        when(taskRepository.save(any(TaskEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(taskResponseMapper.toResponse(any(TaskEntity.class))).thenAnswer(invocation -> responseFor(invocation.getArgument(0)));

        TaskResponse response = taskWorkflowService.closeBranch(42L);

        assertEquals(TaskBranchStatus.CLOSED, response.branchStatus());
    }

    @Test
    void closeBranchRejectsCancelledTaskWithoutExplicitClosureOutcome() {
        TaskEntity task = buildTask(TaskStatus.CANCELLED, TaskBranchStatus.ACTIVE);
        task.setReviewOutcome(TaskReviewOutcome.PENDING);
        when(taskRepository.findWithProjectById(42L)).thenReturn(Optional.of(task));

        TaskWorkflowTransitionNotAllowedException exception = assertThrows(
                TaskWorkflowTransitionNotAllowedException.class,
                () -> taskWorkflowService.closeBranch(42L));

        assertEquals(
                "Task '42' cannot change workflow state: cancelled task closure requires review outcome CLOSED_WITHOUT_REVIEW",
                exception.getMessage());
    }

    @Test
    void closeBranchRejectsInvalidTransition() {
        TaskEntity task = buildTask(TaskStatus.IN_PROGRESS, TaskBranchStatus.ACTIVE);
        when(taskRepository.findWithProjectById(42L)).thenReturn(Optional.of(task));

        TaskWorkflowTransitionNotAllowedException exception = assertThrows(
                TaskWorkflowTransitionNotAllowedException.class,
                () -> taskWorkflowService.closeBranch(42L));

        assertEquals(
                "Task '42' cannot change workflow state: close branch requires REVIEW_PENDING + DONE, or a CANCELLED task",
                exception.getMessage());
    }

    @Test
    void abandonClosesEmptyTaskBranchAndCancelsTask() {
        TaskEntity task = buildTask(TaskStatus.IN_PROGRESS, TaskBranchStatus.ACTIVE);
        when(taskRepository.findWithProjectById(42L)).thenReturn(Optional.of(task));
        when(gitRepositoryService.hasReviewableChanges("/workspace/repos/internal/atenea", "main", "task/42-fix-launch-flow"))
                .thenReturn(false);
        when(gitRepositoryService.inspect("/workspace/repos/internal/atenea", "main", "task/42-fix-launch-flow"))
                .thenReturn(new com.atenea.service.taskexecution.GitRepositoryState(
                        "task/42-fix-launch-flow", true, true, true));
        when(taskRepository.save(any(TaskEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(taskResponseMapper.toResponse(any(TaskEntity.class))).thenAnswer(invocation -> responseFor(invocation.getArgument(0)));

        TaskResponse response = taskWorkflowService.abandon(42L);

        assertEquals(TaskStatus.CANCELLED, response.status());
        assertEquals(TaskBranchStatus.CLOSED, response.branchStatus());
        assertEquals(TaskReviewOutcome.CLOSED_WITHOUT_REVIEW, response.reviewOutcome());
        verify(gitRepositoryService).checkoutBranch("/workspace/repos/internal/atenea", "main");
    }

    @Test
    void abandonRejectsTaskWithReviewableChanges() {
        TaskEntity task = buildTask(TaskStatus.IN_PROGRESS, TaskBranchStatus.ACTIVE);
        when(taskRepository.findWithProjectById(42L)).thenReturn(Optional.of(task));
        when(gitRepositoryService.hasReviewableChanges("/workspace/repos/internal/atenea", "main", "task/42-fix-launch-flow"))
                .thenReturn(true);

        TaskWorkflowTransitionNotAllowedException exception = assertThrows(
                TaskWorkflowTransitionNotAllowedException.class,
                () -> taskWorkflowService.abandon(42L));

        assertEquals("Task '42' cannot change workflow state: abandon requires a task branch without reviewable changes",
                exception.getMessage());
        verify(gitRepositoryService, never()).inspect("/workspace/repos/internal/atenea", "main", "task/42-fix-launch-flow");
    }

    @Test
    void abandonRejectsTaskWithOpenPullRequest() {
        TaskEntity task = buildTask(TaskStatus.DONE, TaskBranchStatus.REVIEW_PENDING);
        task.setPullRequestStatus(TaskPullRequestStatus.OPEN);
        task.setPullRequestUrl("https://git.example/pr/42");
        when(taskRepository.findWithProjectById(42L)).thenReturn(Optional.of(task));

        TaskWorkflowTransitionNotAllowedException exception = assertThrows(
                TaskWorkflowTransitionNotAllowedException.class,
                () -> taskWorkflowService.abandon(42L));

        assertEquals("Task '42' cannot change workflow state: abandon is not allowed while the pull request is still open",
                exception.getMessage());
    }

    private static TaskEntity buildTask(TaskStatus status, TaskBranchStatus branchStatus) {
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
        task.setBranchStatus(branchStatus);
        task.setPullRequestUrl(null);
        task.setPullRequestStatus(TaskPullRequestStatus.NOT_CREATED);
        task.setReviewOutcome(TaskReviewOutcome.PENDING);
        task.setReviewNotes(null);
        task.setStatus(status);
        task.setPriority(TaskPriority.NORMAL);
        task.setCreatedAt(Instant.now());
        task.setUpdatedAt(Instant.now());
        return task;
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
                task.getBranchStatus() != TaskBranchStatus.CLOSED,
                false,
                false,
                true,
                "ready",
                task.getBranchStatus() == TaskBranchStatus.CLOSED ? "none" : "active_branch",
                task.getBranchStatus() == TaskBranchStatus.CLOSED ? "none" : "review",
                "none",
                task.getStatus(),
                task.getPriority(),
                task.getCreatedAt(),
                task.getUpdatedAt()
        );
    }
}
