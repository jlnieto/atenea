package com.atenea.service.project;

import com.atenea.api.project.CreateProjectRequest;
import com.atenea.api.project.ProjectResponse;
import com.atenea.persistence.project.ProjectEntity;
import com.atenea.persistence.project.ProjectRepository;
import com.atenea.service.taskexecution.GitRepositoryService;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProjectService {

    private final ProjectRepository projectRepository;
    private final WorkspaceRepositoryPathValidator workspaceRepositoryPathValidator;
    private final GitRepositoryService gitRepositoryService;

    public ProjectService(
            ProjectRepository projectRepository,
            WorkspaceRepositoryPathValidator workspaceRepositoryPathValidator,
            GitRepositoryService gitRepositoryService
    ) {
        this.projectRepository = projectRepository;
        this.workspaceRepositoryPathValidator = workspaceRepositoryPathValidator;
        this.gitRepositoryService = gitRepositoryService;
    }

    @Transactional(readOnly = true)
    public List<ProjectResponse> getProjects() {
        return projectRepository.findAll(Sort.by(Sort.Direction.ASC, "createdAt"))
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public ProjectResponse createProject(CreateProjectRequest request) {
        String projectName = request.name().trim();
        String projectDescription = workspaceRepositoryPathValidator.normalizeNullableText(request.description());
        String projectRepoPath = workspaceRepositoryPathValidator.normalizeConfiguredRepoPath(request.repoPath());
        String requestedDefaultBaseBranch = workspaceRepositoryPathValidator.normalizeNullableText(request.defaultBaseBranch());

        projectRepository.findByName(projectName)
                .ifPresent(project -> {
                    throw new DuplicateProjectNameException(projectName);
                });

        String defaultBaseBranch = requestedDefaultBaseBranch == null
                ? gitRepositoryService.getCurrentBranch(projectRepoPath)
                : requestedDefaultBaseBranch;

        Instant now = Instant.now();

        ProjectEntity project = new ProjectEntity();
        project.setName(projectName);
        project.setDescription(projectDescription);
        project.setRepoPath(projectRepoPath);
        project.setDefaultBaseBranch(defaultBaseBranch);
        project.setCreatedAt(now);
        project.setUpdatedAt(now);

        return toResponse(projectRepository.save(project));
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
}
