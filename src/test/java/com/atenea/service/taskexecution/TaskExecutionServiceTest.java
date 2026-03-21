package com.atenea.service.taskexecution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.atenea.api.taskexecution.TaskExecutionResponse;
import com.atenea.codexappserver.CodexAppServerClient;
import com.atenea.persistence.project.ProjectEntity;
import com.atenea.persistence.task.TaskEntity;
import com.atenea.persistence.task.TaskPriority;
import com.atenea.persistence.task.TaskRepository;
import com.atenea.persistence.task.TaskStatus;
import com.atenea.persistence.taskexecution.TaskExecutionEntity;
import com.atenea.persistence.taskexecution.TaskExecutionRepository;
import com.atenea.persistence.taskexecution.TaskExecutionRunnerType;
import com.atenea.persistence.taskexecution.TaskExecutionStatus;
import java.time.Instant;
import java.util.Optional;
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

    @InjectMocks
    private TaskExecutionService taskExecutionService;

    @Test
    void launchTaskFailsWhenRepoPathIsBlank() throws Exception {
        TaskEntity task = buildTask("   ");

        when(taskRepository.findWithProjectById(42L)).thenReturn(Optional.of(task));
        when(taskExecutionRepository.saveAndFlush(any(TaskExecutionEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(taskExecutionRepository.save(any(TaskExecutionEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        TaskExecutionResponse response = taskExecutionService.launchTask(42L);

        assertEquals(TaskExecutionStatus.FAILED, response.status());
        assertEquals(TaskExecutionRunnerType.CODEX, response.runnerType());
        assertNull(response.outputSummary());
        assertEquals("Project repoPath is not configured", response.errorSummary());
        assertNotNull(response.finishedAt());

        verify(codexAppServerClient, never()).execute(any(), any());

        ArgumentCaptor<TaskExecutionEntity> savedExecution = ArgumentCaptor.forClass(TaskExecutionEntity.class);
        verify(taskExecutionRepository).save(savedExecution.capture());
        assertEquals(TaskExecutionStatus.FAILED, savedExecution.getValue().getStatus());
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
        task.setStatus(TaskStatus.PENDING);
        task.setPriority(TaskPriority.NORMAL);
        task.setCreatedAt(Instant.now());
        task.setUpdatedAt(Instant.now());
        return task;
    }
}
