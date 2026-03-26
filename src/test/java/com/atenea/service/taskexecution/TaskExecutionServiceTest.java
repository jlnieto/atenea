package com.atenea.service.taskexecution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.atenea.api.taskexecution.TaskExecutionResponse;
import com.atenea.codexappserver.CodexAppServerClient;
import com.atenea.codexappserver.CodexAppServerExecutionListener;
import com.atenea.codexappserver.CodexAppServerExecutionRequest;
import com.atenea.codexappserver.CodexAppServerExecutionResult;
import com.atenea.persistence.project.ProjectEntity;
import com.atenea.persistence.task.TaskBranchStatus;
import com.atenea.persistence.task.TaskEntity;
import com.atenea.persistence.task.TaskPriority;
import com.atenea.persistence.task.TaskRepository;
import com.atenea.persistence.task.TaskStatus;
import com.atenea.persistence.taskexecution.TaskExecutionEntity;
import com.atenea.persistence.taskexecution.TaskExecutionRepository;
import com.atenea.persistence.taskexecution.TaskExecutionRunnerType;
import com.atenea.persistence.taskexecution.TaskExecutionStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TaskExecutionServiceTest {

    @Mock
    private TaskRepository taskRepository;

    @Mock
    private TaskExecutionRepository taskExecutionRepository;

    @Mock
    private CodexAppServerClient codexAppServerClient;

    @Mock
    private TaskExecutionProgressService taskExecutionProgressService;

    @Mock
    private ExecutionPlanningService executionPlanningService;

    @Mock
    private TaskBranchLaunchService taskBranchLaunchService;

    @Mock
    private TaskExecutionReadinessService taskExecutionReadinessService;

    @InjectMocks
    private TaskExecutionService taskExecutionService;

    @Test
    void getExecutionsThrowsWhenTaskDoesNotExist() {
        when(taskRepository.existsById(42L)).thenReturn(false);

        assertThrows(TaskNotFoundException.class, () -> taskExecutionService.getExecutions(42L));
    }

    @Test
    void getExecutionsReturnsMappedResponsesIncludingExternalIds() {
        when(taskRepository.existsById(42L)).thenReturn(true);
        TaskExecutionEntity first = buildExecution(101L, 42L, TaskExecutionStatus.SUCCEEDED, "thread-a", "turn-a");
        TaskExecutionEntity second = buildExecution(100L, 42L, TaskExecutionStatus.FAILED, "thread-b", "turn-b");
        when(taskExecutionRepository.findByTaskIdOrderByCreatedAtDesc(42L)).thenReturn(List.of(first, second));

        List<TaskExecutionResponse> response = taskExecutionService.getExecutions(42L);

        assertEquals(List.of(101L, 100L), response.stream().map(TaskExecutionResponse::id).toList());
        assertEquals("thread-a", response.get(0).externalThreadId());
        assertEquals("turn-a", response.get(0).externalTurnId());
        assertEquals("/srv/repos/demo", response.get(0).targetRepoPath());
        assertEquals(TaskExecutionStatus.FAILED, response.get(1).status());
    }

    @Test
    void getExecutionReturnsMappedExecutionWhenItBelongsToTask() {
        when(taskRepository.existsById(42L)).thenReturn(true);
        TaskExecutionEntity execution = buildExecution(101L, 42L, TaskExecutionStatus.SUCCEEDED, "thread-a", "turn-a");
        when(taskExecutionRepository.findById(101L)).thenReturn(Optional.of(execution));

        TaskExecutionResponse response = taskExecutionService.getExecution(42L, 101L);

        assertEquals(101L, response.id());
        assertEquals(42L, response.taskId());
        assertEquals("thread-a", response.externalThreadId());
    }

    @Test
    void getExecutionThrowsWhenExecutionDoesNotBelongToTask() {
        when(taskRepository.existsById(42L)).thenReturn(true);
        TaskExecutionEntity execution = buildExecution(101L, 43L, TaskExecutionStatus.SUCCEEDED, "thread-a", "turn-a");
        when(taskExecutionRepository.findById(101L)).thenReturn(Optional.of(execution));

        assertThrows(TaskExecutionNotFoundException.class, () -> taskExecutionService.getExecution(42L, 101L));
    }

    @Test
    void launchTaskFailsWhenRepoPathIsBlank() throws Exception {
        TaskEntity task = buildTask("   ");

        when(taskRepository.findWithProjectById(42L)).thenReturn(Optional.of(task));
        when(taskExecutionReadinessService.assess(task)).thenReturn(new TaskExecutionReadiness(true, "ready"));
        when(executionPlanningService.createPlan(task)).thenReturn(
                new ExecutionPlan(task, TaskExecutionRunnerType.CODEX, null, null,
                        "Project repoPath is not configured", ExecutionTargetType.STANDARD));
        mockPersistingSaves();

        TaskExecutionResponse response = taskExecutionService.launchTask(42L);

        assertEquals(TaskExecutionStatus.FAILED, response.status());
        assertEquals(TaskExecutionRunnerType.CODEX, response.runnerType());
        assertNull(response.outputSummary());
        assertEquals("Project repoPath is not configured", response.errorSummary());
        assertNull(response.externalThreadId());
        assertNull(response.externalTurnId());
        assertNotNull(response.finishedAt());

        verify(codexAppServerClient, never()).execute(any(), any());
    }

    @Test
    void launchTaskSucceedsAndReturnsExternalIds() throws Exception {
        TaskEntity task = buildTask("/srv/repos/demo");
        when(taskRepository.findWithProjectById(42L)).thenReturn(Optional.of(task));
        when(taskExecutionReadinessService.assess(task)).thenReturn(new TaskExecutionReadiness(true, "ready"));
        when(executionPlanningService.createPlan(task)).thenReturn(
                new ExecutionPlan(task, TaskExecutionRunnerType.CODEX, "/srv/repos/demo", "generated prompt",
                        null, ExecutionTargetType.STANDARD));
        mockPersistingSaves();
        when(codexAppServerClient.execute(any(CodexAppServerExecutionRequest.class), any(CodexAppServerExecutionListener.class)))
                .thenAnswer(invocation -> {
                    CodexAppServerExecutionListener listener = invocation.getArgument(1);
                    listener.onThreadStarted("thread-progress");
                    listener.onTurnStarted("thread-progress", "turn-progress");
                    return new CodexAppServerExecutionResult(
                            "thread-final",
                            "turn-final",
                            CodexAppServerExecutionResult.Status.COMPLETED,
                            "Implemented the launch flow and added tests.",
                            "Working on it",
                            null);
                });

        TaskExecutionResponse response = taskExecutionService.launchTask(42L);

        assertEquals(TaskExecutionStatus.SUCCEEDED, response.status());
        assertEquals(TaskExecutionRunnerType.CODEX, response.runnerType());
        assertEquals("/srv/repos/demo", response.targetRepoPath());
        assertEquals("Implemented the launch flow and added tests.", response.outputSummary());
        assertNull(response.errorSummary());
        assertEquals("thread-final", response.externalThreadId());
        assertEquals("turn-final", response.externalTurnId());
        assertNotNull(response.startedAt());
        assertNotNull(response.finishedAt());

        verify(taskExecutionProgressService, times(2)).persistExternalThreadId(500L, "thread-progress");
        verify(taskExecutionProgressService).persistExternalTurnId(500L, "turn-progress");
        verify(taskBranchLaunchService).prepareLaunch(task, "/srv/repos/demo");

        ArgumentCaptor<CodexAppServerExecutionRequest> requestCaptor =
                ArgumentCaptor.forClass(CodexAppServerExecutionRequest.class);
        verify(codexAppServerClient).execute(requestCaptor.capture(), any(CodexAppServerExecutionListener.class));
        assertEquals("/srv/repos/demo", requestCaptor.getValue().repoPath());
        assertEquals("generated prompt", requestCaptor.getValue().prompt());
    }

    @Test
    void launchTaskFailsWhenCodexTimesOut() throws Exception {
        TaskEntity task = buildTask("/srv/repos/demo");
        when(taskRepository.findWithProjectById(42L)).thenReturn(Optional.of(task));
        when(taskExecutionReadinessService.assess(task)).thenReturn(new TaskExecutionReadiness(true, "ready"));
        when(executionPlanningService.createPlan(task)).thenReturn(
                new ExecutionPlan(task, TaskExecutionRunnerType.CODEX, "/srv/repos/demo", "prompt",
                        null, ExecutionTargetType.STANDARD));
        mockPersistingSaves();
        when(codexAppServerClient.execute(any(CodexAppServerExecutionRequest.class), any(CodexAppServerExecutionListener.class)))
                .thenThrow(new TimeoutException("Timed out waiting for Codex App Server completion"));

        TaskExecutionResponse response = taskExecutionService.launchTask(42L);

        assertEquals(TaskExecutionStatus.FAILED, response.status());
        assertNull(response.outputSummary());
        assertEquals("Timed out waiting for Codex App Server completion", response.errorSummary());
    }

    @Test
    void launchTaskFailsWhenTaskDoesNotExist() {
        when(taskRepository.findWithProjectById(42L)).thenReturn(Optional.empty());

        assertThrows(TaskNotFoundException.class, () -> taskExecutionService.launchTask(42L));
    }

    @Test
    void launchTaskFailsWhenBranchPolicyBlocksLaunch() throws Exception {
        TaskEntity task = buildTask("/srv/repos/demo");
        when(taskRepository.findWithProjectById(42L)).thenReturn(Optional.of(task));
        when(taskExecutionReadinessService.assess(task)).thenReturn(new TaskExecutionReadiness(true, "ready"));
        when(executionPlanningService.createPlan(task)).thenReturn(
                new ExecutionPlan(task, TaskExecutionRunnerType.CODEX, "/srv/repos/demo", "prompt",
                        null, ExecutionTargetType.STANDARD));
        mockPersistingSaves();
        org.mockito.Mockito.doThrow(new TaskLaunchBlockedException("Project 'demo' is locked by task '41'"))
                .when(taskBranchLaunchService).prepareLaunch(task, "/srv/repos/demo");

        TaskExecutionResponse response = taskExecutionService.launchTask(42L);

        assertEquals(TaskExecutionStatus.FAILED, response.status());
        assertEquals("Project 'demo' is locked by task '41'", response.errorSummary());
        verify(codexAppServerClient, never()).execute(any(), any());
    }

    @Test
    void relaunchTaskFailsWhenNoPreviousExecutionExists() {
        TaskEntity task = buildTask("/srv/repos/demo");
        when(taskRepository.findWithProjectById(42L)).thenReturn(Optional.of(task));
        when(taskExecutionRepository.existsByTaskId(42L)).thenReturn(false);

        assertThrows(TaskRelaunchNotAllowedException.class, () -> taskExecutionService.relaunchTask(42L));
    }

    @Test
    void relaunchTaskReusesNormalExecutionFlowWhenHistoryExists() throws Exception {
        TaskEntity task = buildTask("/srv/repos/demo");
        when(taskRepository.findWithProjectById(42L)).thenReturn(Optional.of(task));
        when(taskExecutionRepository.existsByTaskId(42L)).thenReturn(true);
        when(taskExecutionReadinessService.assess(task)).thenReturn(new TaskExecutionReadiness(true, "ready"));
        when(executionPlanningService.createPlan(task)).thenReturn(
                new ExecutionPlan(task, TaskExecutionRunnerType.CODEX, "/srv/repos/demo", "generated prompt",
                        null, ExecutionTargetType.STANDARD));
        mockPersistingSaves();
        when(codexAppServerClient.execute(any(CodexAppServerExecutionRequest.class), any(CodexAppServerExecutionListener.class)))
                .thenReturn(new CodexAppServerExecutionResult(
                        "thread-final",
                        "turn-final",
                        CodexAppServerExecutionResult.Status.COMPLETED,
                        "Retried successfully.",
                        "Working on it",
                        null));

        TaskExecutionResponse response = taskExecutionService.relaunchTask(42L);

        assertEquals(TaskExecutionStatus.SUCCEEDED, response.status());
        assertEquals("Retried successfully.", response.outputSummary());
        verify(taskBranchLaunchService).prepareLaunch(task, "/srv/repos/demo");
    }

    @Test
    void launchTaskRejectsAmbiguousTaskBeforeCreatingExecution() throws Exception {
        TaskEntity task = buildTask("/srv/repos/demo");
        when(taskRepository.findWithProjectById(42L)).thenReturn(Optional.of(task));
        when(taskExecutionReadinessService.assess(task)).thenReturn(new TaskExecutionReadiness(
                false,
                "task description looks diagnostic or validation-only; specify the concrete change to make"));

        TaskLaunchBlockedException exception = assertThrows(
                TaskLaunchBlockedException.class,
                () -> taskExecutionService.launchTask(42L));

        assertEquals(
                "Task requires clarification before launch: task description looks diagnostic or validation-only; specify the concrete change to make",
                exception.getMessage());
        verify(taskExecutionRepository, never()).saveAndFlush(any(TaskExecutionEntity.class));
        verify(codexAppServerClient, never()).execute(any(), any());
    }

    private void mockPersistingSaves() {
        AtomicReference<TaskExecutionEntity> persisted = new AtomicReference<>();
        when(taskExecutionRepository.saveAndFlush(any(TaskExecutionEntity.class))).thenAnswer(invocation -> {
            TaskExecutionEntity entity = invocation.getArgument(0);
            if (entity.getId() == null) {
                entity.setId(500L);
            }
            persisted.set(entity);
            return entity;
        });
        when(taskExecutionRepository.save(any(TaskExecutionEntity.class))).thenAnswer(invocation -> {
            TaskExecutionEntity entity = invocation.getArgument(0);
            persisted.set(entity);
            return entity;
        });
        lenient().when(taskExecutionRepository.findById(500L))
                .thenAnswer(invocation -> Optional.ofNullable(persisted.get()));
    }

    private static TaskEntity buildTask(String repoPath) {
        ProjectEntity project = new ProjectEntity();
        project.setId(7L);
        project.setName("demo");
        project.setRepoPath(repoPath);
        project.setCreatedAt(Instant.now());
        project.setUpdatedAt(Instant.now());

        TaskEntity task = new TaskEntity();
        task.setId(42L);
        task.setProject(project);
        task.setTitle("Invalid repoPath launch check");
        task.setDescription("Validate non-existent in-container repoPath handling.");
        task.setBaseBranch("main");
        task.setBranchName("task/42-invalid-repopath-launch-check");
        task.setBranchStatus(TaskBranchStatus.PLANNED);
        task.setStatus(TaskStatus.PENDING);
        task.setPriority(TaskPriority.NORMAL);
        task.setCreatedAt(Instant.now());
        task.setUpdatedAt(Instant.now());
        return task;
    }

    private static TaskExecutionEntity buildExecution(
            Long executionId,
            Long taskId,
            TaskExecutionStatus status,
            String externalThreadId,
            String externalTurnId
    ) {
        TaskEntity task = buildTask("/srv/repos/demo");
        task.setId(taskId);

        TaskExecutionEntity execution = new TaskExecutionEntity();
        execution.setId(executionId);
        execution.setTask(task);
        execution.setStatus(status);
        execution.setRunnerType(TaskExecutionRunnerType.CODEX);
        execution.setTargetRepoPath("/srv/repos/demo");
        execution.setStartedAt(Instant.parse("2026-03-22T10:00:00Z"));
        execution.setFinishedAt(Instant.parse("2026-03-22T10:01:00Z"));
        execution.setOutputSummary(status == TaskExecutionStatus.SUCCEEDED ? "ok" : null);
        execution.setErrorSummary(status == TaskExecutionStatus.FAILED ? "error" : null);
        execution.setExternalThreadId(externalThreadId);
        execution.setExternalTurnId(externalTurnId);
        execution.setCreatedAt(Instant.parse("2026-03-22T10:00:00Z"));
        return execution;
    }
}
