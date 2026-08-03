package com.atenea.api.worksession;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atenea.api.ApiExceptionHandler;
import com.atenea.attachments.AttachmentFeatureDisabledException;
import com.atenea.attachments.AttachmentWorkerException;
import com.atenea.attachments.WorkSessionAttachmentService;
import com.atenea.persistence.project.ProjectEntity;
import com.atenea.persistence.worksession.AttachmentKind;
import com.atenea.persistence.worksession.AttachmentRetentionClass;
import com.atenea.persistence.worksession.AttachmentSource;
import com.atenea.persistence.worksession.WorkSessionAttachmentEntity;
import com.atenea.persistence.worksession.WorkSessionEntity;
import com.atenea.service.worksession.AttachmentNotFoundException;
import com.atenea.service.worksession.AttachmentLimitException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class WorkSessionAttachmentControllerTest {

    private static final UUID ATTACHMENT_ID =
            UUID.fromString("d9e42006-8aac-42ca-84e6-c2cad4a82548");

    @Mock
    private WorkSessionAttachmentService attachmentService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new WorkSessionAttachmentController(attachmentService))
                .setControllerAdvice(new ApiExceptionHandler())
                .setMessageConverters(
                        new ByteArrayHttpMessageConverter(),
                        new MappingJackson2HttpMessageConverter(
                                Jackson2ObjectMapperBuilder.json().build()))
                .build();
    }

    @Test
    void webAndMobileRoutesExposeOnlyScopedMetadata() throws Exception {
        WorkSessionAttachmentEntity attachment = attachment();
        when(attachmentService.list(12L, 50)).thenReturn(List.of(attachment));
        when(attachmentService.get(12L, ATTACHMENT_ID)).thenReturn(attachment);

        mockMvc.perform(get("/api/sessions/12/attachments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].workSessionId").value(12))
                .andExpect(jsonPath("$[0].projectId").value(7))
                .andExpect(jsonPath("$[0].storageIdentity").doesNotExist())
                .andExpect(jsonPath("$[0].workerId").doesNotExist());
        mockMvc.perform(get("/api/mobile/sessions/12/attachments/" + ATTACHMENT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ATTACHMENT_ID.toString()));
    }

    @Test
    void exactDownloadHasPrivateHeadersAndVerifiedBody() throws Exception {
        WorkSessionAttachmentEntity attachment = attachment();
        byte[] body = "retained".getBytes();
        when(attachmentService.download(12L, ATTACHMENT_ID))
                .thenReturn(new WorkSessionAttachmentService.Download(attachment, body));

        mockMvc.perform(get("/api/sessions/12/attachments/" + ATTACHMENT_ID + "/content"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "image/png"))
                .andExpect(content().bytes(body));
    }

    @Test
    void uploadForwardsIdempotencyAndReturnsActionableDisabledState() throws Exception {
        UUID key = UUID.fromString("bb78ecb8-64be-4a66-aa48-1a994e7a5d7a");
        MockMultipartFile file = new MockMultipartFile(
                "file", "screen.png", "image/png", "png".getBytes());
        when(attachmentService.upload(
                org.mockito.ArgumentMatchers.eq(12L),
                org.mockito.ArgumentMatchers.eq(key),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.eq(AttachmentSource.OPERATOR_UPLOAD),
                org.mockito.ArgumentMatchers.eq(AttachmentKind.IMAGE),
                org.mockito.ArgumentMatchers.eq(AttachmentRetentionClass.SESSION),
                org.mockito.ArgumentMatchers.any()))
                .thenThrow(new AttachmentFeatureDisabledException("Adjuntos nuevos desactivados."));

        mockMvc.perform(multipart("/api/mobile/sessions/12/attachments")
                        .file(file)
                        .header("Idempotency-Key", key)
                        .param("kind", "IMAGE"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Adjuntos nuevos desactivados."));
    }

    @Test
    void crossSessionLookupFailsClosedAsNotFound() throws Exception {
        when(attachmentService.get(13L, ATTACHMENT_ID))
                .thenThrow(new AttachmentNotFoundException("El adjunto no existe en esta WorkSession."));

        mockMvc.perform(get("/api/sessions/13/attachments/" + ATTACHMENT_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("El adjunto no existe en esta WorkSession."));
    }

    @Test
    void uploadExposesActionableLimitTypeAndWorkerStates() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "screen.png", "image/png", "png".getBytes());
        when(attachmentService.upload(
                org.mockito.ArgumentMatchers.eq(12L),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.eq(AttachmentSource.OPERATOR_UPLOAD),
                org.mockito.ArgumentMatchers.eq(AttachmentKind.IMAGE),
                org.mockito.ArgumentMatchers.eq(AttachmentRetentionClass.SESSION),
                org.mockito.ArgumentMatchers.any()))
                .thenThrow(new AttachmentLimitException("El adjunto supera el límite de 16 MiB."))
                .thenThrow(new AttachmentWorkerException(
                        "El formato del adjunto no está permitido.",
                        415,
                        "unsupported_content_type"))
                .thenThrow(new AttachmentWorkerException(
                        "El almacenamiento de adjuntos no está disponible.",
                        503,
                        "attachment_worker_unavailable"));

        mockMvc.perform(multipart("/api/mobile/sessions/12/attachments")
                        .file(file)
                        .param("kind", "IMAGE"))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.message").value("El adjunto supera el límite de 16 MiB."));
        mockMvc.perform(multipart("/api/mobile/sessions/12/attachments")
                        .file(file)
                        .param("kind", "IMAGE"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.message").value("El formato del adjunto no está permitido."));
        mockMvc.perform(multipart("/api/mobile/sessions/12/attachments")
                        .file(file)
                        .param("kind", "IMAGE"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("El almacenamiento de adjuntos no está disponible."));
    }

    private WorkSessionAttachmentEntity attachment() {
        ProjectEntity project = new ProjectEntity();
        project.setId(7L);
        WorkSessionEntity session = new WorkSessionEntity();
        session.setId(12L);
        session.setProject(project);
        WorkSessionAttachmentEntity attachment = new WorkSessionAttachmentEntity();
        attachment.setId(ATTACHMENT_ID);
        attachment.setWorkSession(session);
        attachment.setProject(project);
        attachment.setSource(AttachmentSource.OPERATOR_UPLOAD);
        attachment.setKind(AttachmentKind.IMAGE);
        attachment.setOriginalFilename("screen.png");
        attachment.setContentType(MediaType.IMAGE_PNG_VALUE);
        attachment.setSizeBytes(8L);
        attachment.setRetentionClass(AttachmentRetentionClass.SESSION);
        attachment.setRetainUntil(Instant.parse("2026-08-27T23:00:00Z"));
        attachment.setSha256("a".repeat(64));
        attachment.setCreatedAt(Instant.parse("2026-07-28T23:00:00Z"));
        attachment.setIndexedAt(Instant.parse("2026-07-28T23:00:01Z"));
        return attachment;
    }
}
