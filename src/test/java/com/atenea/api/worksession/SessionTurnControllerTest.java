package com.atenea.api.worksession;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atenea.api.ApiExceptionHandler;
import com.atenea.persistence.worksession.AgentRunStatus;
import com.atenea.persistence.worksession.SessionTurnActor;
import com.atenea.persistence.worksession.WorkSessionStatus;
import com.atenea.service.worksession.SessionTurnService;
import com.atenea.service.worksession.AttachmentConflictException;
import com.atenea.service.worksession.AttachmentLimitException;
import com.atenea.service.worksession.AttachmentOwnershipException;
import com.atenea.service.worksession.WorkSessionService;
import com.atenea.service.worksession.WorkSessionAlreadyRunningException;
import com.atenea.service.worksession.WorkSessionNotOpenException;
import com.atenea.service.worksession.WorkSessionNotFoundException;
import com.atenea.service.worksession.WorkSessionTurnExecutionFailedException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.stream.Stream;

@ExtendWith(MockitoExtension.class)
class SessionTurnControllerTest {

    @Mock
    private SessionTurnService sessionTurnService;

    @Mock
    private WorkSessionService workSessionService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new SessionTurnController(sessionTurnService, workSessionService))
                .setControllerAdvice(new ApiExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(
                        Jackson2ObjectMapperBuilder.json().build()))
                .build();
    }

    @Test
    void getTurnsReturnsVisibleConversationHistory() throws Exception {
        when(sessionTurnService.getTurns(12L, null, null)).thenReturn(List.of(
                new SessionTurnResponse(
                        101L,
                        SessionTurnActor.OPERATOR,
                        "Explain the current implementation",
                        Instant.parse("2026-03-25T10:05:00Z")),
                new SessionTurnResponse(
                        102L,
                        SessionTurnActor.CODEX,
                        "The implementation is split into services",
                        Instant.parse("2026-03-25T10:06:00Z"))));

        mockMvc.perform(get("/api/sessions/12/turns"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(101))
                .andExpect(jsonPath("$[0].actor").value("OPERATOR"))
                .andExpect(jsonPath("$[0].messageText").value("Explain the current implementation"))
                .andExpect(jsonPath("$[1].id").value(102))
                .andExpect(jsonPath("$[1].actor").value("CODEX"));
    }

    @Test
    void getTurnsSerializesOnlyPublicBoundImageMetadata() throws Exception {
        UUID attachmentId = UUID.fromString("4e8f351e-e05a-41b6-99e5-3eb72d770002");
        when(sessionTurnService.getTurns(12L, null, null)).thenReturn(List.of(
                new SessionTurnResponse(
                        101L,
                        SessionTurnActor.OPERATOR,
                        "Inspect this image",
                        Instant.parse("2026-03-25T10:05:00Z"),
                        null,
                        List.of(new SessionTurnAttachmentResponse(
                                attachmentId,
                                (short) 0,
                                "screen.png",
                                "image/png",
                                1024L,
                                "a".repeat(64),
                                "/api/sessions/12/attachments/" + attachmentId + "/content")))));

        mockMvc.perform(get("/api/sessions/12/turns"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].attachments.length()").value(1))
                .andExpect(jsonPath("$[0].attachments[0].id").value(attachmentId.toString()))
                .andExpect(jsonPath("$[0].attachments[0].position").value(0))
                .andExpect(jsonPath("$[0].attachments[0].originalFilename").value("screen.png"))
                .andExpect(jsonPath("$[0].attachments[0].contentType").value("image/png"))
                .andExpect(jsonPath("$[0].attachments[0].sizeBytes").value(1024))
                .andExpect(jsonPath("$[0].attachments[0].downloadPath").value(
                        "/api/sessions/12/attachments/" + attachmentId + "/content"))
                .andExpect(jsonPath("$[0].attachments[0].workerId").doesNotExist())
                .andExpect(jsonPath("$[0].attachments[0].storageIdentity").doesNotExist())
                .andExpect(jsonPath("$[0].attachments[0].workspaceIdentity").doesNotExist())
                .andExpect(jsonPath("$[0].attachments[0].remoteSessionId").doesNotExist())
                .andExpect(jsonPath("$[0].attachments[0].content").doesNotExist());
    }

    @Test
    void getTurnsReturnsWindowWhenBeforeTurnIdAndLimitAreProvided() throws Exception {
        when(sessionTurnService.getTurns(12L, 105L, 2)).thenReturn(List.of(
                new SessionTurnResponse(
                        103L,
                        SessionTurnActor.OPERATOR,
                        "Older question",
                        Instant.parse("2026-03-25T10:03:00Z")),
                new SessionTurnResponse(
                        104L,
                        SessionTurnActor.CODEX,
                        "Older answer",
                        Instant.parse("2026-03-25T10:04:00Z"))));

        mockMvc.perform(get("/api/sessions/12/turns")
                        .param("beforeTurnId", "105")
                        .param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(103))
                .andExpect(jsonPath("$[1].id").value(104));
    }

    @Test
    void getTurnsReturnsNotFoundWhenSessionDoesNotExist() throws Exception {
        when(sessionTurnService.getTurns(12L, null, null)).thenThrow(new WorkSessionNotFoundException(12L));

        mockMvc.perform(get("/api/sessions/12/turns"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("WorkSession with id '12' was not found"));
    }

    @Test
    void getTurnsReturnsBadRequestWhenLimitIsInvalid() throws Exception {
        when(sessionTurnService.getTurns(12L, null, 0))
                .thenThrow(new IllegalArgumentException("Turn limit must be greater than zero"));

        mockMvc.perform(get("/api/sessions/12/turns")
                        .param("limit", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Turn limit must be greater than zero"));
    }

    @Test
    void createTurnReturnsCreatedConversationTurn() throws Exception {
        when(sessionTurnService.createTurn(12L, new CreateSessionTurnRequest("Inspect the project")))
                .thenReturn(new CreateSessionTurnResponse(
                        new SessionTurnResponse(
                                101L,
                                SessionTurnActor.OPERATOR,
                                "Inspect the project",
                                Instant.parse("2026-03-25T10:05:00Z")),
                        new AgentRunResponse(
                                55L,
                                12L,
                                101L,
                                102L,
                                AgentRunStatus.SUCCEEDED,
                                "/workspace/repos/internal/atenea",
                                "turn-1",
                                Instant.parse("2026-03-25T10:05:01Z"),
                                Instant.parse("2026-03-25T10:05:02Z"),
                                "Current status summary",
                                null,
                                Instant.parse("2026-03-25T10:05:01Z")),
                        new SessionTurnResponse(
                                102L,
                                SessionTurnActor.CODEX,
                                "Current status summary",
                                Instant.parse("2026-03-25T10:05:02Z"))));

        mockMvc.perform(post("/api/sessions/12/turns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "message": "Inspect the project"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.operatorTurn.id").value(101))
                .andExpect(jsonPath("$.operatorTurn.actor").value("OPERATOR"))
                .andExpect(jsonPath("$.run.id").value(55))
                .andExpect(jsonPath("$.run.externalTurnId").value("turn-1"))
                .andExpect(jsonPath("$.codexTurn.actor").value("CODEX"));

        verify(sessionTurnService).createTurn(
                12L,
                new CreateSessionTurnRequest("Inspect the project", null, List.of()));
    }

    @Test
    void createTurnPreservesOrderedAttachmentIdsAndClientRequestIdentity() throws Exception {
        UUID clientRequestId = UUID.fromString("7b35f774-97f2-4a9e-b7db-0f18d59112ba");
        UUID firstAttachmentId = UUID.fromString("4e8f351e-e05a-41b6-99e5-3eb72d770002");
        UUID secondAttachmentId = UUID.fromString("9aa2c7e5-1fd9-48ec-aa10-03dfdfb8ca7d");
        when(sessionTurnService.createTurn(
                org.mockito.ArgumentMatchers.eq(12L),
                any(CreateSessionTurnRequest.class)))
                .thenReturn(null);

        mockMvc.perform(post("/api/sessions/12/turns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "message": "Inspect both images",
                                  "clientRequestId": "%s",
                                  "attachmentIds": ["%s", "%s"]
                                }
                                """.formatted(
                                    clientRequestId,
                                    firstAttachmentId,
                                    secondAttachmentId)))
                .andExpect(status().isCreated());

        verify(sessionTurnService).createTurn(
                12L,
                new CreateSessionTurnRequest(
                        "Inspect both images",
                        clientRequestId,
                        List.of(firstAttachmentId, secondAttachmentId)));
    }

    @Test
    void createTurnRejectsAttachmentsWithoutClientRequestIdentityBeforeService() throws Exception {
        UUID attachmentId = UUID.fromString("4e8f351e-e05a-41b6-99e5-3eb72d770002");

        mockMvc.perform(post("/api/sessions/12/turns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "message": "Inspect this image",
                                  "attachmentIds": ["%s"]
                                }
                                """.formatted(attachmentId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.details[0]").value(
                        "requestIdentityPresentForAttachments: "
                                + "clientRequestId is required when attachmentIds are present"));

        verify(sessionTurnService, never()).createTurn(
                org.mockito.ArgumentMatchers.eq(12L),
                any(CreateSessionTurnRequest.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "path", "host", "workerId", "slot", "workspace", "workspaceIdentity",
            "shell", "credentials", "authorization", "executionAuthority"
    })
    void createTurnRejectsClientProvidedInternalExecutionSelectors(String selector)
            throws Exception {
        mockMvc.perform(post("/api/sessions/12/turns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "message": "Inspect the project",
                                  "%s": "client-controlled"
                                }
                                """.formatted(selector)))
                .andExpect(status().isBadRequest());

        verify(sessionTurnService, never()).createTurn(
                org.mockito.ArgumentMatchers.eq(12L),
                any(CreateSessionTurnRequest.class));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("closedAttachmentDenials")
    void createTurnMapsAttachmentDenialsWithoutReturningPrivateDetails(
            String scenario,
            RuntimeException denial,
            int expectedStatus
    ) throws Exception {
        UUID attachmentId = UUID.fromString("4e8f351e-e05a-41b6-99e5-3eb72d770002");
        when(sessionTurnService.createTurn(
                org.mockito.ArgumentMatchers.eq(12L),
                any(CreateSessionTurnRequest.class))).thenThrow(denial);

        mockMvc.perform(post("/api/sessions/12/turns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "message": "Inspect selected image",
                                  "clientRequestId": "7b35f774-97f2-4a9e-b7db-0f18d59112ba",
                                  "attachmentIds": ["%s"]
                                }
                                """.formatted(attachmentId)))
                .andExpect(status().is(expectedStatus))
                .andExpect(jsonPath("$.message").value(denial.getMessage()))
                .andExpect(jsonPath("$.details").isEmpty())
                .andExpect(jsonPath("$.workerId").doesNotExist())
                .andExpect(jsonPath("$.storageIdentity").doesNotExist())
                .andExpect(jsonPath("$.path").doesNotExist());
    }

    private static Stream<Arguments> closedAttachmentDenials() {
        return Stream.of(
                Arguments.of(
                        "duplicate or conflicting selection",
                        new AttachmentConflictException("La selección de imágenes entra en conflicto."),
                        409),
                Arguments.of(
                        "expired, non-image, partial or foreign ownership",
                        new AttachmentOwnershipException("La imagen no tiene ownership verificable."),
                        409),
                Arguments.of(
                        "individual or combined size limit",
                        new AttachmentLimitException("La selección supera el límite permitido."),
                        413));
    }

    @Test
    void createTurnConversationViewReturnsCanonicalConversationView() throws Exception {
        when(sessionTurnService.createTurn(12L, new CreateSessionTurnRequest("Inspect the project")))
                .thenReturn(new CreateSessionTurnResponse(
                        new SessionTurnResponse(
                                101L,
                                SessionTurnActor.OPERATOR,
                                "Inspect the project",
                                Instant.parse("2026-03-25T10:05:00Z")),
                        new AgentRunResponse(
                                55L,
                                12L,
                                101L,
                                null,
                                AgentRunStatus.RUNNING,
                                "/workspace/repos/internal/atenea",
                                "turn-1",
                                Instant.parse("2026-03-25T10:05:01Z"),
                                null,
                                null,
                                null,
                                Instant.parse("2026-03-25T10:05:01Z")),
                        null));
        when(workSessionService.getSessionConversationView(12L)).thenReturn(new WorkSessionConversationViewResponse(
                new WorkSessionViewResponse(
                        new WorkSessionResponse(
                                12L,
                                7L,
                                WorkSessionStatus.OPEN,
                                WorkSessionOperationalState.RUNNING,
                                "Inspect status",
                                "main",
                                "atenea/session-12",
                                "thread-1",
                                null,
                                null,
                                null,
                                Instant.parse("2026-03-25T10:00:00Z"),
                                Instant.parse("2026-03-25T10:05:01Z"),
                                null,
                                null,
                                null,
                                null,
                                null,
                                false,
                                new SessionOperationalSnapshotResponse(true, true, "atenea/session-12", true)),
                        true,
                        false,
                        new WorkSessionViewLatestRunResponse(
                                55L,
                                AgentRunStatus.RUNNING,
                                101L,
                                null,
                                "turn-1",
                                Instant.parse("2026-03-25T10:05:01Z"),
                                null,
                                null,
                                null),
                        null,
                        null),
                List.of(
                        new SessionTurnResponse(
                                101L,
                                SessionTurnActor.OPERATOR,
                                "Inspect the project",
                                Instant.parse("2026-03-25T10:05:00Z"))),
                20,
                false));

        mockMvc.perform(post("/api/sessions/12/turns/conversation-view")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "message": "Inspect the project"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.view.view.session.id").value(12))
                .andExpect(jsonPath("$.view.view.runInProgress").value(true))
                .andExpect(jsonPath("$.view.recentTurns[0].actor").value("OPERATOR"))
                .andExpect(jsonPath("$.view.recentTurnLimit").value(20))
                .andExpect(jsonPath("$.view.historyTruncated").value(false));
    }

    @Test
    void createTurnReturnsConflictWhenSessionIsNotOpen() throws Exception {
        when(sessionTurnService.createTurn(12L, new CreateSessionTurnRequest("Inspect the project")))
                .thenThrow(new WorkSessionNotOpenException(12L, WorkSessionStatus.CLOSED));

        mockMvc.perform(post("/api/sessions/12/turns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "message": "Inspect the project"
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        "WorkSession with id '12' is not OPEN (current status: CLOSED)"));
    }

    @Test
    void createTurnReturnsConflictWhenSessionIsAlreadyRunning() throws Exception {
        when(sessionTurnService.createTurn(12L, new CreateSessionTurnRequest("Inspect the project")))
                .thenThrow(new WorkSessionAlreadyRunningException(12L));

        mockMvc.perform(post("/api/sessions/12/turns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "message": "Inspect the project"
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        "WorkSession with id '12' is already RUNNING and does not accept a new executable turn"));
    }

    @Test
    void createTurnReturnsBadGatewayWhenCodexExecutionFails() throws Exception {
        when(sessionTurnService.createTurn(12L, new CreateSessionTurnRequest("Inspect the project")))
                .thenThrow(new WorkSessionTurnExecutionFailedException("Codex execution failed for WorkSession turn"));

        mockMvc.perform(post("/api/sessions/12/turns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "message": "Inspect the project"
                                }
                                """))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.message").value("Codex execution failed for WorkSession turn"));
    }
}
