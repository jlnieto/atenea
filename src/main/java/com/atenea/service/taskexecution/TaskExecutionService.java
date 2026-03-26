package com.atenea.service.taskexecution;

import com.atenea.api.taskexecution.TaskExecutionResponse;
import com.atenea.codexappserver.CodexAppServerClient;
import com.atenea.codexappserver.CodexAppServerExecutionRequest;
import com.atenea.codexappserver.CodexAppServerExecutionResult;
import com.atenea.codexappserver.CodexAppServerExecutionListener;
import com.atenea.persistence.task.TaskEntity;
import com.atenea.persistence.task.TaskRepository;
import com.atenea.persistence.taskexecution.TaskExecutionEntity;
import com.atenea.persistence.taskexecution.TaskExecutionRunnerType;
import com.atenea.persistence.taskexecution.TaskExecutionRepository;
import com.atenea.persistence.taskexecution.TaskExecutionStatus;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeoutException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TaskExecutionService {

    private static final int SUMMARY_MAX_LENGTH = 1000;

    private final TaskRepository taskRepository;
    private final TaskExecutionRepository taskExecutionRepository;
    private final CodexAppServerClient codexAppServerClient;
    private final TaskExecutionProgressService taskExecutionProgressService;
    private final ExecutionPlanningService executionPlanningService;
    private final TaskBranchLaunchService taskBranchLaunchService;
    private final TaskExecutionReadinessService taskExecutionReadinessService;

    public TaskExecutionService(
            TaskRepository taskRepository,
            TaskExecutionRepository taskExecutionRepository,
            CodexAppServerClient codexAppServerClient,
            TaskExecutionProgressService taskExecutionProgressService,
            ExecutionPlanningService executionPlanningService,
            TaskBranchLaunchService taskBranchLaunchService,
            TaskExecutionReadinessService taskExecutionReadinessService
    ) {
        this.taskRepository = taskRepository;
        this.taskExecutionRepository = taskExecutionRepository;
        this.codexAppServerClient = codexAppServerClient;
        this.taskExecutionProgressService = taskExecutionProgressService;
        this.executionPlanningService = executionPlanningService;
        this.taskBranchLaunchService = taskBranchLaunchService;
        this.taskExecutionReadinessService = taskExecutionReadinessService;
    }

    @Transactional(readOnly = true)
    public List<TaskExecutionResponse> getExecutions(Long taskId) {
        ensureTaskExists(taskId);

        return taskExecutionRepository.findByTaskIdOrderByCreatedAtDesc(taskId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public TaskExecutionResponse getExecution(Long taskId, Long executionId) {
        ensureTaskExists(taskId);

        TaskExecutionEntity taskExecution = taskExecutionRepository.findById(executionId)
                .filter(execution -> execution.getTask().getId().equals(taskId))
                .orElseThrow(() -> new TaskExecutionNotFoundException(taskId, executionId));

        return toResponse(taskExecution);
    }

    public TaskExecutionResponse launchTask(Long taskId) {
        return executeTask(taskId, false);
    }

    public TaskExecutionResponse relaunchTask(Long taskId) {
        return executeTask(taskId, true);
    }

    private TaskExecutionResponse executeTask(Long taskId, boolean requirePreviousExecution) {
        TaskEntity task = taskRepository.findWithProjectById(taskId)
                .orElseThrow(() -> new TaskNotFoundException(taskId));

        if (requirePreviousExecution && !taskExecutionRepository.existsByTaskId(taskId)) {
            throw new TaskRelaunchNotAllowedException(taskId, "no previous execution exists");
        }

        TaskExecutionReadiness readiness = taskExecutionReadinessService.assess(task);
        if (!readiness.launchReady()) {
            throw new TaskLaunchBlockedException(
                    "Task requires clarification before launch: " + readiness.reason());
        }

        ExecutionPlan executionPlan = executionPlanningService.createPlan(task);

        Instant now = Instant.now();
        TaskExecutionEntity taskExecution = createStartedCodexExecution(executionPlan, now);
        taskExecutionRepository.saveAndFlush(taskExecution);

        if (executionPlan.planningError() != null) {
            return markExecutionFailed(taskExecution, executionPlan.planningError());
        }

        try {
            taskBranchLaunchService.prepareLaunch(task, executionPlan.targetRepoPath());

            CodexAppServerExecutionResult executionResult = codexAppServerClient.execute(
                    new CodexAppServerExecutionRequest(executionPlan.targetRepoPath(), executionPlan.prompt()),
                    new TaskExecutionListener(taskExecution.getId())
            );

            taskExecution.setExternalThreadId(normalizeNullableText(executionResult.threadId()));
            taskExecution.setExternalTurnId(normalizeNullableText(executionResult.turnId()));

            if (executionResult.status() == CodexAppServerExecutionResult.Status.COMPLETED) {
                taskExecution.setStatus(TaskExecutionStatus.SUCCEEDED);
                taskExecution.setFinishedAt(Instant.now());
                taskExecution.setOutputSummary(summarizeProcessOutput(executionResult.finalAnswer()));
                taskExecution.setErrorSummary(null);
            } else {
                taskExecution.setStatus(TaskExecutionStatus.FAILED);
                taskExecution.setFinishedAt(Instant.now());
                taskExecution.setOutputSummary(null);
                taskExecution.setErrorSummary(summarizeExecutionError(executionResult));
            }

            return toResponse(taskExecutionRepository.save(taskExecution));
        } catch (TaskLaunchBlockedException exception) {
            return markExecutionFailed(taskExecution, exception.getMessage());
        } catch (TimeoutException exception) {
            return markExecutionFailed(taskExecution, exception.getMessage());
        } catch (Exception exception) {
            return markExecutionFailed(taskExecution, "Failed to call Codex App Server: " + exception.getMessage());
        }
    }

    private TaskExecutionEntity createStartedCodexExecution(ExecutionPlan executionPlan, Instant now) {
        TaskExecutionEntity taskExecution = new TaskExecutionEntity();
        taskExecution.setTask(executionPlan.task());
        taskExecution.setStatus(TaskExecutionStatus.RUNNING);
        taskExecution.setRunnerType(executionPlan.runnerType());
        taskExecution.setTargetRepoPath(resolveRecordedTargetRepoPath(executionPlan));
        taskExecution.setStartedAt(now);
        taskExecution.setFinishedAt(null);
        taskExecution.setOutputSummary(null);
        taskExecution.setErrorSummary(null);
        taskExecution.setExternalThreadId(null);
        taskExecution.setExternalTurnId(null);
        taskExecution.setCreatedAt(now);
        return taskExecution;
    }

    private String resolveRecordedTargetRepoPath(ExecutionPlan executionPlan) {
        if (executionPlan.targetRepoPath() != null) {
            return executionPlan.targetRepoPath();
        }

        String projectRepoPath = normalizeNullableText(executionPlan.task().getProject().getRepoPath());
        if (projectRepoPath != null) {
            return projectRepoPath;
        }

        return "[unresolved]";
    }

    private void ensureTaskExists(Long taskId) {
        if (!taskRepository.existsById(taskId)) {
            throw new TaskNotFoundException(taskId);
        }
    }

    private TaskExecutionResponse markExecutionFailed(TaskExecutionEntity taskExecution, String errorSummary) {
        TaskExecutionEntity persistedExecution = taskExecutionRepository.findById(taskExecution.getId())
                .orElse(taskExecution);
        persistedExecution.setStatus(TaskExecutionStatus.FAILED);
        persistedExecution.setFinishedAt(Instant.now());
        persistedExecution.setOutputSummary(null);
        persistedExecution.setErrorSummary(summarizeProcessOutput(errorSummary));
        return toResponse(taskExecutionRepository.save(persistedExecution));
    }

    private TaskExecutionResponse toResponse(TaskExecutionEntity taskExecution) {
        return new TaskExecutionResponse(
                taskExecution.getId(),
                taskExecution.getTask().getId(),
                taskExecution.getStatus(),
                taskExecution.getRunnerType(),
                taskExecution.getTargetRepoPath(),
                taskExecution.getStartedAt(),
                taskExecution.getFinishedAt(),
                taskExecution.getOutputSummary(),
                taskExecution.getErrorSummary(),
                taskExecution.getExternalThreadId(),
                taskExecution.getExternalTurnId(),
                taskExecution.getCreatedAt()
        );
    }

    private static String summarizeExecutionError(CodexAppServerExecutionResult executionResult) {
        String summarizedError = summarizeProcessOutput(executionResult.errorMessage());
        if (summarizedError != null) {
            return summarizedError;
        }
        return "Codex App Server turn failed with status " + executionResult.status();
    }

    private static String summarizeProcessOutput(String value) {
        if (value == null) {
            return null;
        }

        String normalizedValue = value
                .replace('\u0000', ' ')
                .replaceAll("\\s+", " ")
                .trim();

        if (normalizedValue.isEmpty()) {
            return null;
        }

        if (normalizedValue.length() <= SUMMARY_MAX_LENGTH) {
            return normalizedValue;
        }

        return normalizedValue.substring(0, SUMMARY_MAX_LENGTH - 1) + "…";
    }

    private static String normalizeNullableText(String value) {
        if (value == null) {
            return null;
        }

        String normalizedValue = value.trim();
        return normalizedValue.isEmpty() ? null : normalizedValue;
    }

    private final class TaskExecutionListener implements CodexAppServerExecutionListener {

        private final Long executionId;

        private TaskExecutionListener(Long executionId) {
            this.executionId = executionId;
        }

        @Override
        public void onThreadStarted(String threadId) {
            taskExecutionProgressService.persistExternalThreadId(executionId, normalizeNullableText(threadId));
        }

        @Override
        public void onTurnStarted(String threadId, String turnId) {
            taskExecutionProgressService.persistExternalThreadId(executionId, normalizeNullableText(threadId));
            taskExecutionProgressService.persistExternalTurnId(executionId, normalizeNullableText(turnId));
        }
    }
}
