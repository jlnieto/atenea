package com.atenea.api.core;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atenea.AteneaApplication;
import com.atenea.persistence.core.CoreCommandRepository;
import com.atenea.persistence.project.ProjectEntity;
import com.atenea.persistence.project.ProjectRepository;
import com.atenea.persistence.worksession.WorkSessionRepository;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(classes = AteneaApplication.class)
@AutoConfigureMockMvc
class CoreCommandIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CoreCommandRepository coreCommandRepository;

    @Autowired
    private WorkSessionRepository workSessionRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @BeforeEach
    void setUp() {
        coreCommandRepository.deleteAll();
        workSessionRepository.deleteAll();
        projectRepository.deleteAll();
    }

    @Test
    void projectOverviewCommandPersistsSupportedResultTypes() throws Exception {
        ProjectEntity project = new ProjectEntity();
        project.setName("atenea-core-integration");
        project.setDescription("Integration project overview");
        project.setRepoPath("/tmp/atenea-core-integration");
        project.setCreatedAt(Instant.now());
        project.setUpdatedAt(Instant.now());
        ProjectEntity persistedProject = projectRepository.save(project);

        mockMvc.perform(post("/api/core/commands")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "input": "cómo está este proyecto",
                                  "channel": "TEXT",
                                  "context": {
                                    "projectId": %d
                                  }
                                }
                                """.formatted(persistedProject.getId())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.intent.capability").value("get_project_overview"))
                .andExpect(jsonPath("$.result.type").value("PROJECT_OVERVIEW"))
                .andExpect(jsonPath("$.result.targetType").value("PROJECT"))
                .andExpect(jsonPath("$.result.targetId").value(persistedProject.getId()));
    }
}
