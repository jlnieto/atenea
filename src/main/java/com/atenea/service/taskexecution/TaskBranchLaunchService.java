package com.atenea.service.taskexecution;

import com.atenea.persistence.task.TaskBranchStatus;
import com.atenea.persistence.task.TaskEntity;
import com.atenea.persistence.task.TaskRepository;
import com.atenea.persistence.task.TaskStatus;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class TaskBranchLaunchService {

    private static final List<TaskBranchStatus> ACTIVE_BRANCH_STATUSES = List.of(
            TaskBranchStatus.ACTIVE,
            TaskBranchStatus.REVIEW_PENDING
    );

    private final TaskRepository taskRepository;
    private final GitRepositoryService gitRepositoryService;

    public TaskBranchLaunchService(TaskRepository taskRepository, GitRepositoryService gitRepositoryService) {
        this.taskRepository = taskRepository;
        this.gitRepositoryService = gitRepositoryService;
    }

    public void prepareLaunch(TaskEntity task, String repoPath) {
        ensureTaskStatusAllowsLaunch(task);
        ensureBranchIdentityPresent(task);
        ensureProjectIsNotLockedByAnotherTask(task);

        GitRepositoryState gitState = gitRepositoryService.inspect(repoPath, task.getBaseBranch(), task.getBranchName());
        ensureWorkingTreeIsClean(gitState, task, repoPath);

        if (task.getBranchName().equals(gitState.currentBranch())) {
            markTaskBranchActive(task);
            return;
        }

        if (!task.getBaseBranch().equals(gitState.currentBranch())) {
            throw new TaskLaunchBlockedException("Repository is on branch '" + gitState.currentBranch()
                    + "' but launch for task '" + task.getId() + "' requires base branch '" + task.getBaseBranch()
                    + "' or task branch '" + task.getBranchName() + "'");
        }

        if (!gitState.baseBranchUpToDate()) {
            throw new TaskLaunchBlockedException("Base branch '" + task.getBaseBranch()
                    + "' is not up to date with its upstream; launch is blocked");
        }

        if (gitState.taskBranchExists()) {
            gitRepositoryService.checkoutBranch(repoPath, task.getBranchName());
        } else {
            gitRepositoryService.createAndCheckoutBranch(repoPath, task.getBaseBranch(), task.getBranchName());
        }

        markTaskBranchActive(task);
    }

    private void ensureBranchIdentityPresent(TaskEntity task) {
        if (isBlank(task.getBaseBranch()) || isBlank(task.getBranchName()) || task.getBranchStatus() == null) {
            throw new TaskLaunchBlockedException("Task '" + task.getId()
                    + "' is missing branch identity and cannot be launched safely");
        }
    }

    private void ensureTaskStatusAllowsLaunch(TaskEntity task) {
        if (task.getStatus() == TaskStatus.DONE || task.getStatus() == TaskStatus.CANCELLED) {
            throw new TaskLaunchBlockedException("Task '" + task.getId()
                    + "' is in terminal status '" + task.getStatus() + "' and cannot be launched");
        }
    }

    private void ensureProjectIsNotLockedByAnotherTask(TaskEntity task) {
        List<TaskEntity> activeTasks = taskRepository.findByProjectIdAndBranchStatusInOrderByCreatedAtAsc(
                task.getProject().getId(),
                ACTIVE_BRANCH_STATUSES);

        activeTasks.stream()
                .filter(activeTask -> !activeTask.getId().equals(task.getId()))
                .findFirst()
                .ifPresent(activeTask -> {
                    throw new TaskLaunchBlockedException("Project '" + task.getProject().getName()
                            + "' is locked by task '" + activeTask.getId()
                            + "' on branch '" + activeTask.getBranchName() + "'");
                });
    }

    private void ensureWorkingTreeIsClean(GitRepositoryState gitState, TaskEntity task, String repoPath) {
        if (!gitState.workingTreeClean()) {
            throw new TaskLaunchBlockedException("Repository '" + repoPath + "' is not clean; cannot launch task '"
                    + task.getId() + "'");
        }
    }

    private void markTaskBranchActive(TaskEntity task) {
        if (task.getBranchStatus() == TaskBranchStatus.ACTIVE) {
            return;
        }

        task.setBranchStatus(TaskBranchStatus.ACTIVE);
        taskRepository.save(task);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
