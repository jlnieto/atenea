package com.atenea.api.project;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atenea.api.ApiExceptionHandler;
import com.atenea.api.project.ProjectOverviewResponse.WorkSessionOverviewResponse;
import com.atenea.service.project.ProjectBootstrapService;
import com.atenea.service.project.ProjectApprovedPriceEstimateService;
import com.atenea.service.project.ProjectRepoPathMissingGitDirectoryException;
import com.atenea.service.project.ProjectOverviewService;
import com.atenea.service.project.ProjectService;
import com.atenea.persistence.worksession.WorkSessionPullRequestStatus;
import com.atenea.persistence.worksession.SessionDeliverableBillingStatus;
import com.atenea.persistence.worksession.WorkSessionStatus;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class ProjectControllerTest {

    @Mock
    private ProjectService projectService;

    @Mock
    private ProjectBootstrapService projectBootstrapService;

    @Mock
    private ProjectOverviewService projectOverviewService;

    @Mock
    private ProjectApprovedPriceEstimateService projectApprovedPriceEstimateService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ProjectController(
                        projectService,
                        projectBootstrapService,
                        projectOverviewService,
                        projectApprovedPriceEstimateService))
                .setControllerAdvice(new ApiExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(
                        Jackson2ObjectMapperBuilder.json().build()))
                .build();
    }

    @Test
    void createProjectReturnsCreatedProject() throws Exception {
        when(projectService.createProject(new CreateProjectRequest(
                "Atenea",
                "Backend orchestration",
                "/repos/internal/atenea",
                null
        ))).thenReturn(new ProjectResponse(
                1L,
                "Atenea",
                "Backend orchestration",
                "/repos/internal/atenea",
                "main",
                Instant.parse("2026-03-22T10:00:00Z"),
                Instant.parse("2026-03-22T10:01:00Z")
        ));

        mockMvc.perform(post("/api/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Atenea",
                                  "description": "Backend orchestration",
                                  "repoPath": "/repos/internal/atenea"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.repoPath").value("/repos/internal/atenea"))
                .andExpect(jsonPath("$.defaultBaseBranch").value("main"));
    }

    @Test
    void createProjectReturnsBadRequestWhenRepoPathIsBlank() throws Exception {
        mockMvc.perform(post("/api/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Atenea",
                                  "description": "Backend orchestration",
                                  "repoPath": "   "
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.details[0]").value("repoPath: must not be blank"));
    }

    @Test
    void createProjectReturnsBadRequestWhenRepoPathHasNoGitDirectory() throws Exception {
        when(projectService.createProject(new CreateProjectRequest(
                "Atenea",
                "Backend orchestration",
                "/repos/internal/atenea",
                null
        ))).thenThrow(new ProjectRepoPathMissingGitDirectoryException("/repos/internal/atenea"));

        mockMvc.perform(post("/api/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Atenea",
                                  "description": "Backend orchestration",
                                  "repoPath": "/repos/internal/atenea"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        "Project repoPath '/repos/internal/atenea' does not contain a .git directory"));
    }

    @Test
    void bootstrapProjectsReturnsCreatedAndExistingProjects() throws Exception {
        when(projectBootstrapService.bootstrapCanonicalProjects()).thenReturn(new ProjectBootstrapResponse(
                java.util.List.of(new ProjectResponse(
                        1L,
                        "Atenea",
                        "Self-hosted Atenea source repository",
                        "/workspace/repos/internal/atenea",
                        "main",
                        null,
                        null)),
                java.util.List.of(new ProjectResponse(
                        2L,
                        "WAB",
                        "Internal WAB development repository",
                        "/workspace/repos/internal/wab",
                        "main",
                        null,
                        null)),
                java.util.List.of(new ProjectBootstrapSkippedProjectResponse(
                        "FMS",
                        "Internal FMS development repository",
                        "/workspace/repos/internal/fms",
                        "Project repoPath '/workspace/repos/internal/fms' does not contain a .git directory"))));

        mockMvc.perform(post("/api/projects/bootstrap"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.createdProjects[0].name").value("Atenea"))
                .andExpect(jsonPath("$.existingProjects[0].name").value("WAB"))
                .andExpect(jsonPath("$.skippedProjects[0].name").value("FMS"));
    }

    @Test
    void getProjectOverviewReturnsCanonicalWorkSessionBlock() throws Exception {
        when(projectOverviewService.getOverview()).thenReturn(java.util.List.of(new ProjectOverviewResponse(
                new ProjectResponse(
                        1L,
                        "Atenea",
                        "Self-hosted Atenea source repository",
                        "/workspace/repos/internal/atenea",
                        "main",
                        Instant.parse("2026-03-22T08:00:00Z"),
                        Instant.parse("2026-03-22T08:05:00Z")),
                new WorkSessionOverviewResponse(
                        50L,
                        true,
                        WorkSessionStatus.OPEN,
                        "Session title",
                        "main",
                        "thread-1",
                        null,
                        WorkSessionPullRequestStatus.NOT_CREATED,
                        null,
                        null,
                        null,
                        false,
                        true,
                        true,
                        "feature/session",
                        true,
                        null,
                        Instant.parse("2026-03-22T10:00:00Z"),
                        Instant.parse("2026-03-22T10:10:00Z"),
                        null))));

        mockMvc.perform(get("/api/projects/overview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].project.name").value("Atenea"))
                .andExpect(jsonPath("$[0].workSession.sessionId").value(50))
                .andExpect(jsonPath("$[0].workSession.current").value(true))
                .andExpect(jsonPath("$[0].workSession.runInProgress").value(true))
                .andExpect(jsonPath("$[0].workSession.closeBlockedState").doesNotExist())
                .andExpect(jsonPath("$[0].legacy").doesNotExist());
    }

    @Test
    void getProjectApprovedPriceEstimatesReturnsApprovedPricingAcrossSessions() throws Exception {
        when(projectApprovedPriceEstimateService.getApprovedPriceEstimates(1L))
                .thenReturn(new ProjectApprovedPriceEstimatesResponse(
                        1L,
                        java.util.List.of(new com.atenea.api.worksession.ApprovedPriceEstimateSummaryResponse(
                                50L,
                                82L,
                                2,
                                "Price estimate v2",
                                "EUR",
                                43.0,
                                6.5,
                                240.0,
                                279.0,
                                320.0,
                                "competitive",
                                "low",
                                "medium",
                                java.util.List.of("Solo trabajo de la session"),
                                java.util.List.of("No incluye soporte posterior"),
                                SessionDeliverableBillingStatus.READY,
                                null,
                                null,
                                Instant.parse("2026-03-22T10:20:00Z"),
                                Instant.parse("2026-03-22T10:20:00Z")))));

        mockMvc.perform(get("/api/projects/1/approved-price-estimates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectId").value(1))
                .andExpect(jsonPath("$.approvedPriceEstimates[0].sessionId").value(50))
                .andExpect(jsonPath("$.approvedPriceEstimates[0].billingStatus").value("READY"))
                .andExpect(jsonPath("$.approvedPriceEstimates[0].recommendedPrice").value(279.0));
    }

}
