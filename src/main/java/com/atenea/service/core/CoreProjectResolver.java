package com.atenea.service.core;

import com.atenea.persistence.project.ProjectEntity;
import com.atenea.persistence.project.ProjectRepository;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CoreProjectResolver {

    private final ProjectRepository projectRepository;

    public CoreProjectResolver(ProjectRepository projectRepository) {
        this.projectRepository = projectRepository;
    }

    @Transactional(readOnly = true)
    public List<ProjectEntity> listProjects() {
        return projectRepository.findAll(Sort.by(Sort.Direction.ASC, "createdAt"));
    }

    @Transactional(readOnly = true)
    public ProjectEntity requireById(Long projectId) {
        return projectRepository.findById(projectId)
                .orElseThrow(() -> new CoreInvalidContextException("Missing or invalid Core parameter: projectId"));
    }

    @Transactional(readOnly = true)
    public ProjectEntity resolveUniqueProject(String input) {
        List<ProjectEntity> matches = findMatches(input);
        if (matches.isEmpty()) {
            return null;
        }
        if (matches.size() == 1) {
            return matches.getFirst();
        }
        throw new CoreClarificationRequiredException(new CoreClarification(
                "More than one project matches the current request. Please clarify the project.",
                matches.stream()
                        .limit(5)
                        .map(project -> new CoreClarificationOption("PROJECT", project.getId(), project.getName()))
                        .toList()));
    }

    @Transactional(readOnly = true)
    public ProjectEntity resolveFromInputOrActive(String input, Long activeProjectId) {
        ProjectEntity matched = resolveUniqueProject(input);
        if (matched != null) {
            return matched;
        }
        if (activeProjectId != null) {
            return requireById(activeProjectId);
        }
        if (listProjects().size() == 1) {
            return listProjects().getFirst();
        }
        return null;
    }

    @Transactional(readOnly = true)
    public List<ProjectEntity> findMatches(String input) {
        if (input == null || input.isBlank()) {
            return List.of();
        }
        String normalized = normalize(input);
        return listProjects().stream()
                .filter(project -> matchProject(project, normalized))
                .sorted(Comparator.comparing(ProjectEntity::getName))
                .toList();
    }

    private boolean matchProject(ProjectEntity project, String normalizedInput) {
        return normalizedInput.contains(normalize(project.getName()))
                || (project.getDescription() != null
                && !project.getDescription().isBlank()
                && normalizedInput.contains(normalize(project.getDescription())));
    }

    private String normalize(String value) {
        return value.toLowerCase(Locale.ROOT).replace('-', ' ').replace('_', ' ').trim();
    }
}
