package com.atenea.service.taskexecution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.atenea.persistence.project.ProjectEntity;
import com.atenea.persistence.task.TaskEntity;
import com.atenea.persistence.task.TaskPriority;
import com.atenea.persistence.task.TaskStatus;
import com.atenea.persistence.taskexecution.TaskExecutionRunnerType;
import com.atenea.service.project.WorkspaceRepositoryPathValidator;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExecutionPlanningServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void createPlanUsesProjectRepoPathAndCodexRunner() throws IOException {
        Path workspaceRoot = Files.createDirectories(tempDir.resolve("repos"));
        createGitRepo(workspaceRoot.resolve("internal/wab"));
        ExecutionPlanningService executionPlanningService =
                new ExecutionPlanningService(new WorkspaceRepositoryPathValidator(workspaceRoot.toString()));
        TaskEntity task = buildTask("WAB", workspaceRoot.resolve("internal/wab").toString());

        ExecutionPlan plan = executionPlanningService.createPlan(task);

        assertEquals(TaskExecutionRunnerType.CODEX, plan.runnerType());
        assertEquals(workspaceRoot.resolve("internal/wab").toString(), plan.targetRepoPath());
        assertTrue(plan.prompt().contains("Validate non-existent in-container repoPath handling."));
        assertTrue(plan.prompt().contains("Treat the task description below as the only requested scope."));
        assertTrue(plan.prompt().contains("If the task wording is ambiguous"));
        assertTrue(!plan.prompt().contains("Task title:"));
        assertTrue(!plan.prompt().contains("Task description:"));
        assertEquals(ExecutionTargetType.STANDARD, plan.targetType());
        assertNull(plan.planningError());
    }

    @Test
    void createPlanReturnsPlanningErrorWhenRepoPathIsBlank() throws IOException {
        Path workspaceRoot = Files.createDirectories(tempDir.resolve("repos"));
        ExecutionPlanningService executionPlanningService =
                new ExecutionPlanningService(new WorkspaceRepositoryPathValidator(workspaceRoot.toString()));
        TaskEntity task = buildTask("demo", "   ");

        ExecutionPlan plan = executionPlanningService.createPlan(task);

        assertNull(plan.targetRepoPath());
        assertEquals("Project repoPath is not configured", plan.planningError());
    }

    @Test
    void createPlanMarksPreviewTargetsAndAddsPreviewInstructions() throws IOException {
        Path workspaceRoot = Files.createDirectories(tempDir.resolve("repos"));
        createGitRepo(workspaceRoot.resolve("sandboxes/internal/atenea-preview"));
        ExecutionPlanningService executionPlanningService =
                new ExecutionPlanningService(new WorkspaceRepositoryPathValidator(workspaceRoot.toString()));
        TaskEntity task = buildTask("Atenea Preview", workspaceRoot.resolve("sandboxes/internal/atenea-preview").toString());

        ExecutionPlan plan = executionPlanningService.createPlan(task);

        assertEquals(ExecutionTargetType.PREVIEW, plan.targetType());
        assertTrue(plan.prompt().contains("This target is a preview sandbox."));
        assertNull(plan.planningError());
    }

    @Test
    void createPlanRejectsPreviewProjectOutsideSandbox() throws IOException {
        Path workspaceRoot = Files.createDirectories(tempDir.resolve("repos"));
        createGitRepo(workspaceRoot.resolve("internal/atenea"));
        ExecutionPlanningService executionPlanningService =
                new ExecutionPlanningService(new WorkspaceRepositoryPathValidator(workspaceRoot.toString()));
        TaskEntity task = buildTask("Atenea Preview", workspaceRoot.resolve("internal/atenea").toString());

        ExecutionPlan plan = executionPlanningService.createPlan(task);

        assertEquals(ExecutionTargetType.STANDARD, plan.targetType());
        assertTrue(plan.planningError().contains("must target a sandbox repository"));
    }

    private static TaskEntity buildTask(String projectName, String repoPath) {
        ProjectEntity project = new ProjectEntity();
        project.setId(7L);
        project.setName(projectName);
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

    private static void createGitRepo(Path repoPath) throws IOException {
        Files.createDirectories(repoPath.resolve(".git"));
    }
}
