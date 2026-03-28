package com.atenea.service.project;

import com.atenea.api.project.ProjectBootstrapResponse;
import com.atenea.api.project.ProjectBootstrapSkippedProjectResponse;
import com.atenea.api.project.ProjectResponse;
import com.atenea.persistence.project.ProjectEntity;
import com.atenea.persistence.project.ProjectRepository;
import com.atenea.service.git.GitRepositoryService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProjectBootstrapService {

    private final ProjectRepository projectRepository;
    private final WorkspaceRepositoryPathValidator workspaceRepositoryPathValidator;
    private final GitRepositoryService gitRepositoryService;

    public ProjectBootstrapService(
            ProjectRepository projectRepository,
            WorkspaceRepositoryPathValidator workspaceRepositoryPathValidator,
            GitRepositoryService gitRepositoryService
    ) {
        this.projectRepository = projectRepository;
        this.workspaceRepositoryPathValidator = workspaceRepositoryPathValidator;
        this.gitRepositoryService = gitRepositoryService;
    }

    @Transactional
    public ProjectBootstrapResponse bootstrapCanonicalProjects() {
        List<ProjectResponse> createdProjects = new ArrayList<>();
        List<ProjectResponse> existingProjects = new ArrayList<>();
        List<ProjectBootstrapSkippedProjectResponse> skippedProjects = new ArrayList<>();

        for (CanonicalProjectDefinition definition : canonicalDefinitions()) {
            String canonicalRepoPath;
            try {
                canonicalRepoPath = workspaceRepositoryPathValidator.normalizeConfiguredRepoPath(definition.repoPath());
            } catch (ProjectRepoPathOutsideWorkspaceException
                     | ProjectRepoPathNotFoundException
                     | ProjectRepoPathNotDirectoryException
                     | ProjectRepoPathMissingGitDirectoryException exception) {
                skippedProjects.add(new ProjectBootstrapSkippedProjectResponse(
                        definition.name(),
                        definition.description(),
                        definition.repoPath(),
                        exception.getMessage()
                ));
                continue;
            }
            Optional<ProjectEntity> existingByName = projectRepository.findByName(definition.name());
            Optional<ProjectEntity> existingByRepoPath = projectRepository.findByRepoPath(canonicalRepoPath);

            if (existingByName.isPresent()) {
                ProjectEntity existingProject = existingByName.get();
                String existingRepoPath = workspaceRepositoryPathValidator
                        .normalizeConfiguredRepoPath(existingProject.getRepoPath());
                if (!canonicalRepoPath.equals(existingRepoPath)) {
                    throw new CanonicalProjectConflictException("Canonical project '" + definition.name()
                            + "' already exists with repoPath '" + existingProject.getRepoPath()
                            + "' but expected '" + canonicalRepoPath + "'");
                }
                existingProjects.add(toResponse(existingProject));
                continue;
            }

            if (existingByRepoPath.isPresent()) {
                ProjectEntity existingProject = existingByRepoPath.get();
                throw new CanonicalProjectConflictException("Canonical repoPath '" + canonicalRepoPath
                        + "' is already registered as project '" + existingProject.getName() + "'");
            }

            Instant now = Instant.now();
            ProjectEntity project = new ProjectEntity();
            project.setName(definition.name());
            project.setDescription(definition.description());
            project.setRepoPath(canonicalRepoPath);
            project.setDefaultBaseBranch(gitRepositoryService.getCurrentBranch(canonicalRepoPath));
            project.setCreatedAt(now);
            project.setUpdatedAt(now);
            createdProjects.add(toResponse(projectRepository.save(project)));
        }

        return new ProjectBootstrapResponse(createdProjects, existingProjects, skippedProjects);
    }

    private List<CanonicalProjectDefinition> canonicalDefinitions() {
        String workspaceRoot = workspaceRepositoryPathValidator.getWorkspaceRoot().toString();
        return List.of(
                new CanonicalProjectDefinition("Atenea", "Self-hosted Atenea source repository", workspaceRoot + "/internal/atenea"),
                new CanonicalProjectDefinition(
                        "Atenea Preview",
                        "Sandbox repository used to validate Atenea changes safely",
                        workspaceRoot + "/sandboxes/internal/atenea-preview"),
                new CanonicalProjectDefinition("WAB", "Internal WAB development repository", workspaceRoot + "/internal/wab"),
                new CanonicalProjectDefinition("FMS", "Internal FMS development repository", workspaceRoot + "/internal/fms"),
                new CanonicalProjectDefinition("ISC", "Client ISC development repository", workspaceRoot + "/clients/isc"),
                new CanonicalProjectDefinition("RMC", "Client RMC development repository", workspaceRoot + "/clients/rmc"),
                new CanonicalProjectDefinition("EDI", "Client EDI development repository", workspaceRoot + "/clients/edi")
        );
    }

    private ProjectResponse toResponse(ProjectEntity project) {
        return new ProjectResponse(
                project.getId(),
                project.getName(),
                project.getDescription(),
                project.getRepoPath(),
                project.getDefaultBaseBranch(),
                project.getCreatedAt(),
                project.getUpdatedAt()
        );
    }

    private record CanonicalProjectDefinition(
            String name,
            String description,
            String repoPath
    ) {
    }
}
