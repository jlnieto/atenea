package com.atenea.api.mobile;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atenea.api.ApiExceptionHandler;
import com.atenea.auth.AuthenticatedOperator;
import com.atenea.api.billing.BillingQueueResponse;
import com.atenea.api.billing.BillingQueueSummaryResponse;
import com.atenea.api.mobile.MobileProjectOverviewResponse.MobileProjectSessionSummaryResponse;
import com.atenea.api.mobile.MobileSessionBlockerResponse;
import com.atenea.api.mobile.MobileSessionInsightsResponse;
import com.atenea.api.worksession.ApprovedPriceEstimateSummaryResponse;
import com.atenea.api.worksession.CloseWorkSessionConversationViewResponse;
import com.atenea.api.worksession.CreateSessionTurnConversationViewResponse;
import com.atenea.api.worksession.CreateSessionTurnRequest;
import com.atenea.api.worksession.MarkPriceEstimateBilledRequest;
import com.atenea.api.worksession.PublishWorkSessionConversationViewResponse;
import com.atenea.api.worksession.PublishWorkSessionRequest;
import com.atenea.api.worksession.SessionDeliverableResponse;
import com.atenea.api.worksession.SessionDeliverablesViewResponse;
import com.atenea.api.worksession.SyncWorkSessionPullRequestConversationViewResponse;
import com.atenea.api.worksession.WorkSessionConversationViewResponse;
import com.atenea.api.worksession.WorkSessionOperationalState;
import com.atenea.api.worksession.WorkSessionResponse;
import com.atenea.api.worksession.WorkSessionViewResponse;
import com.atenea.persistence.worksession.SessionDeliverableBillingStatus;
import com.atenea.persistence.worksession.SessionDeliverableStatus;
import com.atenea.persistence.worksession.SessionDeliverableType;
import com.atenea.persistence.worksession.WorkSessionPullRequestStatus;
import com.atenea.persistence.worksession.WorkSessionStatus;
import com.atenea.service.billing.BillingQueueService;
import com.atenea.service.mobile.MobileInboxService;
import com.atenea.service.mobile.MobileProjectOverviewService;
import com.atenea.service.mobile.MobileSessionReadStateService;
import com.atenea.service.mobile.MobileSessionEventService;
import com.atenea.service.mobile.MobileSessionService;
import com.atenea.service.mobile.MobileStreamService;
import com.atenea.service.operations.OperationsService;
import com.atenea.service.worksession.SessionDeliverableGenerationService;
import com.atenea.service.worksession.SessionDeliverableService;
import com.atenea.service.worksession.SessionTurnService;
import com.atenea.service.worksession.WorkSessionGitHubService;
import com.atenea.service.worksession.WorkSessionService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@ExtendWith(MockitoExtension.class)
class MobileControllerTest {

