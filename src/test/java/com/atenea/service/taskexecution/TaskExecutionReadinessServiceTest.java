package com.atenea.service.taskexecution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.atenea.persistence.project.ProjectEntity;
import com.atenea.persistence.task.TaskEntity;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class TaskExecutionReadinessServiceTest {

    private final TaskExecutionReadinessService service = new TaskExecutionReadinessService();

    @Test
    void assessRejectsMissingDescription() {
        TaskExecutionReadiness readiness = service.assess(task(null));

        assertFalse(readiness.launchReady());
        assertEquals("task description is required for automatic execution", readiness.reason());
    }

    @Test
    void assessRejectsDiagnosticOnlyDescription() {
        TaskExecutionReadiness readiness = service.assess(task("Validar relanzamiento despues de abandon"));

        assertFalse(readiness.launchReady());
        assertEquals(
                "task description looks diagnostic or validation-only; specify the concrete change to make",
                readiness.reason());
    }

    @Test
    void assessAcceptsConcreteChangeDescription() {
        TaskExecutionReadiness readiness = service.assess(
                task("Implementa una validacion en TaskExecutionService para bloquear launch si la descripcion es ambigua."));

        assertTrue(readiness.launchReady());
        assertEquals("ready", readiness.reason());
    }

    private static TaskEntity task(String description) {
        ProjectEntity project = new ProjectEntity();
        project.setId(7L);
        project.setName("Atenea");
        project.setRepoPath("/workspace/repos/internal/atenea");
        project.setCreatedAt(Instant.now());
        project.setUpdatedAt(Instant.now());

        TaskEntity task = new TaskEntity();
        task.setId(42L);
        task.setProject(project);
        task.setTitle("Internal title");
        task.setDescription(description);
        return task;
    }
}
