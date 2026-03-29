package com.atenea.api.project;

import com.atenea.service.project.ProjectService;
import com.atenea.service.project.ProjectBootstrapService;
import com.atenea.service.project.ProjectApprovedPriceEstimateService;
import com.atenea.service.project.ProjectOverviewService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private final ProjectService projectService;
    private final ProjectBootstrapService projectBootstrapService;
    private final ProjectOverviewService projectOverviewService;
    private final ProjectApprovedPriceEstimateService projectApprovedPriceEstimateService;

    public ProjectController(
            ProjectService projectService,
            ProjectBootstrapService projectBootstrapService,
            ProjectOverviewService projectOverviewService,
            ProjectApprovedPriceEstimateService projectApprovedPriceEstimateService
    ) {
        this.projectService = projectService;
        this.projectBootstrapService = projectBootstrapService;
        this.projectOverviewService = projectOverviewService;
        this.projectApprovedPriceEstimateService = projectApprovedPriceEstimateService;
    }

    @GetMapping
    public List<ProjectResponse> getProjects() {
        return projectService.getProjects();
    }

    @GetMapping("/overview")
    public List<ProjectOverviewResponse> getProjectOverview() {
        return projectOverviewService.getOverview();
    }

    @GetMapping("/{projectId}/approved-price-estimates")
    public ProjectApprovedPriceEstimatesResponse getApprovedPriceEstimates(@PathVariable Long projectId) {
        return projectApprovedPriceEstimateService.getApprovedPriceEstimates(projectId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProjectResponse createProject(@Valid @RequestBody CreateProjectRequest request) {
        return projectService.createProject(request);
    }

    @PostMapping("/bootstrap")
    public ProjectBootstrapResponse bootstrapProjects() {
        return projectBootstrapService.bootstrapCanonicalProjects();
    }
}