    @Mock
    private MobileProjectOverviewService mobileProjectOverviewService;
    @Mock
    private MobileInboxService mobileInboxService;
    @Mock
    private MobileSessionService mobileSessionService;
    @Mock
    private MobileSessionReadStateService mobileSessionReadStateService;
    @Mock
    private MobileSessionEventService mobileSessionEventService;
    @Mock
    private MobileStreamService mobileStreamService;
    @Mock
    private WorkSessionService workSessionService;
    @Mock
    private SessionTurnService sessionTurnService;
    @Mock
    private WorkSessionGitHubService workSessionGitHubService;
    @Mock
    private SessionDeliverableService sessionDeliverableService;
    @Mock
    private SessionDeliverableGenerationService sessionDeliverableGenerationService;
    @Mock
    private BillingQueueService billingQueueService;
    @Mock
    private OperationsService operationsService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new MobileController(
                        mobileProjectOverviewService,
                        mobileInboxService,
                        mobileSessionService,
                        mobileSessionReadStateService,
                        mobileSessionEventService,
                        mobileStreamService,
                        workSessionService,
                        sessionTurnService,
                        workSessionGitHubService,
                        sessionDeliverableService,
                        sessionDeliverableGenerationService,
                        billingQueueService,
                        operationsService))
                .setControllerAdvice(new ApiExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(
                        Jackson2ObjectMapperBuilder.json().build()))
                .build();
    }

    @Test
    void independentSessionAndRescueRoutesAreAbsent() throws Exception {
        for (String path : List.of("/api/mobile/projects/7/sessions/resolve",
                "/api/mobile/sessions/17/start-fresh", "/api/mobile/projects/7/rescue-sessions/resolve",
                "/api/mobile/rescue-sessions/91/turns", "/api/mobile/rescue-sessions/91/close")) {
            mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isNotFound());
        }
        mockMvc.perform(get("/api/mobile/rescue-sessions/91/conversation")).andExpect(status().isNotFound());
        org.mockito.Mockito.verifyNoInteractions(workSessionService);
    }

    @Test
    void getProjectOverviewReturnsMobileOverview() throws Exception {
        when(mobileProjectOverviewService.getOverview()).thenReturn(List.of(
                new MobileProjectOverviewResponse(
                        7L,
                        "Atenea",
                        "Repo",
                        "main",
                        new MobileProjectSessionSummaryResponse(
                                12L,
                                WorkSessionStatus.OPEN,
                                "Mobile work",
                                true,
                                null,
                                WorkSessionPullRequestStatus.OPEN,
                                Instant.parse("2026-03-29T10:00:00Z"),
                                false))));

        mockMvc.perform(get("/api/mobile/projects/overview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].projectId").value(7))
                .andExpect(jsonPath("$[0].session.sessionId").value(12))
                .andExpect(jsonPath("$[0].session.runInProgress").value(true))
                .andExpect(jsonPath("$[0].session.recoveryPending").value(false));
    }

    @Test
    void getInboxReturnsItemsAndSummary() throws Exception {
        when(mobileInboxService.getInbox()).thenReturn(new MobileInboxResponse(
                List.of(new MobileInboxItemResponse(
                        "CLOSE_BLOCKED",
                        "warning",
                        "Close blocked",
                        "Repository dirty",
                        "Clean worktree",
                        7L,
                        "Atenea",
                        12L,
                        "Mobile work",
                        null,
                        null,
                        null,
                        Instant.parse("2026-03-29T10:00:00Z"))),
                new MobileInboxSummaryResponse(1, 1, 0, 0, 2)));

        mockMvc.perform(get("/api/mobile/inbox"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].type").value("CLOSE_BLOCKED"))
                .andExpect(jsonPath("$.summary.billingReadyCount").value(2));
    }

    @Test
    void getSessionSummaryReturnsConversationAndActions() throws Exception {
        when(mobileSessionService.getSessionSummary(12L)).thenReturn(new MobileSessionSummaryResponse(
                conversation(),
                new SessionDeliverablesViewResponse(12L, List.of(), false, false, null),
                new ApprovedPriceEstimateSummaryResponse(
                        12L, 81L, 1, "Pricing", "EUR", 43.0, 6.5, 240.0, 279.0, 320.0,
                        "competitive", "low", "medium", List.of("A"), List.of("B"),
                        SessionDeliverableBillingStatus.READY, null, null,
                        Instant.parse("2026-03-29T10:00:00Z"), Instant.parse("2026-03-29T10:00:00Z")),
                new MobileSessionActionsResponse(true, true, true, true, true, true, true),
                new MobileSessionInsightsResponse(
                        "Flujo de voz operativo",
                        new MobileSessionBlockerResponse("NONE", "Sin bloqueo activo"),
                        "Publicar la pull request de la sesión"),
                new MobileSessionOperatorStateResponse(
                        true,
                        MobileSessionOperatorState.CLOSED_OWNER_BLOCKS_CAPACITY,
                        "Bloqueada por una sesión cerrada",
                        "Otra sesión cerrada conserva la capacidad necesaria.",
                        MobileSessionPrimaryAction.RECONCILE_REMOTE_CLOSE,
                        "Reconciliar cierre",
                        true,
                        com.atenea.persistence.auth.CodexOperationsRole.PLATFORM_ADMINISTRATOR,
                        16L,
                        96L)));

        mockMvc.perform(get("/api/mobile/sessions/12/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversation.view.session.id").value(12))
                .andExpect(jsonPath("$.actions.canPublish").value(true))
                .andExpect(jsonPath("$.insights.latestProgress").value("Flujo de voz operativo"))
                .andExpect(jsonPath("$.insights.currentBlocker.category").value("NONE"))
                .andExpect(jsonPath("$.operatorState.state").value("CLOSED_OWNER_BLOCKS_CAPACITY"))
                .andExpect(jsonPath("$.operatorState.title").value("Bloqueada por una sesión cerrada"))
                .andExpect(jsonPath("$.operatorState.primaryAction").value("RECONCILE_REMOTE_CLOSE"))
                .andExpect(jsonPath("$.operatorState.requiredRole").value("PLATFORM_ADMINISTRATOR"))
                .andExpect(jsonPath("$.operatorState.targetWorkSessionId").value(16))
                .andExpect(jsonPath("$.operatorState.workerId").doesNotExist())
                .andExpect(jsonPath("$.operatorState.workspaceIdentity").doesNotExist())
                .andExpect(jsonPath("$.operatorState.operationId").doesNotExist())
                .andExpect(jsonPath("$.approvedPriceEstimate.billingStatus").value("READY"));
    }

    @Test
    void getAndMarkSessionReadStateUseAuthenticatedOperator() throws Exception {
        var principal = SecurityMockMvcRequestPostProcessors.authentication(
                new UsernamePasswordAuthenticationToken(
                        new AuthenticatedOperator(4L, "operator@atenea.local", "Operator"),
                        null));
        MobileSessionReadStateResponse response = new MobileSessionReadStateResponse(
                12L,
                Instant.parse("2026-03-29T10:00:00Z"),
                Instant.parse("2026-03-29T10:05:00Z"));
        when(mobileSessionReadStateService.getReadStates(any())).thenReturn(List.of(response));
        when(mobileSessionReadStateService.markRead(any(), any())).thenReturn(response);

        mockMvc.perform(get("/api/mobile/session-read-states")
                        .with(principal))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].sessionId").value(12))
                .andExpect(jsonPath("$[0].lastSeenActivityAt").isNumber());

        mockMvc.perform(post("/api/mobile/sessions/12/mark-read")
                        .with(principal))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value(12))
                .andExpect(jsonPath("$.updatedAt").isNumber());
    }


    @Test
    void getSessionEventsReturnsEventFeed() throws Exception {
        when(mobileSessionEventService.getEvents(12L, null, null)).thenReturn(new MobileSessionEventsResponse(
                12L,
                List.of(new MobileSessionEventResponse(
                        "RUN_SUCCEEDED",
                        Instant.parse("2026-03-29T10:00:00Z"),
                        "Run succeeded",
                        "Summary",
                        55L,
                        77L,
                        null)),
                Instant.parse("2026-03-29T10:01:00Z")));

        mockMvc.perform(get("/api/mobile/sessions/12/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value(12))
                .andExpect(jsonPath("$.events[0].type").value("RUN_SUCCEEDED"));
    }

    @Test
    void streamEndpointsStartAsyncSseResponses() throws Exception {
        when(mobileStreamService.streamInbox()).thenReturn(new SseEmitter());
        when(mobileStreamService.streamSessionEvents(12L)).thenReturn(new SseEmitter());

        mockMvc.perform(get("/api/mobile/inbox/stream"))
                .andExpect(request().asyncStarted());

        mockMvc.perform(get("/api/mobile/sessions/12/events/stream"))
                .andExpect(request().asyncStarted());
    }



    private WorkSessionConversationViewResponse conversation() {
        return new WorkSessionConversationViewResponse(
                new WorkSessionViewResponse(
                        new WorkSessionResponse(
                                12L,
                                7L,
                                WorkSessionStatus.OPEN,
                                WorkSessionOperationalState.IDLE,
                                "Mobile work",
                                "main",
                                "atenea/session-12",
                                null,
                                null,
                                WorkSessionPullRequestStatus.NOT_CREATED,
                                null,
                                Instant.parse("2026-03-29T09:00:00Z"),
                                Instant.parse("2026-03-29T10:00:00Z"),
                                null,
                                null,
                                null,
                                null,
                                null,
                                false,
                                new com.atenea.api.worksession.SessionOperationalSnapshotResponse(true, true, "atenea/session-12", false)),
                        false,
                        true,
                        null,
                        null,
                        null),
                List.of(),
                20,
                false);
    }

}
