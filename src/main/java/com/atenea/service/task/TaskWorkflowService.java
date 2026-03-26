package com.atenea.service.task;

import com.atenea.api.task.UpdateTaskPullRequestRequest;
import com.atenea.api.task.UpdateTaskReviewOutcomeRequest;
import com.atenea.api.task.TaskResponse;
import com.atenea.persistence.task.TaskBranchStatus;
import com.atenea.persistence.task.TaskEntity;
import com.atenea.persistence.task.TaskPullRequestStatus;
import com.atenea.persistence.task.TaskReviewOutcome;
import com.atenea.persistence.task.TaskRepository;
import com.atenea.persistence.task.TaskStatus;
import com.atenea.service.taskexecution.GitRepositoryService;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TaskWorkflowService {

    private final TaskRepository taskRepository;
    private final TaskResponseMapper taskResponseMapper;
    private final GitRepositoryService gitRepositoryService;

    public TaskWorkflowService(
            TaskRepository taskRepository,
            TaskResponseMapper taskResponseMapper,
            GitRepositoryService gitRepositoryService
    ) {
        this.taskRepository = taskRepository;
        this.taskResponseMapper = taskResponseMapper;
        this.gitRepositoryService = gitRepositoryService;
    }

    @Transactional
    public TaskResponse markReviewPending(Long taskId) {
        TaskEntity task = taskRepository.findWithProjectById(taskId)
                .orElseThrow(() -> new com.atenea.service.taskexecution.TaskNotFoundException(taskId));

        if (task.getBranchStatus() != TaskBranchStatus.ACTIVE) {
            throw new TaskWorkflowTransitionNotAllowedException(taskId,
                    "review pending requires branch status ACTIVE");
        }

        if (task.getStatus() == TaskStatus.CANCELLED) {
            throw new TaskWorkflowTransitionNotAllowedException(taskId,
                    "cancelled tasks cannot move to review pending");
        }

        if (!gitRepositoryService.hasReviewableChanges(
                task.getProject().getRepoPath(),
                task.getBaseBranch(),
                task.getBranchName())) {
            throw new TaskWorkflowTransitionNotAllowedException(taskId,
                    "review pending requires reviewable changes in the task branch");
        }

        task.setStatus(TaskStatus.DONE);
        task.setBranchStatus(TaskBranchStatus.REVIEW_PENDING);
        task.setReviewOutcome(TaskReviewOutcome.PENDING);
        task.setUpdatedAt(Instant.now());
        return taskResponseMapper.toResponse(taskRepository.save(task));
    }

    @Transactional
    public TaskResponse closeBranch(Long taskId) {
        TaskEntity task = taskRepository.findWithProjectById(taskId)
                .orElseThrow(() -> new com.atenea.service.taskexecution.TaskNotFoundException(taskId));

        boolean reviewCompleted = task.getBranchStatus() == TaskBranchStatus.REVIEW_PENDING
                && task.getStatus() == TaskStatus.DONE;
        boolean cancelledTask = task.getStatus() == TaskStatus.CANCELLED
                && task.getBranchStatus() != TaskBranchStatus.CLOSED;

        if (!reviewCompleted && !cancelledTask) {
            throw new TaskWorkflowTransitionNotAllowedException(taskId,
                    "close branch requires REVIEW_PENDING + DONE, or a CANCELLED task");
        }

        if (!cancelledTask && task.getReviewOutcome() != TaskReviewOutcome.APPROVED_FOR_CLOSURE) {
            throw new TaskWorkflowTransitionNotAllowedException(taskId,
                    "close branch requires review outcome APPROVED_FOR_CLOSURE");
        }

        if (!cancelledTask && task.getPullRequestStatus() != TaskPullRequestStatus.MERGED) {
            throw new TaskWorkflowTransitionNotAllowedException(taskId,
                    "close branch requires pull request status MERGED");
        }

        if (cancelledTask && task.getReviewOutcome() != TaskReviewOutcome.CLOSED_WITHOUT_REVIEW) {
            throw new TaskWorkflowTransitionNotAllowedException(taskId,
                    "cancelled task closure requires review outcome CLOSED_WITHOUT_REVIEW");
        }

        task.setBranchStatus(TaskBranchStatus.CLOSED);
        task.setUpdatedAt(Instant.now());
        return taskResponseMapper.toResponse(taskRepository.save(task));
    }

    @Transactional
    public TaskResponse abandon(Long taskId) {
        TaskEntity task = taskRepository.findWithProjectById(taskId)
                .orElseThrow(() -> new com.atenea.service.taskexecution.TaskNotFoundException(taskId));

        if (task.getBranchStatus() == TaskBranchStatus.CLOSED) {
            throw new TaskWorkflowTransitionNotAllowedException(taskId,
                    "abandon requires an open task branch");
        }

        if (task.getPullRequestStatus() == TaskPullRequestStatus.OPEN
                || task.getPullRequestStatus() == TaskPullRequestStatus.APPROVED) {
            throw new TaskWorkflowTransitionNotAllowedException(taskId,
                    "abandon is not allowed while the pull request is still open");
        }

        if (gitRepositoryService.hasReviewableChanges(
                task.getProject().getRepoPath(),
                task.getBaseBranch(),
                task.getBranchName())) {
            throw new TaskWorkflowTransitionNotAllowedException(taskId,
                    "abandon requires a task branch without reviewable changes");
        }

        var gitState = gitRepositoryService.inspect(task.getProject().getRepoPath(), task.getBaseBranch(), task.getBranchName());
        if (!gitState.workingTreeClean()) {
            throw new TaskWorkflowTransitionNotAllowedException(taskId,
                    "abandon requires a clean working tree");
        }
        if (task.getBranchName().equals(gitState.currentBranch())) {
            gitRepositoryService.checkoutBranch(task.getProject().getRepoPath(), task.getBaseBranch());
        }

        task.setStatus(TaskStatus.CANCELLED);
        task.setBranchStatus(TaskBranchStatus.CLOSED);
        task.setReviewOutcome(TaskReviewOutcome.CLOSED_WITHOUT_REVIEW);
        task.setReviewNotes(normalizeNullableText(task.getReviewNotes()));
        task.setUpdatedAt(Instant.now());
        return taskResponseMapper.toResponse(taskRepository.save(task));
    }

    @Transactional
    public TaskResponse updatePullRequest(Long taskId, UpdateTaskPullRequestRequest request) {
        TaskEntity task = taskRepository.findWithProjectById(taskId)
                .orElseThrow(() -> new com.atenea.service.taskexecution.TaskNotFoundException(taskId));

        if (task.getBranchStatus() != TaskBranchStatus.REVIEW_PENDING && task.getBranchStatus() != TaskBranchStatus.CLOSED) {
            throw new TaskWorkflowTransitionNotAllowedException(taskId,
                    "pull request metadata requires branch status REVIEW_PENDING or CLOSED");
        }

        String normalizedPullRequestUrl = normalizeNullableText(request.pullRequestUrl());
        if (requiresPullRequestUrl(request.pullRequestStatus()) && normalizedPullRequestUrl == null) {
            throw new TaskWorkflowTransitionNotAllowedException(taskId,
                    "pull request url is required for status '" + request.pullRequestStatus() + "'");
        }

        task.setPullRequestUrl(normalizedPullRequestUrl);
        task.setPullRequestStatus(request.pullRequestStatus());
        task.setUpdatedAt(Instant.now());
        return taskResponseMapper.toResponse(taskRepository.save(task));
    }

    @Transactional
    public TaskResponse updateReviewOutcome(Long taskId, UpdateTaskReviewOutcomeRequest request) {
        TaskEntity task = taskRepository.findWithProjectById(taskId)
                .orElseThrow(() -> new com.atenea.service.taskexecution.TaskNotFoundException(taskId));

        if (task.getBranchStatus() != TaskBranchStatus.REVIEW_PENDING && task.getBranchStatus() != TaskBranchStatus.CLOSED) {
            throw new TaskWorkflowTransitionNotAllowedException(taskId,
                    "review outcome requires branch status REVIEW_PENDING or CLOSED");
        }

        String normalizedReviewNotes = normalizeNullableText(request.reviewNotes());
        if (requiresReviewNotes(request.reviewOutcome()) && normalizedReviewNotes == null) {
            throw new TaskWorkflowTransitionNotAllowedException(taskId,
                    "review notes are required for outcome '" + request.reviewOutcome() + "'");
        }

        task.setReviewOutcome(request.reviewOutcome());
        task.setReviewNotes(normalizedReviewNotes);
        task.setUpdatedAt(Instant.now());
        return taskResponseMapper.toResponse(taskRepository.save(task));
    }

    private static boolean requiresPullRequestUrl(TaskPullRequestStatus status) {
        return status == TaskPullRequestStatus.OPEN
                || status == TaskPullRequestStatus.APPROVED
                || status == TaskPullRequestStatus.MERGED;
    }

    private static boolean requiresReviewNotes(TaskReviewOutcome reviewOutcome) {
        return reviewOutcome == TaskReviewOutcome.CHANGES_REQUESTED
                || reviewOutcome == TaskReviewOutcome.REJECTED;
    }

    private static String normalizeNullableText(String value) {
        if (value == null) {
            return null;
        }

        String normalizedValue = value.trim();
        return normalizedValue.isEmpty() ? null : normalizedValue;
    }
}
