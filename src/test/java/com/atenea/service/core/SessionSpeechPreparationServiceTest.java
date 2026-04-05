package com.atenea.service.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import com.atenea.api.mobile.MobileSessionActionsResponse;
import com.atenea.api.mobile.MobileSessionBlockerResponse;
import com.atenea.api.mobile.MobileSessionInsightsResponse;
import com.atenea.api.mobile.MobileSessionSummaryResponse;
import com.atenea.api.worksession.SessionDeliverablesViewResponse;
import com.atenea.api.worksession.WorkSessionConversationViewResponse;
import com.atenea.api.worksession.WorkSessionOperationalState;
import com.atenea.api.worksession.WorkSessionResponse;
import com.atenea.api.worksession.WorkSessionViewResponse;
import com.atenea.persistence.worksession.WorkSessionPullRequestStatus;
import com.atenea.persistence.worksession.WorkSessionStatus;
import com.atenea.service.mobile.MobileSessionService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SessionSpeechPreparationServiceTest {

    @Mock
    private MobileSessionService mobileSessionService;

    private SessionSpeechPreparationService service;

    @BeforeEach
    void setUp() {
        service = new SessionSpeechPreparationService(mobileSessionService);
    }

    @Test
    void briefModePrioritizesInsights() {
        when(mobileSessionService.getSessionSummary(44L)).thenReturn(summary(
                false,
                """
                ## Punto actual
                He dejado preparada la landing nueva.

                ## Bloqueo actual
                Sin bloqueo activo.

                ## Siguiente paso recomendado
                Conectar el formulario principal.
                """,
                new MobileSessionInsightsResponse(
                        "He dejado preparada la landing nueva",
                        new MobileSessionBlockerResponse("NONE", "Sin bloqueo activo"),
                        "Conectar el formulario principal")));

        SessionSpeechPreparationResult result = service.prepareLatestResponse(44L, SessionSpeechMode.BRIEF);

        assertEquals(
                "Punto actual: He dejado preparada la landing nueva. Siguiente paso: Conectar el formulario principal.",
                result.text());
        assertEquals(SessionSpeechMode.BRIEF, result.mode());
        assertEquals(List.of("latestProgress", "nextStepRecommended"), result.sectionsUsed());
    }

    @Test
    void briefModeIncludesMeaningfulBlockerAndRunState() {
        when(mobileSessionService.getSessionSummary(55L)).thenReturn(summary(
                true,
                """
                ## Qué he hecho
                - He actualizado [`index.html`](/workspace/repos/sandboxes/smoke/pruebas-inicial/index.html)

                ## Verificación
                Typecheck en verde.
                """,
                new MobileSessionInsightsResponse(
                        "He aplicado el parche principal",
                        new MobileSessionBlockerResponse("TECHNICAL", "Los tests siguen fallando en CI"),
                        "Revisar el stacktrace del test roto")));

        SessionSpeechPreparationResult result = service.prepareLatestResponse(55L, SessionSpeechMode.BRIEF);

        assertEquals(
                "Punto actual: He aplicado el parche principal. Bloqueo técnico: Los tests siguen fallando en CI. Siguiente paso: Revisar el stacktrace del test roto. Archivos tocados: index.html. Verificación: Typecheck en verde. Hay una ejecución en curso.",
                result.text());
        assertTrue(result.sectionsUsed().contains("runInProgress"));
        assertTrue(result.sectionsUsed().contains("touchedFiles"));
        assertTrue(result.sectionsUsed().contains("verification"));
    }

    @Test
    void fullModeCleansMarkdownAndCodeForSpeech() {
        when(mobileSessionService.getSessionSummary(56L)).thenReturn(summary(
                false,
                """
                ## Punto actual
                La nueva landing está preparada.

                - Archivo principal: `/workspace/repos/sandboxes/smoke/pruebas-inicial/index.html`
                - Referencia: [landing principal](/workspace/repos/sandboxes/smoke/pruebas-inicial/index.html)

                ```html
                <form action="/demo"></form>
                ```

                ## Siguiente paso recomendado
                Conectar el formulario principal.
                """,
                new MobileSessionInsightsResponse(
                        "La nueva landing está preparada",
                        new MobileSessionBlockerResponse("NONE", "Sin bloqueo activo"),
                        "Conectar el formulario principal")));

        SessionSpeechPreparationResult result = service.prepareLatestResponse(56L, SessionSpeechMode.FULL);

        assertEquals(SessionSpeechMode.FULL, result.mode());
        assertTrue(result.text().contains("Punto actual:"));
        assertTrue(result.text().contains("La nueva landing está preparada"));
        assertTrue(result.text().contains("Siguiente paso recomendado:"));
        assertTrue(result.text().contains("Conectar el formulario principal"));
        assertTrue(!result.text().contains("ruta de archivo"));
        assertTrue(!result.text().contains("python3 -m http.server"));
    }

    @Test
    void fullModePrefersLatestConversationTurnOverTruncatedSummary() {
        when(mobileSessionService.getSessionSummary(57L)).thenReturn(summary(
                false,
                "## Punto actual La sesión ya está abierta. Ruta actual del proyecto: `/workspace/repos/sandboxes/smoke/pruebas-inicial` ...",
                new MobileSessionInsightsResponse(
                        "La sesión ya está abierta",
                        new MobileSessionBlockerResponse("NONE", "Sin bloqueo activo"),
                        "Indícame la tarea concreta"),
                List.of(
                        new com.atenea.api.worksession.SessionTurnResponse(
                                1L,
                                com.atenea.persistence.worksession.SessionTurnActor.CODEX,
                                """
                                ## Punto actual

                                La sesión del proyecto ya está abierta y lista para seguir trabajando.

                                ## Qué he hecho

                                He retomado el contexto y he dejado todo preparado para continuar.

                                ## Siguiente paso recomendado

                                Indícame la tarea concreta que quieres ejecutar.
                                """,
                                Instant.parse("2026-03-30T10:06:00Z")))));

        SessionSpeechPreparationResult result = service.prepareLatestResponse(57L, SessionSpeechMode.FULL);

        assertTrue(result.text().contains("He retomado el contexto y he dejado todo preparado para continuar"));
        assertTrue(!result.text().contains("Ruta actual del proyecto"));
    }

    private static MobileSessionSummaryResponse summary(
            boolean runInProgress,
            String lastAgentResponse,
            MobileSessionInsightsResponse insights
    ) {
        return summary(runInProgress, lastAgentResponse, insights, List.of());
    }

    private static MobileSessionSummaryResponse summary(
            boolean runInProgress,
            String lastAgentResponse,
            MobileSessionInsightsResponse insights,
            List<com.atenea.api.worksession.SessionTurnResponse> recentTurns
    ) {
        return new MobileSessionSummaryResponse(
                new WorkSessionConversationViewResponse(
                        new WorkSessionViewResponse(
                                new WorkSessionResponse(
                                        44L,
                                        7L,
                                        WorkSessionStatus.OPEN,
                                        runInProgress ? WorkSessionOperationalState.RUNNING : WorkSessionOperationalState.IDLE,
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
                                        new com.atenea.api.worksession.SessionOperationalSnapshotResponse(true, true, "main", false)),
                                runInProgress,
                                true,
                                null,
                                null,
                                lastAgentResponse),
                        recentTurns,
                        20,
                        false),
                new SessionDeliverablesViewResponse(44L, List.of(), false, false, null),
                null,
                new MobileSessionActionsResponse(true, true, true, true, true, true, true),
                insights);
    }
}
