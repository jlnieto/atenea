package com.atenea.api.worksession;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atenea.api.ApiExceptionHandler;
import com.atenea.persistence.project.ProjectEntity;
import com.atenea.persistence.worksession.PreviewState;
import com.atenea.persistence.worksession.WorkSessionEntity;
import com.atenea.persistence.worksession.WorkSessionPreviewEntity;
import com.atenea.previews.PreviewFeatureDisabledException;
import com.atenea.previews.PreviewProperties;
import com.atenea.previews.WorkSessionPreviewService;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class WorkSessionPreviewControllerTest {

    private static final UUID PREVIEW =
            UUID.fromString("61000000-0000-4000-8000-000000000001");

    @Mock WorkSessionPreviewService previewService;
    private MockMvc mockMvc;
    private PreviewProperties previewProperties;

    @BeforeEach
    void setUp() {
        previewProperties = new PreviewProperties();
        previewProperties.setEnabled(true);
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new WorkSessionPreviewController(previewService, previewProperties))
                .setControllerAdvice(new ApiExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(
                        Jackson2ObjectMapperBuilder.json().build()))
                .build();
    }

    @Test
    void webAndMobileExposeSameActionableReadyIdentityWithoutWorkerInternals() throws Exception {
        when(previewService.status(12L)).thenReturn(preview(PreviewState.READY));

        mockMvc.perform(get("/api/sessions/12/preview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(PREVIEW.toString()))
                .andExpect(jsonPath("$.state").value("READY"))
                .andExpect(jsonPath("$.primaryAction").value("OPEN"))
                .andExpect(jsonPath("$.privateUrl").value("http://100.81.98.93:19000/ready"))
                .andExpect(jsonPath("$.workerId").doesNotExist())
                .andExpect(jsonPath("$.allocationIdentity").doesNotExist());
        mockMvc.perform(get("/api/mobile/sessions/12/preview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(PREVIEW.toString()))
                .andExpect(jsonPath("$.primaryAction").value("OPEN"));
    }

    @Test
    void nonReadyStateNeverExposesStaleOpenUrl() throws Exception {
        when(previewService.status(12L)).thenReturn(preview(PreviewState.EXPIRED));

        mockMvc.perform(get("/api/sessions/12/preview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("EXPIRED"))
                .andExpect(jsonPath("$.privateUrl").doesNotExist())
                .andExpect(jsonPath("$.primaryAction").value("START"));
    }

    @Test
    void disabledReadRetainsStateButSuppressesEveryPreviewAffordance() throws Exception {
        previewProperties.setEnabled(false);
        when(previewService.status(12L)).thenReturn(preview(PreviewState.READY));

        mockMvc.perform(get("/api/sessions/12/preview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("READY"))
                .andExpect(jsonPath("$.privateUrl").doesNotExist())
                .andExpect(jsonPath("$.primaryAction").value("NONE"))
                .andExpect(jsonPath("$.nextAction").value(
                        "Los previews nuevos están desactivados; el estado retenido sigue disponible."));
    }

    @Test
    void disabledActivationReturnsActionableConflict() throws Exception {
        when(previewService.activate(
                org.mockito.ArgumentMatchers.eq(12L),
                org.mockito.ArgumentMatchers.any()))
                .thenThrow(new PreviewFeatureDisabledException("Previews nuevos desactivados."));

        mockMvc.perform(post("/api/mobile/sessions/12/preview/activate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "runtimeSessionId":"61000000-0000-4000-8000-000000000002",
                                  "allocationFingerprint":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Previews nuevos desactivados."));
    }

    private WorkSessionPreviewEntity preview(PreviewState state) {
        ProjectEntity project = new ProjectEntity();
        project.setId(7L);
        WorkSessionEntity session = new WorkSessionEntity();
        session.setId(12L);
        session.setProject(project);
        WorkSessionPreviewEntity preview = new WorkSessionPreviewEntity();
        preview.setId(PREVIEW);
        preview.setWorkSession(session);
        preview.setProject(project);
        preview.setState(state);
        preview.setLifecycleRevision(state == PreviewState.READY ? 2 : 3);
        preview.setPrivateUrl("http://100.81.98.93:19000/ready");
        preview.setLeaseExpiresAt(Instant.parse("2026-07-29T02:00:00Z"));
        preview.setHardExpiresAt(Instant.parse("2026-07-29T09:00:00Z"));
        preview.setAuditRetainUntil(Instant.parse("2026-08-28T01:00:00Z"));
        preview.setNextAction(state == PreviewState.READY
                ? "Abre el preview privado."
                : "Inicia de nuevo el preview.");
        return preview;
    }
}
