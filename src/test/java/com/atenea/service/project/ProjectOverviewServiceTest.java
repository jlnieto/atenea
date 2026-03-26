package com.atenea.service.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.when;

import com.atenea.api.project.ProjectOverviewResponse;
import com.atenea.api.task.TaskResponse;
import com.atenea.persistence.project.ProjectEntity;
import com.atenea.persistence.project.ProjectRepository;
import com.atenea.persistence.task.TaskEntity;
import com.atenea.persistence.task.TaskBranchStatus;
import com.atenea.persistence.task.TaskPullRequestStatus;
import com.atenea.persistence.task.TaskPriority;
import com.atenea.persistence.task.TaskReviewOutcome;
import com.atenea.persistence.task.TaskRepository;
import com.atenea.persistence.task.TaskStatus;
import com.atenea.persistence.taskexecution.TaskExecutionEntity;
import com.atenea.persistence.taskexecution.TaskExecutionRepository;
import com.atenea.persistence.taskexecution.TaskExecutionRunnerType;
import com.atenea.persistence.taskexecution.TaskExecutionStatus;
import com.atenea.service.task.TaskResponseMapper;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Sort;

@ExtendWith(MockitoExtension.class)
class ProjectOverviewServiceTest {

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private TaskRepository taskRepository;

    @Mock
    private TaskExecutionRepository taskExecutionRepository;

    @Mock
    private TaskResponseMapper taskResponseMapper;

    @Test
    void getOverviewReturnsLatestTaskAndLatestExecutionPerProject() {
        ProjectOverviewService projectOverviewService =
                new ProjectOverviewService(projectRepository, taskRepository, taskExecutionRepository, taskResponseMapper);
        ProjectEntity project = project(1L, "Atenea", "/workspace/repos/internal/atenea");
        TaskEntity olderTask = task(project, 10L, "Older task", Instant.parse("2026-03-22T09:00:00Z"));
        TaskEntity latestTask = task(project, 11L, "Latest task", Instant.parse("2026-03-22T10:00:00Z"));
        TaskExecutionEntity olderExecution = execution(100L, olderTask, Instant.parse("2026-03-22T09:05:00Z"));
        TaskExecutionEntity latestExecution = execution(101L, latestTask, Instant.parse("2026-03-22T10:05:00Z"));

        when(projectRepository.findAll(Sort.by(Sort.Direction.ASC, "createdAt"))).thenReturn(List.of(project));
        when(taskRepository.findByProjectIdOrderByCreatedAtAsc(1L)).thenReturn(List.of(olderTask, latestTask));
        when(taskExecutionRepository.findFirstByTaskIdOrderByCreatedAtDesc(10L)).thenReturn(Optional.of(olderExecution));
        when(taskExecutionRepository.findFirstByTaskIdOrderByCreatedAtDesc(11L)).thenReturn(Optional.of(latestExecution));
        when(taskResponseMapper.toResponse(latestTask)).thenReturn(taskResponse(latestTask, "launch"));

        List<ProjectOverviewResponse> response = projectOverviewService.getOverview();

        assertEquals(1, response.size());
        assertEquals("Atenea", response.get(0).project().name());
        assertEquals("Latest task", response.get(0).latestTask().title());
        assertEquals("launch", response.get(0).latestTask().nextAction());
        assertEquals(101L, response.get(0).latestExecution().id());
    }

    @Test
    void getOverviewReturnsNullTaskAndExecutionWhenProjectHasNoTasks() {
        ProjectOverviewService projectOverviewService =
                new ProjectOverviewService(projectRepository, taskRepository, taskExecutionRepository, taskResponseMapper);
        ProjectEntity project = project(1L, "WAB", "/workspace/repos/internal/wab");

        when(projectRepository.findAll(Sort.by(Sort.Direction.ASC, "createdAt"))).thenReturn(List.of(project));
        when(taskRepository.findByProjectIdOrderByCreatedAtAsc(1L)).thenReturn(List.of());

        List<ProjectOverviewResponse> response = projectOverviewService.getOverview();

        assertNull(response.get(0).latestTask());
        assertNull(response.get(0).latestExecution());
    }

    private static ProjectEntity project(Long id, String name, String repoPath) {
        ProjectEntity project = new ProjectEntity();
        project.setId(id);
        project.setName(name);
        project.setDescription("desc");
        project.setRepoPath(repoPath);
        project.setCreatedAt(Instant.parse("2026-03-22T08:00:00Z"));
        project.setUpdatedAt(Instant.parse("2026-03-22T08:05:00Z"));
        return project;
    }

    private static TaskEntity task(ProjectEntity project, Long id, String title, Instant createdAt) {
        TaskEntity task = new TaskEntity();
        task.setId(id);
        task.setProject(project);
        task.setTitle(title);
        task.setDescription("desc");
        task.setBaseBranch("main");
        task.setBranchName("task/" + id + "-" + title.toLowerCase().replace(" ", "-"));
        task.setBranchStatus(TaskBranchStatus.PLANNED);
        task.setPullRequestUrl(null);
        task.setPullRequestStatus(TaskPullRequestStatus.NOT_CREATED);
        task.setReviewOutcome(TaskReviewOutcome.PENDING);
        task.setReviewNotes(null);
        task.setStatus(TaskStatus.PENDING);
        task.setPriority(TaskPriority.NORMAL);
        task.setCreatedAt(createdAt);
        task.setUpdatedAt(createdAt.plusSeconds(60));
        return task;
    }

    private static TaskExecutionEntity execution(Long id, TaskEntity task, Instant createdAt) {
        TaskExecutionEntity execution = new TaskExecutionEntity();
        execution.setId(id);
        execution.setTask(task);
        execution.setStatus(TaskExecutionStatus.SUCCEEDED);
        execution.setRunnerType(TaskExecutionRunnerType.CODEX);
        execution.setTargetRepoPath(task.getProject().getRepoPath());
        execution.setStartedAt(createdAt);
        execution.setFinishedAt(createdAt.plusSeconds(60));
        execution.setOutputSummary("ok");
        execution.setErrorSummary(null);
        execution.setExternalThreadId("thread");
        execution.setExternalTurnId("turn");
        execution.setCreatedAt(createdAt);
        return execution;
    }

    private static TaskResponse taskResponse(TaskEntity task, String nextAction) {
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
                false,
                false,
                false,
                true,
                "ready",
                "none",
                nextAction,
                "none",
                task.getStatus(),
                task.getPriority(),
                task.getCreatedAt(),
                task.getUpdatedAt()
        );
    }
}
