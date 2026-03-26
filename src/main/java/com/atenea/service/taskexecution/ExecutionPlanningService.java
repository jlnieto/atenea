package com.atenea.service.taskexecution;

import com.atenea.persistence.task.TaskEntity;
import com.atenea.persistence.taskexecution.TaskExecutionRunnerType;
import com.atenea.service.project.ProjectRepoPathMissingGitDirectoryException;
import com.atenea.service.project.ProjectRepoPathNotDirectoryException;
import com.atenea.service.project.ProjectRepoPathNotFoundException;
import com.atenea.service.project.ProjectRepoPathOutsideWorkspaceException;
import com.atenea.service.project.WorkspaceRepositoryPathValidator;
import org.springframework.stereotype.Service;

@Service
public class ExecutionPlanningService {

    private static final String STANDARD_SYSTEM_INSTRUCTIONS = """
            Work carefully and keep the change set small.
            Do not expand scope.
            Operate only within the current repository path.
            Keep the implementation minimal, production-clean, and easy to extend.
            Treat the task description below as the only requested scope.
            Do not infer extra product goals from generic words like retry, unlock, relaunch, review, or integration.
            If the task wording is ambiguous and does not clearly map to a concrete repository change, stop after minimal inspection and report the ambiguity instead of inventing unrelated code changes.
            If the task is only diagnostic or validation-oriented, prefer safe inspection and explanation over speculative edits.
            """;

    private static final String PREVIEW_INSTRUCTIONS = """
            This target is a preview sandbox.
            Do not touch production repositories, production services, or production data.
            """;

    private final WorkspaceRepositoryPathValidator workspaceRepositoryPathValidator;

    public ExecutionPlanningService(WorkspaceRepositoryPathValidator workspaceRepositoryPathValidator) {
        this.workspaceRepositoryPathValidator = workspaceRepositoryPathValidator;
    }

    public ExecutionPlan createPlan(TaskEntity task) {
        String rawRepoPath = workspaceRepositoryPathValidator.normalizeNullableText(task.getProject().getRepoPath());
        if (rawRepoPath == null) {
            return failedPlan(task, "Project repoPath is not configured");
        }

        String normalizedRepoPath;
        try {
            normalizedRepoPath = workspaceRepositoryPathValidator.normalizeConfiguredRepoPath(rawRepoPath);
        } catch (ProjectRepoPathOutsideWorkspaceException
                 | ProjectRepoPathNotFoundException
                 | ProjectRepoPathNotDirectoryException
                 | ProjectRepoPathMissingGitDirectoryException exception) {
            return failedPlan(task, exception.getMessage());
        }

        ExecutionTargetType targetType = classifyTarget(task, normalizedRepoPath);
        if (isPreviewProject(task) && !isSandboxRepoPath(normalizedRepoPath)) {
            return failedPlan(task, "Preview project '" + task.getProject().getName()
                    + "' must target a sandbox repository under '"
                    + workspaceRepositoryPathValidator.getWorkspaceRoot().resolve("sandboxes") + "'");
        }

        return new ExecutionPlan(
                task,
                TaskExecutionRunnerType.CODEX,
                normalizedRepoPath,
                buildPrompt(task, targetType),
                null,
                targetType
        );
    }

    private static String buildPrompt(TaskEntity task, ExecutionTargetType targetType) {
        StringBuilder prompt = new StringBuilder();
        prompt.append(STANDARD_SYSTEM_INSTRUCTIONS.trim())
                .append(System.lineSeparator());

        if (targetType == ExecutionTargetType.PREVIEW) {
            prompt.append(System.lineSeparator())
                    .append(PREVIEW_INSTRUCTIONS.trim())
                    .append(System.lineSeparator());
        }

        prompt.append(System.lineSeparator())
                .append(task.getDescription() == null ? "" : task.getDescription().trim());

        if (task.getDescription() == null || task.getDescription().isBlank()) {
            prompt.append("No task description was provided.");
        }

        return prompt.toString();
    }

    private ExecutionTargetType classifyTarget(TaskEntity task, String normalizedRepoPath) {
        if (isSandboxRepoPath(normalizedRepoPath) || isPreviewProject(task)) {
            return ExecutionTargetType.PREVIEW;
        }
        return ExecutionTargetType.STANDARD;
    }

    private boolean isSandboxRepoPath(String normalizedRepoPath) {
        String sandboxRoot = workspaceRepositoryPathValidator.getWorkspaceRoot().resolve("sandboxes").toString();
        return normalizedRepoPath.startsWith(sandboxRoot);
    }

    private static boolean isPreviewProject(TaskEntity task) {
        String projectName = task.getProject().getName();
        return projectName != null && projectName.trim().equalsIgnoreCase("Atenea Preview");
    }

    private static ExecutionPlan failedPlan(TaskEntity task, String planningError) {
        return new ExecutionPlan(
                task,
                TaskExecutionRunnerType.CODEX,
                null,
                null,
                planningError,
                ExecutionTargetType.STANDARD
        );
    }
}
