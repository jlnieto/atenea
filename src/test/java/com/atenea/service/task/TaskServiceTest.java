package com.atenea.service.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.atenea.api.task.CreateTaskRequest;
import com.atenea.api.task.TaskResponse;
import com.atenea.persistence.project.ProjectEntity;
import com.atenea.persistence.task.TaskBranchStatus;
import com.atenea.persistence.task.TaskPullRequestStatus;
import com.atenea.persistence.task.TaskReviewOutcome;
import com.atenea.persistence.project.ProjectRepository;
import com.atenea.persistence.task.TaskEntity;
import com.atenea.persistence.task.TaskPriority;
import com.atenea.persistence.task.TaskRepository;
import com.atenea.persistence.task.TaskStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith(MockitoExtension.class)
class TaskServiceTest {

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private TaskRepository taskRepository;

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private TaskResponseMapper taskResponseMapper;

    @InjectMocks
    private TaskService taskService;

    @Test
    void createTaskNormalizesFieldsAndPersists() {
        ProjectEntity project = buildProject(7L);
        when(projectRepository.findById(7L)).thenReturn(Optional.of(project));
        when(taskRepository.findByProjectIdAndTitle(7L, "Implement launch flow")).thenReturn(Optional.empty());
        when(taskRepository.nextTaskId()).thenReturn(22L);
        TaskEntity persistedTask = buildTask(22L, 7L, "Implement launch flow", "Keep it small", TaskStatus.PENDING, TaskPriority.HIGH);
        persistedTask.setBaseBranch("develop");
        when(taskRepository.findWithProjectById(22L)).thenReturn(Optional.of(persistedTask));
        when(taskResponseMapper.toResponse(persistedTask)).thenReturn(taskResponse(
                22L,
                7L,
                "Implement launch flow",
                "Keep it small",
                "develop",
                "task/22-implement-launch-flow",
                TaskBranchStatus.PLANNED,
                TaskStatus.PENDING,
                TaskPriority.HIGH));

        TaskResponse response = taskService.createTask(7L, new CreateTaskRequest(
                "  Implement launch flow  ",
                "   Keep it small  ",
                "  develop  ",
                TaskStatus.PENDING,
                TaskPriority.HIGH));

        assertEquals(22L, response.id());
        assertEquals("Implement launch flow", response.title());
        assertEquals("Keep it small", response.description());
        assertEquals("develop", response.baseBranch());
        assertEquals("task/22-implement-launch-flow", response.branchName());
        assertEquals(TaskBranchStatus.PLANNED, response.branchStatus());
        assertNull(response.pullRequestUrl());
        assertEquals(TaskPullRequestStatus.NOT_CREATED, response.pullRequestStatus());
        assertEquals(TaskReviewOutcome.PENDING, response.reviewOutcome());
        assertNull(response.reviewNotes());
        assertEquals(false, response.projectBlocked());
        assertEquals(false, response.hasReviewableChanges());
        assertEquals(false, response.lastExecutionFailed());
        assertEquals("none", response.blockingReason());
        assertEquals("launch", response.nextAction());
        assertEquals("none", response.recoveryAction());
        assertEquals(TaskStatus.PENDING, response.status());
        assertEquals(TaskPriority.HIGH, response.priority());
        verify(jdbcTemplate, times(1)).update(any(String.class), any(Object[].class));
    }

    @Test
    void createTaskConvertsBlankDescriptionToNull() {
        ProjectEntity project = buildProject(7L);
        when(projectRepository.findById(7L)).thenReturn(Optional.of(project));
        when(taskRepository.findByProjectIdAndTitle(7L, "Implement launch flow")).thenReturn(Optional.empty());
        when(taskRepository.nextTaskId()).thenReturn(22L);
        TaskEntity persistedTask = buildTask(
                22L,
                7L,
                "Implement launch flow",
                null,
                TaskStatus.PENDING,
                TaskPriority.NORMAL);
        when(taskRepository.findWithProjectById(22L)).thenReturn(Optional.of(persistedTask));
        when(taskResponseMapper.toResponse(persistedTask)).thenReturn(taskResponse(
                22L,
                7L,
                "Implement launch flow",
                null,
                "main",
                "task/22-implement-launch-flow",
                TaskBranchStatus.PLANNED,
                TaskStatus.PENDING,
                TaskPriority.NORMAL));

        TaskResponse response = taskService.createTask(7L, new CreateTaskRequest(
                "Implement launch flow",
                "   ",
                null,
                TaskStatus.PENDING,
                TaskPriority.NORMAL));

        assertNull(response.description());
        assertEquals("main", response.baseBranch());
    }

