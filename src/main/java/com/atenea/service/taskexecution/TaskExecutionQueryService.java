package com.atenea.service.taskexecution;

import com.atenea.api.taskexecution.TaskExecutionListItemResponse;
import com.atenea.service.task.TaskOperationalState;
import com.atenea.service.task.TaskOperationalStateService;
import com.atenea.persistence.taskexecution.TaskExecutionEntity;
import com.atenea.persistence.taskexecution.TaskExecutionRepository;
import com.atenea.persistence.taskexecution.TaskExecutionStatus;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TaskExecutionQueryService {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;

    private final TaskExecutionRepository taskExecutionRepository;
    private final TaskOperationalStateService taskOperationalStateService;

    public TaskExecutionQueryService(
            TaskExecutionRepository taskExecutionRepository,
            TaskOperationalStateService taskOperationalStateService
    ) {
        this.taskExecutionRepository = taskExecutionRepository;
        this.taskOperationalStateService = taskOperationalStateService;
    }

    @Transactional(readOnly = true)
    public List<TaskExecutionListItemResponse> getExecutions(TaskExecutionStatus status, Long projectId, Integer limit) {
        int normalizedLimit = normalizeLimit(limit);
        PageRequest pageRequest = PageRequest.of(0, normalizedLimit);

        List<TaskExecutionEntity> executions;
        if (projectId != null && status != null) {
            executions = taskExecutionRepository.findByTaskProjectIdAndStatusOrderByCreatedAtDesc(projectId, status, pageRequest);
        } else if (projectId != null) {
            executions = taskExecutionRepository.findByTaskProjectIdOrderByCreatedAtDesc(projectId, pageRequest);
        } else if (status != null) {
            executions = taskExecutionRepository.findByStatusOrderByCreatedAtDesc(status, pageRequest);
        } else {
            executions = taskExecutionRepository.findAllByOrderByCreatedAtDesc(pageRequest);
        }

        return executions.stream()
                .map(this::toResponse)
                .toList();
    }

    private TaskExecutionListItemResponse toResponse(TaskExecutionEntity execution) {
        TaskOperationalState operationalState = taskOperationalStateService.resolve(execution.getTask());
        return new TaskExecutionListItemResponse(
                execution.getId(),
                execution.getTask().getId(),
                execution.getTask().getTitle(),
                execution.getTask().getProject().getId(),
                execution.getTask().getProject().getName(),
                execution.getTask().getBranchStatus(),
                execution.getTask().getPullRequestStatus(),
                execution.getTask().getReviewOutcome(),
                operationalState.projectBlocked(),
                operationalState.hasReviewableChanges(),
                operationalState.lastExecutionFailed(),
                operationalState.launchReady(),
                operationalState.launchReadinessReason(),
                operationalState.blockingReason(),
                operationalState.nextAction(),
                operationalState.recoveryAction(),
                execution.getStatus(),
                execution.getRunnerType(),
                execution.getTargetRepoPath(),
                execution.getOutputSummary(),
                execution.getErrorSummary(),
                execution.getExternalThreadId(),
                execution.getExternalTurnId(),
                execution.getStartedAt(),
                execution.getFinishedAt(),
                execution.getCreatedAt()
        );
    }

    private int normalizeLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }

        if (limit < 1) {
            throw new IllegalArgumentException("Query parameter 'limit' must be greater than 0");
        }

        return Math.min(limit, MAX_LIMIT);
    }
}
