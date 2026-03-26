package com.atenea.service.task;

import com.atenea.api.task.TaskResponse;
import com.atenea.persistence.task.TaskEntity;
import org.springframework.stereotype.Component;

@Component
public class TaskResponseMapper {

    private final TaskOperationalStateService taskOperationalStateService;

    public TaskResponseMapper(TaskOperationalStateService taskOperationalStateService) {
        this.taskOperationalStateService = taskOperationalStateService;
    }

    public TaskResponse toResponse(TaskEntity task) {
        TaskOperationalState operationalState = taskOperationalStateService.resolve(task);
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
                operationalState.projectBlocked(),
                operationalState.hasReviewableChanges(),
                operationalState.lastExecutionFailed(),
                operationalState.launchReady(),
                operationalState.launchReadinessReason(),
                operationalState.blockingReason(),
                operationalState.nextAction(),
                operationalState.recoveryAction(),
                task.getStatus(),
                task.getPriority(),
                task.getCreatedAt(),
                task.getUpdatedAt()
        );
    }
}
