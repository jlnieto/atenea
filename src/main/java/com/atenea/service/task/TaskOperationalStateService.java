package com.atenea.service.task;

import com.atenea.persistence.task.TaskBranchStatus;
import com.atenea.persistence.task.TaskEntity;
import com.atenea.persistence.taskexecution.TaskExecutionRepository;
import com.atenea.persistence.taskexecution.TaskExecutionStatus;
import com.atenea.service.taskexecution.GitRepositoryService;
import com.atenea.service.taskexecution.TaskExecutionReadiness;
import com.atenea.service.taskexecution.TaskExecutionReadinessService;
import org.springframework.stereotype.Service;

@Service
public class TaskOperationalStateService {

    private final GitRepositoryService gitRepositoryService;
    private final TaskExecutionRepository taskExecutionRepository;
    private final TaskExecutionReadinessService taskExecutionReadinessService;

    public TaskOperationalStateService(
            GitRepositoryService gitRepositoryService,
            TaskExecutionRepository taskExecutionRepository,
            TaskExecutionReadinessService taskExecutionReadinessService
    ) {
        this.gitRepositoryService = gitRepositoryService;
        this.taskExecutionRepository = taskExecutionRepository;
        this.taskExecutionReadinessService = taskExecutionReadinessService;
    }

    public TaskOperationalState resolve(TaskEntity task) {
        boolean hasReviewableChanges = resolveHasReviewableChanges(task);
        boolean lastExecutionFailed = taskExecutionRepository.findFirstByTaskIdOrderByCreatedAtDesc(task.getId())
                .map(execution -> execution.getStatus() == TaskExecutionStatus.FAILED)
                .orElse(false);
        boolean projectBlocked = TaskOperationalStateResolver.isProjectBlocked(task);
        TaskExecutionReadiness readiness = taskExecutionReadinessService.assess(task);

        return new TaskOperationalState(
                projectBlocked,
                hasReviewableChanges,
                lastExecutionFailed,
                readiness.launchReady(),
                readiness.reason(),
                TaskOperationalStateResolver.blockingReason(task, projectBlocked, hasReviewableChanges, lastExecutionFailed),
                TaskOperationalStateResolver.nextAction(task, hasReviewableChanges, lastExecutionFailed, readiness.launchReady()),
                TaskOperationalStateResolver.recoveryAction(task, hasReviewableChanges, lastExecutionFailed, readiness.launchReady())
        );
    }

    private boolean resolveHasReviewableChanges(TaskEntity task) {
        try {
            return gitRepositoryService.hasReviewableChanges(
                    task.getProject().getRepoPath(),
                    task.getBaseBranch(),
                    task.getBranchName()
            );
        } catch (Exception exception) {
            return false;
        }
    }
}