    @Test
    void createTaskThrowsWhenProjectDoesNotExist() {
        when(projectRepository.findById(7L)).thenReturn(Optional.empty());

        assertThrows(ProjectNotFoundException.class, () -> taskService.createTask(
                7L,
                new CreateTaskRequest("Implement launch flow", null, null, TaskStatus.PENDING, TaskPriority.NORMAL)));
    }

    @Test
    void createTaskThrowsWhenTitleAlreadyExistsInProject() {
        ProjectEntity project = buildProject(7L);
        when(projectRepository.findById(7L)).thenReturn(Optional.of(project));
        when(taskRepository.findByProjectIdAndTitle(7L, "Implement launch flow")).thenReturn(Optional.of(new TaskEntity()));

        assertThrows(DuplicateTaskTitleException.class, () -> taskService.createTask(
                7L,
                new CreateTaskRequest("  Implement launch flow  ", null, null, TaskStatus.PENDING, TaskPriority.NORMAL)));
    }

    @Test
    void getTasksThrowsWhenProjectDoesNotExist() {
        when(projectRepository.existsById(7L)).thenReturn(false);

        assertThrows(ProjectNotFoundException.class, () -> taskService.getTasks(7L));
    }

    @Test
    void getTasksReturnsMappedResponses() {
        when(projectRepository.existsById(7L)).thenReturn(true);
        TaskEntity first = buildTask(11L, 7L, "First", null, TaskStatus.PENDING, TaskPriority.NORMAL);
        TaskEntity second = buildTask(12L, 7L, "Second", "desc", TaskStatus.DONE, TaskPriority.HIGH);
        when(taskRepository.findByProjectIdOrderByCreatedAtAsc(7L)).thenReturn(List.of(first, second));
        when(taskResponseMapper.toResponse(first)).thenReturn(taskResponse(
                11L, 7L, "First", null, "main", "task/11-first", TaskBranchStatus.PLANNED, TaskStatus.PENDING, TaskPriority.NORMAL));
        when(taskResponseMapper.toResponse(second)).thenReturn(taskResponse(
                12L, 7L, "Second", "desc", "main", "task/12-second", TaskBranchStatus.PLANNED, TaskStatus.DONE, TaskPriority.HIGH));

        List<TaskResponse> response = taskService.getTasks(7L);

        assertEquals(List.of(11L, 12L), response.stream().map(TaskResponse::id).toList());
        assertEquals("First", response.get(0).title());
        assertEquals("main", response.get(0).baseBranch());
        assertEquals("task/11-first", response.get(0).branchName());
        assertEquals(TaskPullRequestStatus.NOT_CREATED, response.get(0).pullRequestStatus());
        assertEquals(TaskReviewOutcome.PENDING, response.get(0).reviewOutcome());
        assertEquals("launch", response.get(0).nextAction());
        assertEquals(TaskStatus.DONE, response.get(1).status());
        assertEquals(TaskPriority.HIGH, response.get(1).priority());
    }

    private static TaskResponse taskResponse(
            Long id,
            Long projectId,
            String title,
            String description,
            String baseBranch,
            String branchName,
            TaskBranchStatus branchStatus,
            TaskStatus status,
            TaskPriority priority
    ) {
        return new TaskResponse(
                id,
                projectId,
                title,
                description,
                baseBranch,
                branchName,
                branchStatus,
                null,
                TaskPullRequestStatus.NOT_CREATED,
                TaskReviewOutcome.PENDING,
                null,
                false,
                false,
                false,
                true,
                "ready",
                "none",
                "launch",
                "none",
                status,
                priority,
                Instant.now(),
                Instant.now()
        );
    }

    private static ProjectEntity buildProject(Long id) {
        ProjectEntity project = new ProjectEntity();
        project.setId(id);
        project.setName("demo");
        project.setCreatedAt(Instant.now());
        project.setUpdatedAt(Instant.now());
        return project;
    }

    private static TaskEntity buildTask(
            Long id,
            Long projectId,
            String title,
            String description,
            TaskStatus status,
            TaskPriority priority
    ) {
        TaskEntity task = new TaskEntity();
        task.setId(id);
        task.setProject(buildProject(projectId));
        task.setTitle(title);
        task.setDescription(description);
        task.setBaseBranch("main");
        task.setBranchName("task/" + id + "-" + title.toLowerCase().replace(' ', '-'));
        task.setBranchStatus(TaskBranchStatus.PLANNED);
        task.setPullRequestUrl(null);
        task.setPullRequestStatus(TaskPullRequestStatus.NOT_CREATED);
        task.setReviewOutcome(TaskReviewOutcome.PENDING);
        task.setReviewNotes(null);
        task.setStatus(status);
        task.setPriority(priority);
        task.setCreatedAt(Instant.now());
        task.setUpdatedAt(Instant.now());
        return task;
    }
}
