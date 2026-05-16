package com.atenea.api.mobile;

import static org.mockito.ArgumentMatchers.any;
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
import com.atenea.api.rescue.CreateRescueTurnRequest;
import com.atenea.api.rescue.CreateRescueTurnResponse;
import com.atenea.api.rescue.ResolveRescueSessionRequest;
import com.atenea.api.rescue.ResolveRescueSessionResponse;
import com.atenea.api.rescue.RescueSessionConversationViewResponse;
import com.atenea.api.rescue.RescueSessionResponse;
import com.atenea.api.rescue.RescueSessionTurnResponse;
import com.atenea.api.worksession.ApprovedPriceEstimateSummaryResponse;
import com.atenea.api.worksession.CloseWorkSessionConversationViewResponse;
import com.atenea.api.worksession.CreateSessionTurnConversationViewResponse;
import com.atenea.api.worksession.CreateSessionTurnRequest;
import com.atenea.api.worksession.MarkPriceEstimateBilledRequest;
import com.atenea.api.worksession.PublishWorkSessionConversationViewResponse;
import com.atenea.api.worksession.PublishWorkSessionRequest;
import com.atenea.api.worksession.ResolveWorkSessionConversationViewResponse;
import com.atenea.api.worksession.ResolveWorkSessionRequest;
import com.atenea.api.worksession.ResolveWorkSessionResponse;
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
import com.atenea.persistence.rescue.RescueSessionStatus;
import com.atenea.persistence.rescue.RescueSessionTurnActor;
import com.atenea.service.billing.BillingQueueService;
import com.atenea.service.mobile.MobileInboxService;
import com.atenea.service.mobile.MobileProjectOverviewService;
import com.atenea.service.mobile.MobileSessionReadStateService;
import com.atenea.service.mobile.MobileSessionEventService;
import com.atenea.service.mobile.MobileSessionService;
import com.atenea.service.mobile.MobileStreamService;
import com.atenea.service.operations.OperationsService;
import com.atenea.service.rescue.RescueSessionService;
import com.atenea.service.worksession.SessionDeliverableGenerationService;
import com.atenea.service.worksession.SessionDeliverableService;
import com.atenea.service.worksession.SessionTurnService;
import com.atenea.service.worksession.WorkSessionGitHubService;
import com.atenea.service.worksession.WorkSessionService;
import java.time.Instant;
import java.util.List;
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
    private RescueSessionService rescueSessionService;
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
                        rescueSessionService,
                        operationsService))
                .setControllerAdvice(new ApiExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(
                        Jackson2ObjectMapperBuilder.json().build()))
                .build();
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
                                Instant.parse("2026-03-29T10:00:00Z")))));

        mockMvc.perform(get("/api/mobile/projects/overview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].projectId").value(7))
                .andExpect(jsonPath("$[0].session.sessionId").value(12))
                .andExpect(jsonPath("$[0].session.runInProgress").value(true));
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
                        "Publicar la pull request de la sesión")));

        mockMvc.perform(get("/api/mobile/sessions/12/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversation.view.session.id").value(12))
                .andExpect(jsonPath("$.actions.canPublish").value(true))
                .andExpect(jsonPath("$.insights.latestProgress").value("Flujo de voz operativo"))
                .andExpect(jsonPath("$.insights.currentBlocker.category").value("NONE"))
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

    @Test
    void mobileCompatibilityAliasesRemainAvailableForLegacyMutationFlows() throws Exception {
        when(workSessionService.resolveSessionConversationView(7L, new ResolveWorkSessionRequest("Title", null)))
                .thenReturn(new ResolveWorkSessionConversationViewResponse(true, conversation()));
        when(workSessionService.getSessionConversationView(12L)).thenReturn(conversation());
        when(workSessionGitHubService.publishSessionConversationView(12L, new PublishWorkSessionRequest("Ship")))
                .thenReturn(new PublishWorkSessionConversationViewResponse(conversation()));
        when(workSessionGitHubService.syncPullRequestConversationView(12L))
                .thenReturn(new SyncWorkSessionPullRequestConversationViewResponse(conversation()));
        when(workSessionService.closeSessionConversationView(12L))
                .thenReturn(new CloseWorkSessionConversationViewResponse(conversation()));
        when(billingQueueService.getQueue(SessionDeliverableBillingStatus.READY, null, null, null))
                .thenReturn(new BillingQueueResponse(List.of()));
        when(billingQueueService.getQueueSummary(null, null, null, null))
                .thenReturn(new BillingQueueSummaryResponse(0, 0, List.of(), List.of()));
        when(sessionDeliverableService.markPriceEstimateBilled(12L, 81L, new MarkPriceEstimateBilledRequest("INV-1")))
                .thenReturn(new SessionDeliverableResponse(
                        81L, 12L, SessionDeliverableType.PRICE_ESTIMATE, SessionDeliverableStatus.SUCCEEDED, 1, "Pricing",
                        "# Price", "{}", "{}", null, null, "gpt-5.4", "v1", true, Instant.parse("2026-03-29T10:00:00Z"),
                        SessionDeliverableBillingStatus.BILLED, "INV-1", Instant.parse("2026-03-29T10:05:00Z"),
                        Instant.parse("2026-03-29T10:00:00Z"), Instant.parse("2026-03-29T10:05:00Z")));

        mockMvc.perform(post("/api/mobile/projects/7/sessions/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Title"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(true));

        mockMvc.perform(get("/api/mobile/sessions/12/conversation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.view.session.id").value(12));

        mockMvc.perform(post("/api/mobile/sessions/12/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "commitMessage": "Ship"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.view.view.session.id").value(12));

        mockMvc.perform(post("/api/mobile/sessions/12/pull-request/sync"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.view.view.session.id").value(12));

        mockMvc.perform(post("/api/mobile/sessions/12/close"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.view.view.session.id").value(12));

        mockMvc.perform(get("/api/mobile/billing/queue").param("billingStatus", "READY"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/mobile/billing/queue/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.readyCount").value(0));

        mockMvc.perform(post("/api/mobile/sessions/12/deliverables/81/billing/mark-billed")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "billingReference": "INV-1"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.billingStatus").value("BILLED"));
    }

    @Test
    void rescueSessionEndpointsExposeSeparatedOperationalFlow() throws Exception {
        RescueSessionConversationViewResponse rescueConversation = rescueConversation();
        when(rescueSessionService.resolveSession(7L, new ResolveRescueSessionRequest("Repo bloqueado")))
                .thenReturn(new ResolveRescueSessionResponse(true, rescueConversation));
        when(rescueSessionService.getConversation(91L)).thenReturn(rescueConversation);
        when(rescueSessionService.createTurn(91L, new CreateRescueTurnRequest("Revisa el bloqueo")))
                .thenReturn(new CreateRescueTurnResponse(rescueConversation));

        mockMvc.perform(post("/api/mobile/projects/7/rescue-sessions/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Repo bloqueado"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(true))
                .andExpect(jsonPath("$.view.session.id").value(91))
                .andExpect(jsonPath("$.view.session.status").value("OPEN"));

        mockMvc.perform(get("/api/mobile/rescue-sessions/91/conversation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.session.projectId").value(7))
                .andExpect(jsonPath("$.turns[0].actor").value("ATENEA"));

        mockMvc.perform(post("/api/mobile/rescue-sessions/91/turns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "message": "Revisa el bloqueo"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.view.session.id").value(91));
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

    private RescueSessionConversationViewResponse rescueConversation() {
        return new RescueSessionConversationViewResponse(
                new RescueSessionResponse(
                        91L,
                        7L,
                        "Pruebas Inicial",
                        "/workspace/repos/sandboxes/smoke/pruebas-inicial",
                        RescueSessionStatus.OPEN,
                        "Repo bloqueado",
                        true,
                        "thread-rescue",
                        null,
                        Instant.parse("2026-03-29T10:00:00Z"),
                        Instant.parse("2026-03-29T10:01:00Z"),
                        null),
                List.of(new RescueSessionTurnResponse(
                        101L,
                        RescueSessionTurnActor.ATENEA,
                        "Modo rescate abierto",
                        null,
                        Instant.parse("2026-03-29T10:00:00Z"))));
    }
}
