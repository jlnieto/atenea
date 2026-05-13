package com.atenea.api.core;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atenea.AteneaApplication;
import com.atenea.persistence.core.CoreCommandRepository;
import com.atenea.persistence.project.ProjectEntity;
import com.atenea.persistence.project.ProjectRepository;
import com.atenea.persistence.worksession.WorkSessionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(classes = AteneaApplication.class)
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "atenea.auth.bootstrap.enabled=true",
        "atenea.auth.bootstrap.email=operator@atenea.local",
        "atenea.auth.bootstrap.password=secret-pass",
        "atenea.auth.bootstrap.display-name=Integration Operator",
        "atenea.auth.jwt.secret=integration-mobile-secret-2026"
})
class CoreCommandIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CoreCommandRepository coreCommandRepository;

    @Autowired
    private WorkSessionRepository workSessionRepository;

    @Autowired
    private ProjectRepository projectRepository;

    private String accessToken;

    @BeforeEach
    void setUp() {
        coreCommandRepository.deleteAll();
        workSessionRepository.deleteAll();
        projectRepository.deleteAll();
        accessToken = null;
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
                        .header("Authorization", "Bearer " + getAccessToken())
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

    private String getAccessToken() throws Exception {
        if (accessToken != null) {
            return accessToken;
        }
        JsonNode loginJson = objectMapper.readTree(mockMvc.perform(post("/api/mobile/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "operator@atenea.local",
                                  "password": "secret-pass"
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
        accessToken = loginJson.get("accessToken").asText();
        return accessToken;
    }
}
