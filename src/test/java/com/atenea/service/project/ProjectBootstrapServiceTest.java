package com.atenea.service.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.atenea.api.project.ProjectBootstrapResponse;
import com.atenea.persistence.project.ProjectEntity;
import com.atenea.persistence.project.ProjectRepository;
import com.atenea.service.git.GitRepositoryService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProjectBootstrapServiceTest {

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private GitRepositoryService gitRepositoryService;

    @TempDir
    Path tempDir;

    private ProjectBootstrapService projectBootstrapService;

    @BeforeEach
    void setUp() throws IOException {
        Path workspaceRoot = Files.createDirectories(tempDir.resolve("repos"));
        createGitRepo(workspaceRoot.resolve("internal/atenea"));
        createGitRepo(workspaceRoot.resolve("sandboxes/internal/atenea-preview"));
        createGitRepo(workspaceRoot.resolve("internal/wab"));
        createGitRepo(workspaceRoot.resolve("internal/fms"));
        createGitRepo(workspaceRoot.resolve("clients/isc"));
        createGitRepo(workspaceRoot.resolve("clients/rmc"));
        createGitRepo(workspaceRoot.resolve("clients/edi"));

        WorkspaceRepositoryPathValidator validator = new WorkspaceRepositoryPathValidator(workspaceRoot.toString());
        projectBootstrapService = new ProjectBootstrapService(projectRepository, validator, gitRepositoryService);

        lenient().when(projectRepository.findByName(any())).thenReturn(Optional.empty());
        lenient().when(projectRepository.findByRepoPath(any())).thenReturn(Optional.empty());
        lenient().when(gitRepositoryService.getCurrentBranch(any())).thenReturn("main");
        lenient().when(projectRepository.save(any(ProjectEntity.class))).thenAnswer(invocation -> {
            ProjectEntity entity = invocation.getArgument(0);
            entity.setId((long) entity.getName().hashCode());
            return entity;
        });
    }

    @Test
    void bootstrapCanonicalProjectsCreatesMissingProjects() {
        ProjectBootstrapResponse response = projectBootstrapService.bootstrapCanonicalProjects();

        assertEquals(7, response.createdProjects().size());
        assertEquals(0, response.existingProjects().size());
        assertEquals(0, response.skippedProjects().size());
        assertEquals("Atenea", response.createdProjects().get(0).name());
        assertEquals("main", response.createdProjects().get(0).defaultBaseBranch());
        assertEquals("Atenea Preview", response.createdProjects().get(1).name());
    }

    @Test
    void bootstrapCanonicalProjectsReturnsExistingProjectsWhenAlreadyRegistered() {
        when(projectRepository.findByName("Atenea")).thenReturn(Optional.of(project(
                10L,
                "Atenea",
                "Self-hosted Atenea source repository",
                tempDir.resolve("repos/internal/atenea").toString())));
        when(projectRepository.findByName("Atenea Preview")).thenReturn(Optional.of(project(
                11L,
                "Atenea Preview",
                "Sandbox repository used to validate Atenea changes safely",
                tempDir.resolve("repos/sandboxes/internal/atenea-preview").toString())));

        ProjectBootstrapResponse response = projectBootstrapService.bootstrapCanonicalProjects();

        assertEquals(5, response.createdProjects().size());
        assertEquals(2, response.existingProjects().size());
        assertEquals(0, response.skippedProjects().size());
        assertEquals("Atenea", response.existingProjects().get(0).name());
    }

    @Test
    void bootstrapCanonicalProjectsSkipsRepositoriesThatAreNotGitRepos() throws Exception {
        Files.delete(tempDir.resolve("repos/internal/fms/.git"));
        Files.delete(tempDir.resolve("repos/clients/isc/.git"));

        ProjectBootstrapResponse response = projectBootstrapService.bootstrapCanonicalProjects();

        assertEquals(5, response.createdProjects().size());
        assertEquals(0, response.existingProjects().size());
        assertEquals(2, response.skippedProjects().size());
        assertEquals("FMS", response.skippedProjects().get(0).name());
        assertEquals("ISC", response.skippedProjects().get(1).name());
    }

    @Test
    void bootstrapCanonicalProjectsThrowsWhenCanonicalNamePointsToDifferentPath() {
        when(projectRepository.findByName("Atenea")).thenReturn(Optional.of(project(
                10L,
                "Atenea",
                "desc",
                tempDir.resolve("repos/internal/wab").toString())));

        assertThrows(CanonicalProjectConflictException.class, () -> projectBootstrapService.bootstrapCanonicalProjects());
    }

    @Test
    void bootstrapCanonicalProjectsThrowsWhenCanonicalPathIsAlreadyRegisteredWithDifferentName() {
        when(projectRepository.findByRepoPath(tempDir.resolve("repos/internal/atenea").toString())).thenReturn(Optional.of(project(
                12L,
                "Unexpected",
                "desc",
                tempDir.resolve("repos/internal/atenea").toString())));

        assertThrows(CanonicalProjectConflictException.class, () -> projectBootstrapService.bootstrapCanonicalProjects());
    }

    private static void createGitRepo(Path repoPath) throws IOException {
        Files.createDirectories(repoPath.resolve(".git"));
    }

    private static ProjectEntity project(Long id, String name, String description, String repoPath) {
        ProjectEntity entity = new ProjectEntity();
        entity.setId(id);
        entity.setName(name);
        entity.setDescription(description);
        entity.setRepoPath(repoPath);
        entity.setDefaultBaseBranch("main");
        entity.setCreatedAt(Instant.parse("2026-03-22T10:00:00Z"));
        entity.setUpdatedAt(Instant.parse("2026-03-22T10:01:00Z"));
        return entity;
    }
}
