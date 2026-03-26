package com.atenea.service.project;

import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class WorkspaceRepositoryPathValidator {

    private final Path workspaceRoot;

    public WorkspaceRepositoryPathValidator(@Value("${atenea.workspace-root:/repos}") String configuredWorkspaceRoot) {
        this.workspaceRoot = normalizeWorkspaceRoot(configuredWorkspaceRoot);
    }

    public String normalizeConfiguredRepoPath(String repoPath) {
        String normalizedRepoPath = normalizeRequiredText(repoPath);
        validateExistingRepositoryPath(workspaceRoot, normalizedRepoPath);
        return Path.of(normalizedRepoPath).normalize().toString();
    }

    public Path getWorkspaceRoot() {
        return workspaceRoot;
    }

    public String normalizeNullableText(String value) {
        if (value == null) {
            return null;
        }

        String normalizedValue = value.trim();
        return normalizedValue.isEmpty() ? null : normalizedValue;
    }

    private static Path normalizeWorkspaceRoot(String value) {
        String workspaceRoot = normalizeNullableTextStatic(value);
        if (workspaceRoot == null) {
            throw new IllegalStateException("Configured workspace root must not be blank");
        }

        Path normalizedWorkspaceRoot = Path.of(workspaceRoot).normalize();
        if (!normalizedWorkspaceRoot.isAbsolute()) {
            throw new IllegalStateException("Configured workspace root '" + workspaceRoot + "' must be an absolute path");
        }

        return normalizedWorkspaceRoot;
    }

    private static String normalizeRequiredText(String value) {
        String normalizedValue = normalizeNullableTextStatic(value);
        if (normalizedValue == null) {
            throw new IllegalArgumentException("Required text value is blank");
        }

        return normalizedValue;
    }

    private static void validateExistingRepositoryPath(Path workspaceRoot, String repoPath) {
        Path normalizedRepoPath = Path.of(repoPath).normalize();

        if (!normalizedRepoPath.isAbsolute() || !normalizedRepoPath.startsWith(workspaceRoot)) {
            throw new ProjectRepoPathOutsideWorkspaceException(repoPath, workspaceRoot.toString());
        }

        if (normalizedRepoPath.equals(workspaceRoot)) {
            throw new ProjectRepoPathOutsideWorkspaceException(repoPath, workspaceRoot.toString());
        }

        if (!Files.exists(normalizedRepoPath)) {
            throw new ProjectRepoPathNotFoundException(repoPath);
        }

        if (!Files.isDirectory(normalizedRepoPath)) {
            throw new ProjectRepoPathNotDirectoryException(repoPath);
        }

        if (!Files.isDirectory(normalizedRepoPath.resolve(".git"))) {
            throw new ProjectRepoPathMissingGitDirectoryException(repoPath);
        }
    }

    private static String normalizeNullableTextStatic(String value) {
        if (value == null) {
            return null;
        }

        String normalizedValue = value.trim();
        return normalizedValue.isEmpty() ? null : normalizedValue;
    }
}
