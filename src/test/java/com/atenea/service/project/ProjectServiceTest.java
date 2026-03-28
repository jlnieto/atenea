package com.atenea.service.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.atenea.api.project.CreateProjectRequest;
import com.atenea.api.project.ProjectResponse;
import com.atenea.persistence.project.ProjectEntity;
import com.atenea.persistence.project.ProjectRepository;
import com.atenea.service.taskexecution.GitRepositoryService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Sort;

@ExtendWith(MockitoExtension.class)
class ProjectServiceTest {

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private GitRepositoryService gitRepositoryService;

    @TempDir
    Path tempDir;

    private ProjectService projectService;
    private WorkspaceRepositoryPathValidator workspaceRepositoryPathValidator;
    private Path defaultWorkspaceRoot;

    @BeforeEach
    void setUp() throws IOException {
        defaultWorkspaceRoot = Files.createDirectories(tempDir.resolve("repos"));
        workspaceRepositoryPathValidator = new WorkspaceRepositoryPathValidator(defaultWorkspaceRoot.toString());
        projectService = new ProjectService(projectRepository, workspaceRepositoryPathValidator, gitRepositoryService);
    }

    @Test
    void createProjectDefaultsWorkspaceRootNormalizesFieldsAndPersists() throws IOException {
        Path repoPath = createGitRepo(defaultWorkspaceRoot.resolve("internal/atenea"));

        when(projectRepository.findByName("Atenea")).thenReturn(Optional.empty());
        when(gitRepositoryService.getCurrentBranch(repoPath.toString())).thenReturn("main");
        when(projectRepository.save(any(ProjectEntity.class))).thenAnswer(invocation -> {
            ProjectEntity entity = invocation.getArgument(0);
            entity.setId(10L);
            return entity;
        });

        ProjectResponse response = projectService.createProject(new CreateProjectRequest(
                "  Atenea  ",
                "   Backend orchestration  ",
                "   " + repoPath + "   ",
                null));

        assertEquals(10L, response.id());
        assertEquals("Atenea", response.name());
        assertEquals("Backend orchestration", response.description());
        assertEquals(repoPath.toString(), response.repoPath());
        assertEquals("main", response.defaultBaseBranch());

        ArgumentCaptor<ProjectEntity> captor = ArgumentCaptor.forClass(ProjectEntity.class);
        verify(projectRepository).save(captor.capture());
        ProjectEntity saved = captor.getValue();
        assertEquals(repoPath.toString(), saved.getRepoPath());
        assertEquals("main", saved.getDefaultBaseBranch());
    }

    @Test
    void createProjectUsesProvidedDefaultBaseBranchWhenPresent() throws IOException {
        Path repoPath = createGitRepo(defaultWorkspaceRoot.resolve("internal/atenea"));

        when(projectRepository.findByName("Atenea")).thenReturn(Optional.empty());
        when(projectRepository.save(any(ProjectEntity.class))).thenAnswer(invocation -> {
            ProjectEntity entity = invocation.getArgument(0);
            entity.setId(10L);
            return entity;
        });

        ProjectResponse response = projectService.createProject(new CreateProjectRequest(
                "Atenea",
                "Backend orchestration",
                repoPath.toString(),
                " release/2026-q2 "));

        assertEquals("release/2026-q2", response.defaultBaseBranch());
    }

    @Test
    void createProjectThrowsWhenRepoPathIsOutsideWorkspace() throws IOException {
        Path otherRoot = Files.createDirectories(tempDir.resolve("outside"));
        Path repoPath = createGitRepo(otherRoot.resolve("rogue"));

        assertThrows(ProjectRepoPathOutsideWorkspaceException.class, () -> projectService.createProject(
                new CreateProjectRequest("Atenea", null, repoPath.toString(), null)));
    }

    @Test
    void createProjectThrowsWhenRepoPathDoesNotExist() {
        Path missingRepoPath = defaultWorkspaceRoot.resolve("internal/missing");

        assertThrows(ProjectRepoPathNotFoundException.class, () -> projectService.createProject(
                new CreateProjectRequest("Atenea", null, missingRepoPath.toString(), null)));
    }

    @Test
    void createProjectThrowsWhenRepoPathLacksGitDirectory() throws IOException {
        Path repoPath = Files.createDirectories(defaultWorkspaceRoot.resolve("internal/no-git"));

        assertThrows(ProjectRepoPathMissingGitDirectoryException.class, () -> projectService.createProject(
                new CreateProjectRequest("Atenea", null, repoPath.toString(), null)));
    }

    @Test
    void createProjectThrowsWhenNameAlreadyExists() throws IOException {
        Path repoPath = createGitRepo(defaultWorkspaceRoot.resolve("internal/atenea"));
        when(projectRepository.findByName("Atenea")).thenReturn(Optional.of(new ProjectEntity()));

        assertThrows(DuplicateProjectNameException.class, () -> projectService.createProject(
                new CreateProjectRequest("  Atenea  ", null, repoPath.toString(), null)));
    }

    @Test
    void getProjectsReturnsMappedResponsesInRepositoryOrder() {
        ProjectEntity older = buildProject(
                1L,
                "Alpha",
                null,
                "/repos/internal/alpha",
                Instant.parse("2026-01-01T10:00:00Z"));
        ProjectEntity newer = buildProject(
                2L,
                "Beta",
                "desc",
                "/repos/clients/acme/backend",
                Instant.parse("2026-01-02T10:00:00Z"));

        when(projectRepository.findAll(Sort.by(Sort.Direction.ASC, "createdAt"))).thenReturn(List.of(older, newer));

        List<ProjectResponse> response = projectService.getProjects();

        assertEquals(List.of(1L, 2L), response.stream().map(ProjectResponse::id).toList());
        assertEquals("/repos/internal/alpha", response.get(0).repoPath());
        assertEquals("desc", response.get(1).description());
        assertEquals("main", response.get(0).defaultBaseBranch());
    }

    private static ProjectEntity buildProject(
            Long id,
            String name,
            String description,
            String repoPath,
            Instant createdAt) {
        ProjectEntity entity = new ProjectEntity();
        entity.setId(id);
        entity.setName(name);
        entity.setDescription(description);
        entity.setRepoPath(repoPath);
        entity.setDefaultBaseBranch("main");
        entity.setCreatedAt(createdAt);
        entity.setUpdatedAt(createdAt.plusSeconds(60));
        return entity;
    }

    private static Path createGitRepo(Path repoPath) throws IOException {
        Files.createDirectories(repoPath.resolve(".git"));
        return repoPath;
    }
}
