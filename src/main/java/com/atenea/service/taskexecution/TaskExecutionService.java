package com.atenea.service.taskexecution;

import com.atenea.api.taskexecution.CreateTaskExecutionRequest;
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
    private static final String STANDARD_SYSTEM_INSTRUCTIONS = """
            Work carefully and keep the change set small.
            Do not expand scope.
            Operate only within the current repository path.
            Keep the implementation minimal, production-clean, and easy to extend.
            """;

    private final TaskRepository taskRepository;
    private final TaskExecutionRepository taskExecutionRepository;
    private final CodexAppServerClient codexAppServerClient;
    private final TaskExecutionProgressService taskExecutionProgressService;

    public TaskExecutionService(
            TaskRepository taskRepository,
            TaskExecutionRepository taskExecutionRepository,
            CodexAppServerClient codexAppServerClient,
            TaskExecutionProgressService taskExecutionProgressService
    ) {
        this.taskRepository = taskRepository;
        this.taskExecutionRepository = taskExecutionRepository;
        this.codexAppServerClient = codexAppServerClient;
        this.taskExecutionProgressService = taskExecutionProgressService;
    }

    @Transactional(readOnly = true)
    public List<TaskExecutionResponse> getExecutions(Long taskId) {
        ensureTaskExists(taskId);

        return taskExecutionRepository.findByTaskIdOrderByCreatedAtDesc(taskId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public TaskExecutionResponse createExecution(Long taskId, CreateTaskExecutionRequest request) {
        TaskEntity task = taskRepository.findById(taskId)
                .orElseThrow(() -> new TaskNotFoundException(taskId));

        Instant now = Instant.now();
        String outputSummary = normalizeNullableText(request.outputSummary());
        String errorSummary = normalizeNullableText(request.errorSummary());

        TaskExecutionEntity taskExecution = new TaskExecutionEntity();
        taskExecution.setTask(task);
        taskExecution.setStatus(request.status());
        taskExecution.setRunnerType(request.runnerType());
        taskExecution.setStartedAt(request.startedAt());
        taskExecution.setFinishedAt(request.finishedAt());
        taskExecution.setOutputSummary(outputSummary);
        taskExecution.setErrorSummary(errorSummary);
        taskExecution.setExternalThreadId(null);
        taskExecution.setExternalTurnId(null);
        taskExecution.setCreatedAt(now);

        return toResponse(taskExecutionRepository.save(taskExecution));
    }

    public TaskExecutionResponse launchTask(Long taskId) {
        TaskEntity task = taskRepository.findWithProjectById(taskId)
                .orElseThrow(() -> new TaskNotFoundException(taskId));

        Instant now = Instant.now();
        TaskExecutionEntity taskExecution = createStartedCodexExecution(task, now);
        taskExecutionRepository.saveAndFlush(taskExecution);

        String repoPath = normalizeNullableText(task.getProject().getRepoPath());
        if (repoPath == null) {
            return markExecutionFailed(taskExecution, "Project repoPath is not configured");
        }

        String prompt = buildPrompt(task);

        try {
            CodexAppServerExecutionResult executionResult = codexAppServerClient.execute(
                    new CodexAppServerExecutionRequest(repoPath, prompt),
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
        } catch (TimeoutException exception) {
            return markExecutionFailed(taskExecution, exception.getMessage());
        } catch (Exception exception) {
            return markExecutionFailed(taskExecution, "Failed to call Codex App Server: " + exception.getMessage());
        }
    }

    private TaskExecutionEntity createStartedCodexExecution(TaskEntity task, Instant now) {
        TaskExecutionEntity taskExecution = new TaskExecutionEntity();
        taskExecution.setTask(task);
        taskExecution.setStatus(TaskExecutionStatus.RUNNING);
        taskExecution.setRunnerType(TaskExecutionRunnerType.CODEX);
        taskExecution.setStartedAt(now);
        taskExecution.setFinishedAt(null);
        taskExecution.setOutputSummary(null);
        taskExecution.setErrorSummary(null);
        taskExecution.setExternalThreadId(null);
        taskExecution.setExternalTurnId(null);
        taskExecution.setCreatedAt(now);
        return taskExecution;
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
                taskExecution.getStartedAt(),
                taskExecution.getFinishedAt(),
                taskExecution.getOutputSummary(),
                taskExecution.getErrorSummary(),
                taskExecution.getCreatedAt()
        );
    }

    private static String buildPrompt(TaskEntity task) {
        StringBuilder prompt = new StringBuilder();
        prompt.append(STANDARD_SYSTEM_INSTRUCTIONS.trim())
                .append(System.lineSeparator())
                .append(System.lineSeparator())
                .append("Task title: ")
                .append(task.getTitle());

        if (task.getDescription() != null) {
            prompt.append(System.lineSeparator())
                    .append("Task description: ")
                    .append(task.getDescription());
        }

        return prompt.toString();
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
