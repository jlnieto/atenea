package com.atenea.service.project;

import com.atenea.api.project.CreateProjectRequest;
import com.atenea.api.project.ProjectResponse;
import com.atenea.persistence.project.ProjectEntity;
import com.atenea.persistence.project.ProjectRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProjectService {

    private final ProjectRepository projectRepository;
    private final WorkspaceRepositoryPathValidator workspaceRepositoryPathValidator;

    public ProjectService(
            ProjectRepository projectRepository,
            WorkspaceRepositoryPathValidator workspaceRepositoryPathValidator
    ) {
        this.projectRepository = projectRepository;
        this.workspaceRepositoryPathValidator = workspaceRepositoryPathValidator;
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

        projectRepository.findByName(projectName)
                .ifPresent(project -> {
                    throw new DuplicateProjectNameException(projectName);
                });

        Instant now = Instant.now();

        ProjectEntity project = new ProjectEntity();
        project.setName(projectName);
        project.setDescription(projectDescription);
        project.setRepoPath(projectRepoPath);
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
                project.getCreatedAt(),
                project.getUpdatedAt()
        );
    }
}
