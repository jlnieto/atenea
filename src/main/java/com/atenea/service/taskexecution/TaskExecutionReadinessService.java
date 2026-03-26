package com.atenea.service.taskexecution;

import com.atenea.persistence.task.TaskEntity;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class TaskExecutionReadinessService {

    private static final Set<String> ACTIONABLE_TERMS = Set.of(
            "add", "adjust", "block", "change", "create", "document", "expose", "fix", "implement",
            "prevent", "refactor", "remove", "rename", "return", "support", "update",
            "anade", "añade", "ajusta", "bloquea", "cambia", "corrige", "crea", "documenta",
            "evita", "expone", "implementa", "refactoriza", "renombra", "soporta", "actualiza"
    );

    private static final Set<String> DIAGNOSTIC_TERMS = Set.of(
            "analyze", "check", "diagnose", "inspect", "investigate", "look", "prove", "review",
            "test", "validate", "verify",
            "analiza", "comprueba", "comprobar", "diagnostica", "inspecciona", "investiga",
            "mira", "prueba", "revisa", "valida", "verifica"
    );

    private static final Set<String> ARTIFACT_HINTS = Set.of(
            "/", ".java", ".kt", ".ts", ".tsx", ".js", ".jsx", ".sql", ".md", ".yml", ".yaml",
            "api", "branch", "class", "column", "controller", "endpoint", "field", "file", "method",
            "migration", "module", "repo", "repository", "route", "service", "table", "test",
            "archivo", "campo", "clase", "columna", "controlador", "fichero",
            "metodo", "migracion", "módulo", "modulo", "prueba", "repositorio", "ruta", "servicio", "tabla"
    );

    public TaskExecutionReadiness assess(TaskEntity task) {
        String description = normalize(task.getDescription());
        if (description == null) {
            return new TaskExecutionReadiness(false,
                    "task description is required for automatic execution");
        }

        String[] words = description.split("\\s+");
        if (words.length < 4 || description.length() < 20) {
            return new TaskExecutionReadiness(false,
                    "task description is too short; describe the concrete repository change to perform");
        }

        String normalized = stripAccents(description).toLowerCase(Locale.ROOT);
        boolean hasActionableIntent = ACTIONABLE_TERMS.stream().anyMatch(normalized::contains);
        boolean hasDiagnosticIntent = DIAGNOSTIC_TERMS.stream().anyMatch(normalized::contains);
        boolean hasArtifactHint = ARTIFACT_HINTS.stream().anyMatch(normalized::contains);

        if (hasDiagnosticIntent && !hasActionableIntent) {
            return new TaskExecutionReadiness(false,
                    "task description looks diagnostic or validation-only; specify the concrete change to make");
        }

        if (!hasActionableIntent) {
            return new TaskExecutionReadiness(false,
                    "task description must describe a concrete change, not only a generic goal");
        }

        if (hasDiagnosticIntent && !hasArtifactHint) {
            return new TaskExecutionReadiness(false,
                    "task description looks diagnostic or validation-only; specify the concrete change to make");
        }

        return new TaskExecutionReadiness(true, "ready");
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private static String stripAccents(String value) {
        return value
                .replace('á', 'a')
                .replace('é', 'e')
                .replace('í', 'i')
                .replace('ó', 'o')
                .replace('ú', 'u')
                .replace('Á', 'A')
                .replace('É', 'E')
                .replace('Í', 'I')
                .replace('Ó', 'O')
                .replace('Ú', 'U');
    }
}
