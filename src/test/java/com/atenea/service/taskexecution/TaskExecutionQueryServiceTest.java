package com.atenea.service.taskexecution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.atenea.api.taskexecution.TaskExecutionListItemResponse;
import com.atenea.persistence.project.ProjectEntity;
import com.atenea.persistence.task.TaskEntity;
import com.atenea.persistence.task.TaskBranchStatus;
import com.atenea.persistence.task.TaskPullRequestStatus;
import com.atenea.persistence.task.TaskPriority;
import com.atenea.persistence.task.TaskReviewOutcome;
import com.atenea.persistence.task.TaskStatus;
import com.atenea.persistence.taskexecution.TaskExecutionEntity;
import com.atenea.persistence.taskexecution.TaskExecutionRepository;
import com.atenea.persistence.taskexecution.TaskExecutionRunnerType;
import com.atenea.persistence.taskexecution.TaskExecutionStatus;
import com.atenea.service.task.TaskOperationalState;
import com.atenea.service.task.TaskOperationalStateService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TaskExecutionQueryServiceTest {

    @Mock
    private TaskExecutionRepository taskExecutionRepository;

    @Mock
    private TaskOperationalStateService taskOperationalStateService;

    @Test
    void getExecutionsReturnsGlobalExecutionsByDefault() {
        TaskExecutionQueryService service = new TaskExecutionQueryService(taskExecutionRepository, taskOperationalStateService);
        TaskExecutionEntity execution = buildExecution(100L, 42L, 7L, "Atenea", "Fix launch flow");
        when(taskExecutionRepository.findAllByOrderByCreatedAtDesc(any()))
                .thenReturn(List.of(execution));
        when(taskOperationalStateService.resolve(execution.getTask()))
                .thenReturn(new TaskOperationalState(true, false, true, true, "ready", "empty_branch_after_failure", "retry", "retry"));

        List<TaskExecutionListItemResponse> response = service.getExecutions(null, null, null);

        assertEquals(1, response.size());
        assertEquals("Atenea", response.get(0).projectName());
        assertEquals("Fix launch flow", response.get(0).taskTitle());
        assertEquals(TaskBranchStatus.ACTIVE, response.get(0).branchStatus());
        assertEquals(false, response.get(0).hasReviewableChanges());
        assertEquals(true, response.get(0).lastExecutionFailed());
        assertEquals("empty_branch_after_failure", response.get(0).blockingReason());
        assertEquals("retry", response.get(0).nextAction());
        assertEquals("retry", response.get(0).recoveryAction());
    }

    @Test
    void getExecutionsFiltersByProjectAndStatus() {
        TaskExecutionQueryService service = new TaskExecutionQueryService(taskExecutionRepository, taskOperationalStateService);
        TaskExecutionEntity execution = buildExecution(100L, 42L, 7L, "Atenea", "Fix launch flow");
        when(taskExecutionRepository.findByTaskProjectIdAndStatusOrderByCreatedAtDesc(
                eq(7L), eq(TaskExecutionStatus.SUCCEEDED), any()))
                .thenReturn(List.of(execution));
        when(taskOperationalStateService.resolve(execution.getTask()))
                .thenReturn(new TaskOperationalState(true, true, false, true, "ready", "active_branch", "review", "none"));

        List<TaskExecutionListItemResponse> response = service.getExecutions(TaskExecutionStatus.SUCCEEDED, 7L, 5);

        assertEquals(1, response.size());
        verify(taskExecutionRepository).findByTaskProjectIdAndStatusOrderByCreatedAtDesc(
                eq(7L), eq(TaskExecutionStatus.SUCCEEDED), any());
    }

    @Test
    void getExecutionsRejectsNonPositiveLimit() {
        TaskExecutionQueryService service = new TaskExecutionQueryService(taskExecutionRepository, taskOperationalStateService);

        assertThrows(IllegalArgumentException.class, () -> service.getExecutions(null, null, 0));

        verify(taskExecutionRepository, never()).findAllByOrderByCreatedAtDesc(any());
    }

    private static TaskExecutionEntity buildExecution(
            Long executionId,
            Long taskId,
            Long projectId,
            String projectName,
            String taskTitle
    ) {
        ProjectEntity project = new ProjectEntity();
        project.setId(projectId);
        project.setName(projectName);
        project.setRepoPath("/workspace/repos/internal/atenea");
        project.setCreatedAt(Instant.parse("2026-03-22T08:00:00Z"));
        project.setUpdatedAt(Instant.parse("2026-03-22T08:05:00Z"));

        TaskEntity task = new TaskEntity();
        task.setId(taskId);
        task.setProject(project);
        task.setTitle(taskTitle);
        task.setDescription("desc");
        task.setBaseBranch("main");
        task.setBranchName("task/" + taskId + "-fix-launch-flow");
        task.setBranchStatus(TaskBranchStatus.ACTIVE);
        task.setPullRequestUrl(null);
        task.setPullRequestStatus(TaskPullRequestStatus.NOT_CREATED);
        task.setReviewOutcome(TaskReviewOutcome.PENDING);
        task.setReviewNotes(null);
        task.setStatus(TaskStatus.PENDING);
        task.setPriority(TaskPriority.NORMAL);
        task.setCreatedAt(Instant.parse("2026-03-22T10:00:00Z"));
        task.setUpdatedAt(Instant.parse("2026-03-22T10:01:00Z"));

        TaskExecutionEntity execution = new TaskExecutionEntity();
        execution.setId(executionId);
        execution.setTask(task);
        execution.setStatus(TaskExecutionStatus.SUCCEEDED);
        execution.setRunnerType(TaskExecutionRunnerType.CODEX);
        execution.setTargetRepoPath("/workspace/repos/internal/atenea");
        execution.setStartedAt(Instant.parse("2026-03-22T10:02:00Z"));
        execution.setFinishedAt(Instant.parse("2026-03-22T10:03:00Z"));
        execution.setOutputSummary("ok");
        execution.setExternalThreadId("thread-1");
        execution.setExternalTurnId("turn-1");
        execution.setCreatedAt(Instant.parse("2026-03-22T10:02:00Z"));
        return execution;
    }
}
