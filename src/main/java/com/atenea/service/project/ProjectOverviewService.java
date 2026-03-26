package com.atenea.service.project;

import com.atenea.api.project.ProjectOverviewResponse;
import com.atenea.api.project.ProjectResponse;
import com.atenea.api.task.TaskResponse;
import com.atenea.api.taskexecution.TaskExecutionResponse;
import com.atenea.service.task.TaskResponseMapper;
import com.atenea.persistence.project.ProjectEntity;
import com.atenea.persistence.project.ProjectRepository;
import com.atenea.persistence.task.TaskEntity;
import com.atenea.persistence.task.TaskRepository;
import com.atenea.persistence.taskexecution.TaskExecutionEntity;
import com.atenea.persistence.taskexecution.TaskExecutionRepository;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProjectOverviewService {

    private final ProjectRepository projectRepository;
    private final TaskRepository taskRepository;
    private final TaskExecutionRepository taskExecutionRepository;
    private final TaskResponseMapper taskResponseMapper;

    public ProjectOverviewService(
            ProjectRepository projectRepository,
            TaskRepository taskRepository,
            TaskExecutionRepository taskExecutionRepository,
            TaskResponseMapper taskResponseMapper
    ) {
        this.projectRepository = projectRepository;
        this.taskRepository = taskRepository;
        this.taskExecutionRepository = taskExecutionRepository;
        this.taskResponseMapper = taskResponseMapper;
    }

    @Transactional(readOnly = true)
    public List<ProjectOverviewResponse> getOverview() {
        return projectRepository.findAll(Sort.by(Sort.Direction.ASC, "createdAt"))
                .stream()
                .map(this::toOverview)
                .toList();
    }

    private ProjectOverviewResponse toOverview(ProjectEntity project) {
        List<TaskEntity> tasks = taskRepository.findByProjectIdOrderByCreatedAtAsc(project.getId());
        TaskEntity latestTaskEntity = tasks.stream()
                .max(Comparator.comparing(TaskEntity::getCreatedAt))
                .orElse(null);

        TaskExecutionEntity latestExecutionEntity = tasks.stream()
                .map(TaskEntity::getId)
                .map(taskExecutionRepository::findFirstByTaskIdOrderByCreatedAtDesc)
                .flatMap(Optional::stream)
                .max(Comparator.comparing(TaskExecutionEntity::getCreatedAt))
                .orElse(null);

        return new ProjectOverviewResponse(
                toProjectResponse(project),
                latestTaskEntity == null ? null : toTaskResponse(latestTaskEntity),
                latestExecutionEntity == null ? null : toTaskExecutionResponse(latestExecutionEntity)
        );
    }

    private ProjectResponse toProjectResponse(ProjectEntity project) {
        return new ProjectResponse(
                project.getId(),
                project.getName(),
                project.getDescription(),
                project.getRepoPath(),
                project.getCreatedAt(),
                project.getUpdatedAt()
        );
    }

    private TaskResponse toTaskResponse(TaskEntity task) {
        return taskResponseMapper.toResponse(task);
    }

    private TaskExecutionResponse toTaskExecutionResponse(TaskExecutionEntity taskExecution) {
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
}
