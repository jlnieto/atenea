package com.atenea.service.task;

import com.atenea.api.task.CreateTaskRequest;
import com.atenea.api.task.TaskResponse;
import com.atenea.persistence.project.ProjectEntity;
import com.atenea.persistence.project.ProjectRepository;
import com.atenea.persistence.task.TaskBranchStatus;
import com.atenea.persistence.task.TaskEntity;
import com.atenea.persistence.task.TaskPullRequestStatus;
import com.atenea.persistence.task.TaskReviewOutcome;
import com.atenea.persistence.task.TaskRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TaskService {

    private static final String DEFAULT_BASE_BRANCH = "main";

    private final ProjectRepository projectRepository;
    private final TaskRepository taskRepository;
    private final JdbcTemplate jdbcTemplate;
    private final TaskResponseMapper taskResponseMapper;

    public TaskService(
            ProjectRepository projectRepository,
            TaskRepository taskRepository,
            JdbcTemplate jdbcTemplate,
            TaskResponseMapper taskResponseMapper
    ) {
        this.projectRepository = projectRepository;
        this.taskRepository = taskRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.taskResponseMapper = taskResponseMapper;
    }

    @Transactional(readOnly = true)
    public List<TaskResponse> getTasks(Long projectId) {
        ensureProjectExists(projectId);

        return taskRepository.findByProjectIdOrderByCreatedAtAsc(projectId)
                .stream()
                .map(taskResponseMapper::toResponse)
                .toList();
    }

    @Transactional
    public TaskResponse createTask(Long projectId, CreateTaskRequest request) {
        ProjectEntity project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ProjectNotFoundException(projectId));

        String taskTitle = request.title().trim();
        String taskDescription = normalizeNullableText(request.description());
        String baseBranch = normalizeBaseBranch(request.baseBranch());

        taskRepository.findByProjectIdAndTitle(projectId, taskTitle)
                .ifPresent(task -> {
                    throw new DuplicateTaskTitleException(projectId, taskTitle);
                });

        Instant now = Instant.now();

        TaskEntity task = new TaskEntity();
        Long taskId = taskRepository.nextTaskId();
        task.setId(taskId);
        task.setProject(project);
        task.setTitle(taskTitle);
        task.setDescription(taskDescription);
        task.setBaseBranch(baseBranch);
        task.setBranchName(buildBranchName(taskId, taskTitle));
        task.setBranchStatus(TaskBranchStatus.PLANNED);
        task.setPullRequestUrl(null);
        task.setPullRequestStatus(TaskPullRequestStatus.NOT_CREATED);
        task.setReviewOutcome(TaskReviewOutcome.PENDING);
        task.setReviewNotes(null);
        task.setStatus(request.status());
        task.setPriority(request.priority());
        task.setCreatedAt(now);
        task.setUpdatedAt(now);

        jdbcTemplate.update("""
                insert into task (
                    id,
                    project_id,
                    title,
                    description,
                    base_branch,
                    branch_name,
                    branch_status,
                    pull_request_url,
                    pull_request_status,
                    review_outcome,
                    review_notes,
                    status,
                    priority,
                    created_at,
                    updated_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                task.getId(),
                project.getId(),
                task.getTitle(),
                task.getDescription(),
                task.getBaseBranch(),
                task.getBranchName(),
                task.getBranchStatus().name(),
                task.getPullRequestUrl(),
                task.getPullRequestStatus().name(),
                task.getReviewOutcome().name(),
                task.getReviewNotes(),
                task.getStatus().name(),
                task.getPriority().name(),
                Timestamp.from(task.getCreatedAt()),
                Timestamp.from(task.getUpdatedAt()));

        TaskEntity persistedTask = taskRepository.findWithProjectById(taskId)
                .orElseThrow(() -> new IllegalStateException("Task '" + taskId + "' was inserted but could not be reloaded"));
        return taskResponseMapper.toResponse(persistedTask);
    }

    private void ensureProjectExists(Long projectId) {
        if (!projectRepository.existsById(projectId)) {
            throw new ProjectNotFoundException(projectId);
        }
    }

    private static String normalizeNullableText(String value) {
        if (value == null) {
            return null;
        }

        String normalizedValue = value.trim();
        return normalizedValue.isEmpty() ? null : normalizedValue;
    }

    private static String normalizeBaseBranch(String value) {
        String normalizedValue = normalizeNullableText(value);
        return normalizedValue == null ? DEFAULT_BASE_BRANCH : normalizedValue;
    }

    private static String buildBranchName(Long taskId, String taskTitle) {
        if (taskId == null) {
            throw new IllegalStateException("Task id must be assigned before generating branchName");
        }

        String slug = taskTitle.toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+|-+$)", "")
                .replaceAll("-{2,}", "-");
        if (slug.isBlank()) {
            slug = "task";
        }
        if (slug.length() > 80) {
            slug = slug.substring(0, 80).replaceAll("-+$", "");
        }

        return "task/" + taskId + "-" + slug;
    }
}
