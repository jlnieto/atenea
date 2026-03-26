package com.atenea.service.taskexecution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.atenea.persistence.project.ProjectEntity;
import com.atenea.persistence.task.TaskBranchStatus;
import com.atenea.persistence.task.TaskEntity;
import com.atenea.persistence.task.TaskRepository;
import com.atenea.persistence.task.TaskStatus;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TaskBranchLaunchServiceTest {

    @Mock
    private TaskRepository taskRepository;

    @Mock
    private GitRepositoryService gitRepositoryService;

    @InjectMocks
    private TaskBranchLaunchService taskBranchLaunchService;

    @Test
    void prepareLaunchCreatesTaskBranchFromUpdatedBaseBranch() {
        TaskEntity task = buildTask(42L, "Atenea", "main", "task/42-fix-launch-flow", TaskBranchStatus.PLANNED);
        when(taskRepository.findByProjectIdAndBranchStatusInOrderByCreatedAtAsc(task.getProject().getId(), List.of(
                TaskBranchStatus.ACTIVE,
                TaskBranchStatus.REVIEW_PENDING))).thenReturn(List.of(task));
        when(gitRepositoryService.inspect("/workspace/repos/internal/atenea", "main", "task/42-fix-launch-flow"))
                .thenReturn(new GitRepositoryState("main", true, true, false));

        taskBranchLaunchService.prepareLaunch(task, "/workspace/repos/internal/atenea");

        verify(gitRepositoryService).createAndCheckoutBranch(
                "/workspace/repos/internal/atenea",
                "main",
                "task/42-fix-launch-flow");
        ArgumentCaptor<TaskEntity> captor = ArgumentCaptor.forClass(TaskEntity.class);
        verify(taskRepository).save(captor.capture());
        assertEquals(TaskBranchStatus.ACTIVE, captor.getValue().getBranchStatus());
    }

    @Test
    void prepareLaunchChecksOutExistingTaskBranchFromBaseBranch() {
        TaskEntity task = buildTask(42L, "Atenea", "main", "task/42-fix-launch-flow", TaskBranchStatus.PLANNED);
        when(taskRepository.findByProjectIdAndBranchStatusInOrderByCreatedAtAsc(task.getProject().getId(), List.of(
                TaskBranchStatus.ACTIVE,
                TaskBranchStatus.REVIEW_PENDING))).thenReturn(List.of(task));
        when(gitRepositoryService.inspect("/workspace/repos/internal/atenea", "main", "task/42-fix-launch-flow"))
                .thenReturn(new GitRepositoryState("main", true, true, true));

        taskBranchLaunchService.prepareLaunch(task, "/workspace/repos/internal/atenea");

        verify(gitRepositoryService).checkoutBranch("/workspace/repos/internal/atenea", "task/42-fix-launch-flow");
    }

    @Test
    void prepareLaunchRejectsWhenAnotherTaskLocksProject() {
        TaskEntity task = buildTask(42L, "Atenea", "main", "task/42-fix-launch-flow", TaskBranchStatus.PLANNED);
        TaskEntity otherTask = buildTask(41L, "Atenea", "main", "task/41-other", TaskBranchStatus.ACTIVE);
        when(taskRepository.findByProjectIdAndBranchStatusInOrderByCreatedAtAsc(task.getProject().getId(), List.of(
                TaskBranchStatus.ACTIVE,
                TaskBranchStatus.REVIEW_PENDING))).thenReturn(List.of(otherTask, task));

        TaskLaunchBlockedException exception = assertThrows(
                TaskLaunchBlockedException.class,
                () -> taskBranchLaunchService.prepareLaunch(task, "/workspace/repos/internal/atenea"));

        assertEquals("Project 'Atenea' is locked by task '41' on branch 'task/41-other'", exception.getMessage());
        verify(gitRepositoryService, never()).inspect("/workspace/repos/internal/atenea", "main", "task/42-fix-launch-flow");
    }

    @Test
    void prepareLaunchRejectsWhenBaseBranchIsNotUpdated() {
        TaskEntity task = buildTask(42L, "Atenea", "main", "task/42-fix-launch-flow", TaskBranchStatus.PLANNED);
        when(taskRepository.findByProjectIdAndBranchStatusInOrderByCreatedAtAsc(task.getProject().getId(), List.of(
                TaskBranchStatus.ACTIVE,
                TaskBranchStatus.REVIEW_PENDING))).thenReturn(List.of(task));
        when(gitRepositoryService.inspect("/workspace/repos/internal/atenea", "main", "task/42-fix-launch-flow"))
                .thenReturn(new GitRepositoryState("main", true, false, false));

        TaskLaunchBlockedException exception = assertThrows(
                TaskLaunchBlockedException.class,
                () -> taskBranchLaunchService.prepareLaunch(task, "/workspace/repos/internal/atenea"));

        assertEquals("Base branch 'main' is not up to date with its upstream; launch is blocked", exception.getMessage());
    }

    private static TaskEntity buildTask(
            Long taskId,
            String projectName,
            String baseBranch,
            String branchName,
            TaskBranchStatus branchStatus
    ) {
        ProjectEntity project = new ProjectEntity();
        project.setId(7L);
        project.setName(projectName);
        project.setRepoPath("/workspace/repos/internal/atenea");
        project.setCreatedAt(Instant.now());
        project.setUpdatedAt(Instant.now());

        TaskEntity task = new TaskEntity();
        task.setId(taskId);
        task.setProject(project);
        task.setTitle("Fix launch flow");
        task.setDescription("desc");
        task.setBaseBranch(baseBranch);
        task.setBranchName(branchName);
        task.setBranchStatus(branchStatus);
        task.setStatus(TaskStatus.PENDING);
        task.setCreatedAt(Instant.now());
        task.setUpdatedAt(Instant.now());
        return task;
    }

    @Test
    void prepareLaunchRejectsTerminalTaskStatus() {
        TaskEntity task = buildTask(42L, "Atenea", "main", "task/42-fix-launch-flow", TaskBranchStatus.ACTIVE);
        task.setStatus(TaskStatus.DONE);

        TaskLaunchBlockedException exception = assertThrows(
                TaskLaunchBlockedException.class,
                () -> taskBranchLaunchService.prepareLaunch(task, "/workspace/repos/internal/atenea"));

        assertEquals("Task '42' is in terminal status 'DONE' and cannot be launched", exception.getMessage());
    }

    @Test
    void prepareLaunchDoesNotTreatPlannedTaskAsProjectLock() {
        TaskEntity task = buildTask(42L, "Atenea", "main", "task/42-fix-launch-flow", TaskBranchStatus.PLANNED);
        TaskEntity otherPlannedTask = buildTask(41L, "Atenea", "main", "task/41-other", TaskBranchStatus.PLANNED);
        when(taskRepository.findByProjectIdAndBranchStatusInOrderByCreatedAtAsc(task.getProject().getId(), List.of(
                TaskBranchStatus.ACTIVE,
                TaskBranchStatus.REVIEW_PENDING))).thenReturn(List.of());
        when(gitRepositoryService.inspect("/workspace/repos/internal/atenea", "main", "task/42-fix-launch-flow"))
                .thenReturn(new GitRepositoryState("main", true, true, false));

        taskBranchLaunchService.prepareLaunch(task, "/workspace/repos/internal/atenea");

        verify(gitRepositoryService).createAndCheckoutBranch("/workspace/repos/internal/atenea", "main", "task/42-fix-launch-flow");
        verify(taskRepository, never()).save(otherPlannedTask);
    }
}
