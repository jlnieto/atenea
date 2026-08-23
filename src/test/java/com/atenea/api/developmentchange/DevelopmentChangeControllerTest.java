package com.atenea.api.developmentchange;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atenea.api.ApiExceptionHandler;
import com.atenea.auth.AuthenticatedOperator;
import com.atenea.persistence.developmentchange.DevelopmentChangeProjectionState;
import com.atenea.persistence.developmentchange.DevelopmentChangeSourceState;
import com.atenea.persistence.developmentchange.DevelopmentChangeStatus;
import com.atenea.persistence.developmentchange.DevelopmentChangeWorkspaceState;
import com.atenea.persistence.developmentchange.DevelopmentChangeWorkspaceOperationKind;
import com.atenea.persistence.developmentchange.DevelopmentChangeWorkspaceOperationState;
import com.atenea.persistence.developmentchange.RemoteSessionOperationState;
import com.atenea.persistence.worksession.WorkSessionStatus;
import com.atenea.service.developmentchange.DevelopmentChangeRejectedException;
import com.atenea.service.developmentchange.DevelopmentChangeService;
import com.atenea.service.developmentchange.DevelopmentChangeWorkspaceService;
import com.atenea.service.developmentchange.RemoteSessionRejectedException;
import com.atenea.service.developmentchange.RemoteSessionService;
import com.atenea.v2.control.V2FailureCategory;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class DevelopmentChangeControllerTest {

    private static final UUID CHANGE_KEY =
            UUID.fromString("8bf60472-3c0e-49aa-99bf-6dc3c7e60eaf");
    private static final UUID IDEMPOTENCY_KEY =
            UUID.fromString("17f120f6-79e2-49e4-bd13-23db520d1374");

    @Mock private DevelopmentChangeService service;
    @Mock private DevelopmentChangeWorkspaceService workspaceService;
    @Mock private RemoteSessionService remoteSessionService;

    private MockMvc mockMvc;
    private RequestPostProcessor principal;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new DevelopmentChangeController(
                                service, workspaceService, remoteSessionService))
                .setControllerAdvice(new ApiExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(
                        Jackson2ObjectMapperBuilder.json()
                                .featuresToEnable(
                                        com.fasterxml.jackson.core.JsonParser.Feature
                                                .STRICT_DUPLICATE_DETECTION)
                                .build()))
                .build();
        principal = SecurityMockMvcRequestPostProcessors.authentication(
                new UsernamePasswordAuthenticationToken(
                        new AuthenticatedOperator(9L, "synthetic@atenea.test", "Synthetic"),
                        null));
    }

    @Test
    void readsServerOwnedProjection() throws Exception {
        when(service.list(7L)).thenReturn(List.of(response()));

        mockMvc.perform(get("/api/v2/projects/7/development-changes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].changeKey").value(CHANGE_KEY.toString()))
                .andExpect(jsonPath("$[0].workspaceBranch")
                        .value("atenea/change-" + CHANGE_KEY))
                .andExpect(jsonPath("$[0].primaryAction.kind").value("BIND_SESSION"));
    }

    @Test
    void createsWithAuthenticatedOperatorAndOpaqueIdempotencyHeader() throws Exception {
        when(service.create(any(), eq(7L), eq(IDEMPOTENCY_KEY), any()))
                .thenReturn(new DevelopmentChangeMutationResponse(
                        UUID.fromString("61552669-4b46-431c-811d-344293ab3c67"),
                        "a".repeat(64),
                        false,
                        response()));

        mockMvc.perform(post("/api/v2/projects/7/development-changes")
                        .with(principal)
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Synthetic change\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayed").value(false))
                .andExpect(jsonPath("$.receiptSha256").value("a".repeat(64)))
                .andExpect(jsonPath("$.developmentChange.changeKey")
                        .value(CHANGE_KEY.toString()));
    }

    @Test
    void internalSelectorIsRejectedAsDeterministicValidationWithoutEchoingItsValue()
            throws Exception {
        when(service.create(any(), eq(7L), eq(IDEMPOTENCY_KEY), any()))
                .thenAnswer(invocation -> {
                    CreateDevelopmentChangeRequest request = invocation.getArgument(3);
                    if (!request.unsupportedFields().contains("workspaceBranch")) {
                        throw new AssertionError("Unsupported selector was not retained for rejection");
                    }
                    throw new DevelopmentChangeRejectedException(
                            V2FailureCategory.VALIDATION,
                            "DEVELOPMENT_CHANGE_INTERNAL_SELECTOR_REJECTED",
                            "La solicitud contiene selectores que sólo puede resolver el servidor.",
                            DevelopmentChangeActionResponse.none());
                });

        mockMvc.perform(post("/api/v2/projects/7/development-changes")
                        .with(principal)
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Synthetic change",
                                  "workspaceBranch": "secret/client-selected"
                                }
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.failureCategory").value("VALIDATION"))
                .andExpect(jsonPath("$.failureCode")
                        .value("DEVELOPMENT_CHANGE_INTERNAL_SELECTOR_REJECTED"))
                .andExpect(jsonPath("$.message").value(
                        "La solicitud contiene selectores que sólo puede resolver el servidor."))
                .andExpect(jsonPath("$..workspaceBranch").doesNotExist());
    }

    @Test
    void blankTitleReachesTheAuditedDomainValidation() throws Exception {
        when(service.create(any(), eq(7L), eq(IDEMPOTENCY_KEY), any()))
                .thenAnswer(invocation -> {
                    CreateDevelopmentChangeRequest request = invocation.getArgument(3);
                    if (!request.getTitle().isBlank()) {
                        throw new AssertionError("Expected a blank title");
                    }
                    throw new DevelopmentChangeRejectedException(
                            V2FailureCategory.VALIDATION,
                            "DEVELOPMENT_CHANGE_REQUEST_INVALID",
                            "Se requiere título e idempotency key válidos.",
                            DevelopmentChangeActionResponse.none());
                });

        mockMvc.perform(post("/api/v2/projects/7/development-changes")
                        .with(principal)
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"   \"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.failureCategory").value("VALIDATION"))
                .andExpect(jsonPath("$.failureCode")
                        .value("DEVELOPMENT_CHANGE_REQUEST_INVALID"));
    }

    @Test
    void workspaceEndpointsExposeOnlyDurableSanitizedOperationState() throws Exception {
        DevelopmentChangeWorkspaceOperationResponse result =
                new DevelopmentChangeWorkspaceOperationResponse(
                        UUID.fromString("cc4d339e-7a24-4e89-8ca5-5c65908f2584"),
                        DevelopmentChangeWorkspaceOperationKind.PROVISION,
                        DevelopmentChangeWorkspaceOperationState.SUCCEEDED,
                        2,
                        "d".repeat(64),
                        false,
                        null,
                        DevelopmentChangeWorkspaceState.READY,
                        DevelopmentChangeSourceState.CLEAN,
                        0,
                        "b".repeat(64),
                        DevelopmentChangeActionResponse.bindSession());
        when(workspaceService.provision(
                any(), eq(7L), eq(CHANGE_KEY), eq(IDEMPOTENCY_KEY)))
                .thenReturn(result);

        mockMvc.perform(post("/api/v2/projects/7/development-changes/"
                        + CHANGE_KEY + "/workspace/provision")
                        .with(principal)
                        .header("Idempotency-Key", IDEMPOTENCY_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operationKind").value("PROVISION"))
                .andExpect(jsonPath("$.state").value("SUCCEEDED"))
                .andExpect(jsonPath("$.workspaceState").value("READY"))
                .andExpect(jsonPath("$.nextAction.kind").value("BIND_SESSION"))
                .andExpect(jsonPath("$..workspaceIdentity").doesNotExist())
                .andExpect(jsonPath("$..workspacePath").doesNotExist());
    }

    @Test
    void openOrResolveAcceptsOnlyExpectedRevisionAndOpaqueIdempotencyKey() throws Exception {
        UUID operationId = UUID.fromString("0bd4bd09-589c-4cf5-8922-a867cad7dbf5");
        UUID remoteSessionId = UUID.fromString("cc5e4f00-38ce-457e-9c48-97148e95a0ac");
        when(remoteSessionService.openOrResolve(
                any(), eq(7L), eq(CHANGE_KEY), eq(IDEMPOTENCY_KEY), any()))
                .thenReturn(new RemoteSessionOperationResponse(
                        operationId,
                        RemoteSessionOperationState.SUCCEEDED,
                        1,
                        "a".repeat(64),
                        false,
                        RemoteSessionResolution.CREATED,
                        CHANGE_KEY,
                        91L,
                        remoteSessionId,
                        "b".repeat(64),
                        "c".repeat(64),
                        4,
                        WorkSessionStatus.OPEN,
                        RemoteSessionNextAction.CONTINUE_SESSION));

        mockMvc.perform(post("/api/v2/projects/7/development-changes/"
                        + CHANGE_KEY + "/work-session:open-or-resolve")
                        .with(principal)
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedChangeRevision\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operationId").value(operationId.toString()))
                .andExpect(jsonPath("$.state").value("SUCCEEDED"))
                .andExpect(jsonPath("$.resolution").value("CREATED"))
                .andExpect(jsonPath("$.sessionId").value(91))
                .andExpect(jsonPath("$.remoteSessionId").value(remoteSessionId.toString()))
                .andExpect(jsonPath("$.replayed").value(false))
                .andExpect(jsonPath("$..workspaceIdentity").doesNotExist())
                .andExpect(jsonPath("$..workspacePath").doesNotExist());
    }

    @Test
    void openOrResolveRetainsEveryClientSelectorForDeterministicRejectionWithoutEcho()
            throws Exception {
        when(remoteSessionService.openOrResolve(
                any(), eq(7L), eq(CHANGE_KEY), eq(IDEMPOTENCY_KEY), any()))
                .thenAnswer(invocation -> {
                    OpenOrResolveRemoteSessionRequest request = invocation.getArgument(4);
                    if (!request.unsupportedFields().containsAll(List.of(
                            "idempotencyKey", "sessionId", "worker", "workload", "branch",
                            "workspace", "workspacePath", "path", "remoteIdentity",
                            "remoteSessionId", "ref", "sourceRef"))) {
                        throw new AssertionError("Client selectors were not retained for rejection");
                    }
                    throw new RemoteSessionRejectedException(
                            RemoteSessionRejectionClass.VALIDATION,
                            "REMOTE_SESSION_CLIENT_SELECTOR_REJECTED",
                            "La solicitud contiene selectores internos no admitidos.",
                            RemoteSessionNextAction.NONE);
                });

        mockMvc.perform(post("/api/v2/projects/7/development-changes/"
                        + CHANGE_KEY + "/work-session:open-or-resolve")
                        .with(principal)
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "expectedChangeRevision": 3,
                                  "idempotencyKey": "17f120f6-79e2-49e4-bd13-23db520d1374",
                                  "sessionId": 19,
                                  "worker": "foreign",
                                  "workload": "client-workload",
                                  "branch": "client/ref",
                                  "workspace": "client-workspace",
                                  "workspacePath": "/host/secret",
                                  "path": "/host/other-secret",
                                  "remoteIdentity": "foreign-identity",
                                  "remoteSessionId": "6547081d-895e-4be1-a8fd-d115b7743cdf",
                                  "ref": "refs/heads/foreign",
                                  "sourceRef": "refs/heads/client"
                                }
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.rejectionClass").value("VALIDATION"))
                .andExpect(jsonPath("$.failureCode")
                        .value("REMOTE_SESSION_CLIENT_SELECTOR_REJECTED"))
                .andExpect(jsonPath("$.sessionId").isEmpty())
                .andExpect(jsonPath("$..idempotencyKey").doesNotExist())
                .andExpect(jsonPath("$..worker").doesNotExist())
                .andExpect(jsonPath("$..workspacePath").doesNotExist())
                .andExpect(jsonPath("$.remoteSessionId").isEmpty());
    }

    private DevelopmentChangeResponse response() {
        Instant now = Instant.parse("2026-08-20T19:00:00Z");
        return new DevelopmentChangeResponse(
                CHANGE_KEY,
                7L,
                "Synthetic change",
                DevelopmentChangeStatus.OPEN,
                "refs/heads/main",
                "1".repeat(40),
                "atenea/change-" + CHANGE_KEY,
                "remote:synthetic-worker-01:change:" + CHANGE_KEY,
                "synthetic-worker-01",
                4,
                0,
                "b".repeat(64),
                DevelopmentChangeSourceState.CLEAN,
                "1".repeat(40),
                DevelopmentChangeWorkspaceState.READY,
                1,
                "c".repeat(64),
                true,
                DevelopmentChangeProjectionState.NOT_STARTED,
                DevelopmentChangeProjectionState.NOT_STARTED,
                DevelopmentChangeProjectionState.NOT_STARTED,
                DevelopmentChangeProjectionState.NOT_STARTED,
                null,
                DevelopmentChangePhase.READY,
                true,
                DevelopmentChangeActionResponse.bindSession(),
                now,
                now,
                0);
    }
}
