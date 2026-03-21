package com.atenea.service.task;

import com.atenea.api.task.CreateTaskRequest;
import com.atenea.api.task.TaskResponse;
import com.atenea.persistence.project.ProjectEntity;
import com.atenea.persistence.project.ProjectRepository;
import com.atenea.persistence.task.TaskEntity;
import com.atenea.persistence.task.TaskRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TaskService {

    private final ProjectRepository projectRepository;
    private final TaskRepository taskRepository;

    public TaskService(ProjectRepository projectRepository, TaskRepository taskRepository) {
        this.projectRepository = projectRepository;
        this.taskRepository = taskRepository;
    }

    @Transactional(readOnly = true)
    public List<TaskResponse> getTasks(Long projectId) {
        ensureProjectExists(projectId);

        return taskRepository.findByProjectIdOrderByCreatedAtAsc(projectId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public TaskResponse createTask(Long projectId, CreateTaskRequest request) {
        ProjectEntity project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ProjectNotFoundException(projectId));

        String taskTitle = request.title().trim();
        String taskDescription = normalizeNullableText(request.description());

        taskRepository.findByProjectIdAndTitle(projectId, taskTitle)
                .ifPresent(task -> {
                    throw new DuplicateTaskTitleException(projectId, taskTitle);
                });

        Instant now = Instant.now();

        TaskEntity task = new TaskEntity();
        task.setProject(project);
        task.setTitle(taskTitle);
        task.setDescription(taskDescription);
        task.setStatus(request.status());
        task.setPriority(request.priority());
        task.setCreatedAt(now);
        task.setUpdatedAt(now);

        return toResponse(taskRepository.save(task));
    }

    private void ensureProjectExists(Long projectId) {
        if (!projectRepository.existsById(projectId)) {
            throw new ProjectNotFoundException(projectId);
        }
    }

    private TaskResponse toResponse(TaskEntity task) {
        return new TaskResponse(
                task.getId(),
                task.getProject().getId(),
                task.getTitle(),
                task.getDescription(),
                task.getStatus(),
                task.getPriority(),
                task.getCreatedAt(),
                task.getUpdatedAt()
        );
    }

    private static String normalizeNullableText(String value) {
        if (value == null) {
            return null;
        }

        String normalizedValue = value.trim();
        return normalizedValue.isEmpty() ? null : normalizedValue;
    }
}
