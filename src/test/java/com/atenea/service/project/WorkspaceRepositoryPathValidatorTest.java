package com.atenea.service.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceRepositoryPathValidatorTest {

    @TempDir
    Path tempDir;

    @Test
    void constructorThrowsWhenConfiguredWorkspaceRootIsRelative() {
        assertThrows(IllegalStateException.class, () -> new WorkspaceRepositoryPathValidator("repos"));
    }

    @Test
    void normalizeConfiguredRepoPathReturnsNormalizedAbsolutePath() throws IOException {
        Path workspaceRoot = Files.createDirectories(tempDir.resolve("repos"));
        Path repoPath = Files.createDirectories(workspaceRoot.resolve("internal/atenea/.git")).getParent();
        WorkspaceRepositoryPathValidator validator = new WorkspaceRepositoryPathValidator(workspaceRoot.toString());

        String normalizedPath = validator.normalizeConfiguredRepoPath(repoPath + "/./");

        assertEquals(repoPath.normalize().toString(), normalizedPath);
    }
}
