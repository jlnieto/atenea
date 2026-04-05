package com.atenea.service.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.atenea.api.mobile.MobileSessionActionsResponse;
import com.atenea.api.mobile.MobileSessionBlockerResponse;
import com.atenea.api.mobile.MobileSessionInsightsResponse;
import com.atenea.api.mobile.MobileSessionSummaryResponse;
import com.atenea.api.project.ProjectOverviewResponse;
import com.atenea.api.project.ProjectResponse;
import com.atenea.api.worksession.ApprovedPriceEstimateSummaryResponse;
import com.atenea.api.worksession.CreateSessionTurnRequest;
import com.atenea.api.worksession.MarkPriceEstimateBilledRequest;
import com.atenea.api.worksession.ResolveWorkSessionConversationViewResponse;
import com.atenea.api.worksession.ResolveWorkSessionRequest;
import com.atenea.api.worksession.SessionDeliverableResponse;
import com.atenea.api.worksession.SessionDeliverableSummaryResponse;
import com.atenea.api.worksession.SessionDeliverablesViewResponse;
import com.atenea.api.worksession.SessionOperationalSnapshotResponse;
import com.atenea.api.worksession.SessionTurnResponse;
import com.atenea.api.worksession.WorkSessionConversationViewResponse;
import com.atenea.api.worksession.WorkSessionOperationalState;
import com.atenea.api.worksession.WorkSessionResponse;
import com.atenea.api.worksession.WorkSessionViewResponse;
import com.atenea.persistence.core.CoreDomain;
import com.atenea.persistence.core.CoreResultType;
import com.atenea.persistence.core.CoreRiskLevel;
import com.atenea.persistence.core.CoreTargetType;
import com.atenea.persistence.worksession.SessionDeliverableBillingStatus;
import com.atenea.persistence.worksession.SessionDeliverableStatus;
import com.atenea.persistence.worksession.SessionDeliverableType;
import com.atenea.persistence.worksession.SessionTurnActor;
import com.atenea.persistence.worksession.WorkSessionPullRequestStatus;
import com.atenea.persistence.worksession.WorkSessionStatus;
import com.atenea.service.mobile.MobileSessionService;
import com.atenea.service.project.ProjectOverviewService;
import com.atenea.service.worksession.SessionDeliverableGenerationService;
import com.atenea.service.worksession.SessionDeliverableService;
import com.atenea.service.worksession.SessionTurnService;
import com.atenea.service.worksession.WorkSessionGitHubService;
import com.atenea.service.worksession.WorkSessionService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DevelopmentCoreDomainHandlerTest {

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
    private ProjectOverviewService projectOverviewService;

    @Mock
    private MobileSessionService mobileSessionService;

    @Mock
    private CoreOperatorContextService coreOperatorContextService;

    private DevelopmentCoreDomainHandler handler;

    @BeforeEach
    void setUp() {
        handler = new DevelopmentCoreDomainHandler(
                workSessionService,
                sessionTurnService,
                workSessionGitHubService,
                sessionDeliverableService,
                sessionDeliverableGenerationService,
                projectOverviewService,
                mobileSessionService,
                coreOperatorContextService);
    }

    @Test
    void createWorkSessionDelegatesToResolveConversationView() {
        WorkSessionConversationViewResponse conversationView = conversationView(44L);
        when(workSessionService.resolveSessionConversationView(7L, new ResolveWorkSessionRequest("Nueva sesion", null)))
                .thenReturn(new ResolveWorkSessionConversationViewResponse(true, conversationView));

        CoreCommandExecutionResult result = handler.execute(
                new CoreIntentEnvelope(
                        "CREATE_WORK_SESSION",
                        CoreDomain.DEVELOPMENT,
                        "create_work_session",
                        Map.of("projectId", 7L, "title", "Nueva sesion"),
                        BigDecimal.valueOf(0.90),
                        CoreRiskLevel.READ,
                        false),
                new CoreExecutionContext(101L, 7L, null, "default", false, null));

        assertEquals(CoreResultType.WORK_SESSION_CONVERSATION_VIEW, result.resultType());
        assertEquals(CoreTargetType.WORK_SESSION, result.targetType());
        assertEquals(44L, result.targetId());
    }

    @Test
    void continueWorkSessionDelegatesToTurnCreationAndConversationRead() {
        WorkSessionConversationViewResponse conversationView = conversationView(
                12L,
                """
                ## Punto actual
                Estoy en la sesión activa y ya he retomado el trabajo.

                ## Siguiente paso recomendado
                Revisar la siguiente tarea.
                """);
        when(workSessionService.getSessionConversationView(12L)).thenReturn(conversationView);

        CoreCommandExecutionResult result = handler.execute(
                new CoreIntentEnvelope(
                        "CONTINUE_WORK_SESSION",
                        CoreDomain.DEVELOPMENT,
                        "continue_work_session",
                        Map.of("workSessionId", 12L, "message", "Sigue con esto"),
                        BigDecimal.valueOf(0.99),
                        CoreRiskLevel.READ,
                        false),
                new CoreExecutionContext(101L, 7L, 12L, "default", false, null));

        verify(sessionTurnService).createTurn(12L, new CreateSessionTurnRequest("Sigue con esto"));
        verify(workSessionService).getSessionConversationView(12L);
        assertEquals(CoreResultType.WORK_SESSION_CONVERSATION_VIEW, result.resultType());
        assertEquals(12L, result.targetId());
        assertEquals("Codex responde: Estoy en la sesión activa y ya he retomado el trabajo", result.speakableMessage());
    }

    @Test
    void getProjectOverviewDelegatesToProjectOverviewService() {
        when(projectOverviewService.getOverview()).thenReturn(List.of(projectOverview(7L)));

        CoreCommandExecutionResult result = handler.execute(
                new CoreIntentEnvelope(
                        "GET_PROJECT_OVERVIEW",
                        CoreDomain.DEVELOPMENT,
                        "get_project_overview",
                        Map.of("projectId", 7L),
                        BigDecimal.valueOf(0.95),
                        CoreRiskLevel.READ,
                        false),
                new CoreExecutionContext(101L, 7L, null, "default", false, null));

        assertEquals(CoreResultType.PROJECT_OVERVIEW, result.resultType());
        assertEquals(CoreTargetType.PROJECT, result.targetType());
        assertEquals(7L, result.targetId());
        assertEquals("Atenea: sesión abierta. Título Trabajo. Sin pull request.",
                result.speakableMessage());
    }

    @Test
    void listProjectsOverviewBuildsSpokenSummaryFromOverview() {
        when(projectOverviewService.getOverview()).thenReturn(List.of(
                projectOverview(7L),
                new ProjectOverviewResponse(
                        new ProjectResponse(
                                8L,
                                "Hermes",
                                "Automation",
                                "/repos/hermes",
                                "main",
                                Instant.parse("2026-03-30T10:00:00Z"),
                                Instant.parse("2026-03-30T10:00:00Z")),
                        null)));

        CoreCommandExecutionResult result = handler.execute(
                new CoreIntentEnvelope(
                        "LIST_PROJECTS_OVERVIEW",
                        CoreDomain.DEVELOPMENT,
                        "list_projects_overview",
                        Map.of(),
                        BigDecimal.valueOf(0.95),
                        CoreRiskLevel.READ,
                        false),
                new CoreExecutionContext(101L, null, null, "default", false, null));

        assertEquals(
                "Hay 2 proyectos registrados. Atenea: sesión abierta. Título Trabajo. Sin pull request. Hermes: sin sesión activa.",
                result.speakableMessage());
    }

    @Test
    void getSessionSummaryBuildsDevelopmentProgressSummary() {
        when(mobileSessionService.getSessionSummary(44L)).thenReturn(new MobileSessionSummaryResponse(
                new WorkSessionConversationViewResponse(
                        new WorkSessionViewResponse(
                                new WorkSessionResponse(
                                        44L,
                                        7L,
                                        WorkSessionStatus.OPEN,
                                        WorkSessionOperationalState.IDLE,
                                        "Nueva",
                                        "main",
                                        "atenea/session-44",
                                        "thread-1",
                                        null,
                                        WorkSessionPullRequestStatus.NOT_CREATED,
                                        null,
                                        Instant.parse("2026-03-30T10:00:00Z"),
                                        Instant.parse("2026-03-30T10:05:00Z"),
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        false,
                                        new SessionOperationalSnapshotResponse(true, true, "main", false)),
                                false,
                                true,
                                null,
                                null,
                                """
                                ## Punto actual
                                Se ha completado la implementación del flujo de voz.

                                ## Siguiente paso recomendado
                                Publicar la PR de la sesión.
                                """),
                        List.of(
                                new SessionTurnResponse(
                                        90L,
                                        SessionTurnActor.ATENEA,
                                        "Flujo de voz operativo y pendiente de revisión final.",
                                        Instant.parse("2026-03-30T10:05:00Z"))),
                        20,
                        false),
                new SessionDeliverablesViewResponse(
                        44L,
                        List.of(new SessionDeliverableSummaryResponse(
                                301L,
                                SessionDeliverableType.WORK_TICKET,
                                SessionDeliverableStatus.SUCCEEDED,
                                1,
                                "Ticket",
                                true,
                                Instant.parse("2026-03-30T10:04:00Z"),
                                Instant.parse("2026-03-30T10:04:00Z"),
                                "Resumen",
                                301L)),
                        true,
                        true,
                        Instant.parse("2026-03-30T10:04:00Z")),
                new ApprovedPriceEstimateSummaryResponse(
                        44L,
                        501L,
                        1,
                        "Presupuesto inicial",
                        "EUR",
                        80.0,
                        10.0,
                        800.0,
                        900.0,
                        1000.0,
                        "standard",
                        "medium",
                        "high",
                        List.of(),
                        List.of(),
                        SessionDeliverableBillingStatus.READY,
                        null,
                        null,
                        Instant.parse("2026-03-30T10:04:30Z"),
                        Instant.parse("2026-03-30T10:04:30Z")),
                new MobileSessionActionsResponse(true, true, false, false, true, true, true),
                new MobileSessionInsightsResponse(
                        "Se ha completado la implementación del flujo de voz",
                        new MobileSessionBlockerResponse("NONE", "Sin bloqueo activo"),
                        "Publicar la PR de la sesión")));

        CoreCommandExecutionResult result = handler.execute(
                new CoreIntentEnvelope(
                        "GET_SESSION_SUMMARY",
                        CoreDomain.DEVELOPMENT,
                        "get_session_summary",
                        Map.of("projectId", 7L, "workSessionId", 44L),
                        BigDecimal.valueOf(0.95),
                        CoreRiskLevel.READ,
                        false),
                new CoreExecutionContext(101L, 7L, 44L, "default", false, null));

        assertEquals(CoreResultType.WORK_SESSION_SUMMARY, result.resultType());
        assertEquals(
                "La sesión Nueva está abierta. No hay una ejecución en curso. Último avance: Se ha completado la implementación del flujo de voz. Bloqueo actual: Sin bloqueo activo. Siguiente paso recomendado: Publicar la PR de la sesión. Sin pull request. Hay 1 entregable aprobado. El presupuesto aprobado está listo para facturar.",
                result.speakableMessage());
    }

    @Test
    void getSessionSummaryClassifiesTechnicalBlocker() {
        when(mobileSessionService.getSessionSummary(55L)).thenReturn(new MobileSessionSummaryResponse(
                new WorkSessionConversationViewResponse(
                        new WorkSessionViewResponse(
                                new WorkSessionResponse(
                                        55L,
                                        7L,
                                        WorkSessionStatus.OPEN,
                                        WorkSessionOperationalState.IDLE,
                                        "Bugfix",
                                        "main",
                                        "atenea/session-55",
                                        "thread-55",
                                        null,
                                        WorkSessionPullRequestStatus.NOT_CREATED,
                                        null,
                                        Instant.parse("2026-03-30T10:00:00Z"),
                                        Instant.parse("2026-03-30T10:05:00Z"),
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        false,
                                        new SessionOperationalSnapshotResponse(true, true, "main", false)),
                                false,
                                true,
                                null,
                                "Build failed because tests are still red in CI",
                                "Se aplicó el parche principal."),
                        List.of(),
                        20,
                        false),
                new SessionDeliverablesViewResponse(55L, List.of(), false, false, null),
                null,
                new MobileSessionActionsResponse(true, true, false, false, true, true, false),
                new MobileSessionInsightsResponse(
                        "Se aplicó el parche principal",
                        new MobileSessionBlockerResponse("TECHNICAL", "Build failed because tests are still red in CI"),
                        "Publicar la pull request de la sesión")));

        CoreCommandExecutionResult result = handler.execute(
                new CoreIntentEnvelope(
                        "GET_SESSION_SUMMARY",
                        CoreDomain.DEVELOPMENT,
                        "get_session_summary",
                        Map.of("projectId", 7L, "workSessionId", 55L),
                        BigDecimal.valueOf(0.95),
                        CoreRiskLevel.READ,
                        false),
                new CoreExecutionContext(101L, 7L, 55L, "default", false, null));

        assertEquals(
                "La sesión Bugfix está abierta. No hay una ejecución en curso. Último avance: Se aplicó el parche principal. Bloqueo actual: Técnico. Build failed because tests are still red in CI. Siguiente paso recomendado: Publicar la pull request de la sesión. Sin pull request.",
                result.speakableMessage());
    }

    @Test
    void getSessionSummaryClassifiesBusinessBlocker() {
        when(mobileSessionService.getSessionSummary(56L)).thenReturn(new MobileSessionSummaryResponse(
                new WorkSessionConversationViewResponse(
                        new WorkSessionViewResponse(
                                new WorkSessionResponse(
                                        56L,
                                        7L,
                                        WorkSessionStatus.OPEN,
                                        WorkSessionOperationalState.IDLE,
                                        "Landing",
                                        "main",
                                        "atenea/session-56",
                                        "thread-56",
                                        null,
                                        WorkSessionPullRequestStatus.NOT_CREATED,
                                        null,
                                        Instant.parse("2026-03-30T10:00:00Z"),
                                        Instant.parse("2026-03-30T10:05:00Z"),
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        false,
                                        new SessionOperationalSnapshotResponse(true, true, "main", false)),
                                false,
                                true,
                                null,
                                null,
                                """
                                ## Punto actual
                                La nueva landing ya está preparada.

                                ## Bloqueo actual
                                Pendiente de validación del cliente sobre el copy final.

                                ## Siguiente paso recomendado
                                Esperar la aprobación final del cliente.
                                """),
                        List.of(),
                        20,
                        false),
                new SessionDeliverablesViewResponse(56L, List.of(), false, false, null),
                null,
                new MobileSessionActionsResponse(true, true, false, false, true, true, false),
                new MobileSessionInsightsResponse(
                        "La nueva landing ya está preparada",
                        new MobileSessionBlockerResponse("BUSINESS", "Pendiente de validación del cliente sobre el copy final"),
                        "Esperar la aprobación final del cliente")));

        CoreCommandExecutionResult result = handler.execute(
                new CoreIntentEnvelope(
                        "GET_SESSION_SUMMARY",
                        CoreDomain.DEVELOPMENT,
                        "get_session_summary",
                        Map.of("projectId", 7L, "workSessionId", 56L),
                        BigDecimal.valueOf(0.95),
                        CoreRiskLevel.READ,
                        false),
                new CoreExecutionContext(101L, 7L, 56L, "default", false, null));

        assertEquals(
                "La sesión Landing está abierta. No hay una ejecución en curso. Último avance: La nueva landing ya está preparada. Bloqueo actual: De negocio. Pendiente de validación del cliente sobre el copy final. Siguiente paso recomendado: Esperar la aprobación final del cliente. Sin pull request.",
                result.speakableMessage());
    }

    @Test
    void approveSessionDeliverableDelegatesToService() {
        when(sessionDeliverableService.approveDeliverable(44L, 301L))
                .thenReturn(deliverableResponse(
                        301L,
                        44L,
                        SessionDeliverableType.WORK_TICKET,
                        true,
                        SessionDeliverableBillingStatus.READY,
                        null));

        CoreCommandExecutionResult result = handler.execute(
                new CoreIntentEnvelope(
                        "APPROVE_SESSION_DELIVERABLE",
                        CoreDomain.DEVELOPMENT,
                        "approve_session_deliverable",
                        Map.of("projectId", 7L, "workSessionId", 44L, "deliverableId", 301L),
                        BigDecimal.valueOf(0.95),
                        CoreRiskLevel.SAFE_WRITE,
                        true),
                new CoreExecutionContext(101L, 7L, 44L, "default", false, null));

        verify(sessionDeliverableService).approveDeliverable(44L, 301L);
        assertEquals(CoreResultType.SESSION_DELIVERABLE, result.resultType());
        assertEquals(CoreTargetType.SESSION_DELIVERABLE, result.targetType());
        assertEquals(301L, result.targetId());
        assertEquals("He aprobado correctamente el entregable solicitado.", result.speakableMessage());
    }

    @Test
    void markPriceEstimateBilledDelegatesToService() {
        when(sessionDeliverableService.markPriceEstimateBilled(
                44L,
                501L,
                new MarkPriceEstimateBilledRequest("INV-2026-001")))
                .thenReturn(deliverableResponse(
                        501L,
                        44L,
                        SessionDeliverableType.PRICE_ESTIMATE,
                        true,
                        SessionDeliverableBillingStatus.BILLED,
                        "INV-2026-001"));

        CoreCommandExecutionResult result = handler.execute(
                new CoreIntentEnvelope(
                        "MARK_PRICE_ESTIMATE_BILLED",
                        CoreDomain.DEVELOPMENT,
                        "mark_price_estimate_billed",
                        Map.of(
                                "projectId", 7L,
                                "workSessionId", 44L,
                                "deliverableId", 501L,
                                "billingReference", "INV-2026-001"),
                        BigDecimal.valueOf(0.95),
                        CoreRiskLevel.SAFE_WRITE,
                        true),
                new CoreExecutionContext(101L, 7L, 44L, "default", false, null));

        verify(sessionDeliverableService).markPriceEstimateBilled(
                44L,
                501L,
                new MarkPriceEstimateBilledRequest("INV-2026-001"));
        assertEquals(CoreResultType.SESSION_DELIVERABLE, result.resultType());
        assertEquals(CoreTargetType.SESSION_DELIVERABLE, result.targetType());
        assertEquals(501L, result.targetId());
        assertEquals("He marcado correctamente el presupuesto aprobado como facturado.", result.speakableMessage());
    }

    private static WorkSessionConversationViewResponse conversationView(Long sessionId) {
        return conversationView(sessionId, null);
    }

    private static WorkSessionConversationViewResponse conversationView(Long sessionId, String lastAgentResponse) {
        return new WorkSessionConversationViewResponse(
                new WorkSessionViewResponse(
                        new WorkSessionResponse(
                                sessionId,
                                7L,
                                WorkSessionStatus.OPEN,
                                WorkSessionOperationalState.IDLE,
                                "Trabajo",
                                "main",
                                "atenea/session-" + sessionId,
                                "thread-1",
                                null,
                                WorkSessionPullRequestStatus.NOT_CREATED,
                                null,
                                Instant.parse("2026-03-30T10:00:00Z"),
                                Instant.parse("2026-03-30T10:00:00Z"),
                                null,
                                null,
                                null,
                                null,
                                null,
                                false,
                                new SessionOperationalSnapshotResponse(true, true, "main", false)),
                        false,
                        true,
                        null,
                        null,
                        lastAgentResponse),
                List.of(),
                20,
                false);
    }

    private static SessionDeliverableResponse deliverableResponse(
            Long deliverableId,
            Long sessionId,
            SessionDeliverableType type,
            boolean approved,
            SessionDeliverableBillingStatus billingStatus,
            String billingReference
    ) {
        return new SessionDeliverableResponse(
                deliverableId,
                sessionId,
                type,
                SessionDeliverableStatus.SUCCEEDED,
                1,
                type.name() + " v1",
                "# Deliverable",
                "{}",
                "{}",
                null,
                null,
                "gpt-5.4",
                "v1",
                approved,
                approved ? Instant.parse("2026-03-30T10:06:00Z") : null,
                billingStatus,
                billingReference,
                billingStatus == SessionDeliverableBillingStatus.BILLED
                        ? Instant.parse("2026-03-30T10:07:00Z")
                        : null,
                Instant.parse("2026-03-30T10:05:00Z"),
                Instant.parse("2026-03-30T10:07:00Z"));
    }

    private static ProjectOverviewResponse projectOverview(Long projectId) {
        return new ProjectOverviewResponse(
                new ProjectResponse(
                        projectId,
                        "Atenea",
                        "Core backend",
                        "/repos/atenea",
                        "main",
                        Instant.parse("2026-03-30T10:00:00Z"),
                        Instant.parse("2026-03-30T10:00:00Z")),
                new ProjectOverviewResponse.WorkSessionOverviewResponse(
                        44L,
                        true,
                        WorkSessionStatus.OPEN,
                        "Trabajo",
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
                        "main",
                        false,
                        null,
                        Instant.parse("2026-03-30T10:00:00Z"),
                        Instant.parse("2026-03-30T10:00:00Z"),
                        null));
    }
}
