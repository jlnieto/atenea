package com.atenea.service.mobile;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

import com.atenea.api.mobile.MobileSessionInsightsResponse;
import com.atenea.api.mobile.MobileSessionSummaryResponse;
import com.atenea.api.worksession.SessionDeliverablesViewResponse;
import com.atenea.api.worksession.SessionTurnResponse;
import com.atenea.api.worksession.WorkSessionConversationViewResponse;
import com.atenea.api.worksession.WorkSessionOperationalState;
import com.atenea.api.worksession.WorkSessionResponse;
import com.atenea.api.worksession.WorkSessionViewResponse;
import com.atenea.persistence.worksession.SessionTurnActor;
import com.atenea.persistence.worksession.WorkSessionPullRequestStatus;
import com.atenea.persistence.worksession.WorkSessionStatus;
import com.atenea.service.worksession.ApprovedPriceEstimateNotFoundException;
import com.atenea.service.worksession.SessionDeliverableService;
import com.atenea.service.worksession.WorkSessionService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MobileSessionServiceTest {

    @Mock
    private WorkSessionService workSessionService;

    @Mock
    private SessionDeliverableService sessionDeliverableService;

    private MobileSessionService mobileSessionService;

    @BeforeEach
    void setUp() {
        mobileSessionService = new MobileSessionService(
                workSessionService,
                sessionDeliverableService,
                new MobileSessionInsightsService());
    }

    @Test
    void getSessionSummaryReturnsNullApprovedPriceEstimateWhenNoApprovedPricingExists() {
        when(workSessionService.getSessionConversationView(12L)).thenReturn(conversation());
        when(sessionDeliverableService.getApprovedDeliverablesView(12L))
                .thenReturn(new SessionDeliverablesViewResponse(12L, List.of(), false, false, null));
        when(sessionDeliverableService.getApprovedPriceEstimateSummary(12L))
                .thenThrow(new ApprovedPriceEstimateNotFoundException(12L));

        MobileSessionSummaryResponse response = mobileSessionService.getSessionSummary(12L);

        assertNull(response.approvedPriceEstimate());
        assertTrue(response.actions().canCreateTurn());
    }

    @Test
    void getSessionSummaryDoesNotExposeCloseForUnpublishedSession() {
        when(workSessionService.getSessionConversationView(12L)).thenReturn(conversation());
        when(sessionDeliverableService.getApprovedDeliverablesView(12L))
                .thenReturn(new SessionDeliverablesViewResponse(12L, List.of(), false, false, null));
        when(sessionDeliverableService.getApprovedPriceEstimateSummary(12L))
                .thenThrow(new ApprovedPriceEstimateNotFoundException(12L));

        MobileSessionSummaryResponse response = mobileSessionService.getSessionSummary(12L);

        assertFalse(response.actions().canClose());
    }

    @Test
    void getSessionSummaryIncludesStructuredInsights() {
        when(workSessionService.getSessionConversationView(12L)).thenReturn(conversationWithInsights());
        when(sessionDeliverableService.getApprovedDeliverablesView(12L))
                .thenReturn(new SessionDeliverablesViewResponse(12L, List.of(), false, false, null));
        when(sessionDeliverableService.getApprovedPriceEstimateSummary(12L))
                .thenThrow(new ApprovedPriceEstimateNotFoundException(12L));

        MobileSessionSummaryResponse response = mobileSessionService.getSessionSummary(12L);
        MobileSessionInsightsResponse insights = response.insights();

        assertEquals("La migración a Core ya está integrada", insights.latestProgress());
        assertEquals("BUSINESS", insights.currentBlocker().category());
        assertEquals("Pendiente de validación del cliente sobre la copy final", insights.currentBlocker().summary());
        assertEquals("Conectar el formulario principal a un destino real", insights.nextStepRecommended());
    }

    private static WorkSessionConversationViewResponse conversation() {
        WorkSessionResponse session = new WorkSessionResponse(
                12L,
                7L,
                WorkSessionStatus.OPEN,
                WorkSessionOperationalState.IDLE,
                "Mobile work",
                "main",
                "atenea/session-12",
                "thread-12",
                null,
                WorkSessionPullRequestStatus.NOT_CREATED,
                null,
                Instant.parse("2026-03-29T10:00:00Z"),
                Instant.parse("2026-03-29T10:01:00Z"),
                null,
                null,
                null,
                null,
                null,
                false,
                new com.atenea.api.worksession.SessionOperationalSnapshotResponse(true, true, "atenea/session-12", false)
        );
        WorkSessionViewResponse view = new WorkSessionViewResponse(session, false, true, null, null, null);
        return new WorkSessionConversationViewResponse(view, List.of(), 20, false);
    }

    private static WorkSessionConversationViewResponse conversationWithInsights() {
        WorkSessionResponse session = new WorkSessionResponse(
                12L,
                7L,
                WorkSessionStatus.OPEN,
                WorkSessionOperationalState.IDLE,
                "Mobile work",
                "main",
                "atenea/session-12",
                "thread-12",
                null,
                WorkSessionPullRequestStatus.NOT_CREATED,
                null,
                Instant.parse("2026-03-29T10:00:00Z"),
                Instant.parse("2026-03-29T10:01:00Z"),
                null,
                null,
                null,
                null,
                null,
                false,
                new com.atenea.api.worksession.SessionOperationalSnapshotResponse(true, true, "atenea/session-12", false)
        );
        WorkSessionViewResponse view = new WorkSessionViewResponse(
                session,
                false,
                true,
                null,
                null,
                """
                ## Punto actual
                La migración a Core ya está integrada.

                ## Bloqueo actual
                Pendiente de validación del cliente sobre la copy final.

                ## Siguiente paso recomendado
                El siguiente paso útil es conectar el formulario principal a un destino real.
                """
        );
        return new WorkSessionConversationViewResponse(
                view,
                List.of(new SessionTurnResponse(
                        91L,
                        SessionTurnActor.ATENEA,
                        "Bloqueo actual: Pendiente de validación del cliente sobre la copy final.",
                        Instant.parse("2026-03-29T10:01:30Z"))),
                20,
                false);
    }
}
