package com.atenea.service.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
import com.atenea.persistence.core.SessionSpeechBriefingCacheEntity;
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

    @Mock
    private SessionSpeechBriefingClient briefingClient;

    @Mock
    private SessionSpeechBriefingCacheService briefingCacheService;

    private SessionSpeechBriefingProperties briefingProperties;

    private SessionSpeechPreparationService service;

    @BeforeEach
    void setUp() {
        briefingProperties = new SessionSpeechBriefingProperties();
        service = new SessionSpeechPreparationService(
                mobileSessionService,
                List.of(briefingClient),
                briefingProperties,
                briefingCacheService);
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
    void briefModeUsesDeepSeekBriefingWhenEnabled() {
        briefingProperties.setEnabled(true);
        when(briefingClient.supports("deepseek")).thenReturn(true);
        when(briefingClient.createBriefing(any())).thenReturn(new SessionSpeechBriefingResult(
                "Codex ha dejado el cambio listo y las pruebas principales pasan. El siguiente paso es publicar.",
                "deepseek",
                "deepseek-v4-flash"));
        when(mobileSessionService.getSessionSummary(44L)).thenReturn(summary(
                false,
                """
                ## Qué he hecho
                He aplicado el cambio.

                ## Verificación
                Tests en verde.
                """,
                new MobileSessionInsightsResponse(
                        "He aplicado el cambio",
                        new MobileSessionBlockerResponse("NONE", "Sin bloqueo activo"),
                        "Publicar la pull request")));

        SessionSpeechPreparationResult result = service.prepareLatestResponse(44L, SessionSpeechMode.BRIEF);

        assertEquals(
                "Codex ha dejado el cambio listo y las pruebas principales pasan. El siguiente paso es publicar.",
                result.text());
        assertEquals(List.of("briefing", "briefing:deepseek", "model:deepseek-v4-flash"), result.sectionsUsed());
        verify(briefingClient).createBriefing(any());
        verify(briefingCacheService).save(
                eq(44L),
                eq(SessionSpeechMode.BRIEF),
                eq("deepseek"),
                eq("deepseek-v4-flash"),
                eq("session-speech-briefing-v1"),
                any(),
                any(),
                any(),
                eq("Codex ha dejado el cambio listo y las pruebas principales pasan. El siguiente paso es publicar."),
                eq(false));
    }

    @Test
    void briefModeUsesPersistentBriefingCacheBeforeCallingProvider() {
        briefingProperties.setEnabled(true);
        when(briefingClient.supports("deepseek")).thenReturn(true);
        SessionSpeechBriefingCacheEntity cached = new SessionSpeechBriefingCacheEntity();
        cached.setProvider("deepseek");
        cached.setModel("deepseek-v4-flash");
        cached.setText("Codex ya dejo la correccion lista. Falta decidir si se publica.");
        cached.setTruncated(false);
        when(briefingCacheService.find(
                eq(44L),
                eq(SessionSpeechMode.BRIEF),
                eq("deepseek"),
                eq("deepseek-v4-flash"),
                eq("session-speech-briefing-v1"),
                any())).thenReturn(java.util.Optional.of(cached));
        when(mobileSessionService.getSessionSummary(44L)).thenReturn(summary(
                false,
                """
                ## Qué he hecho
                He aplicado el cambio.

                ## Verificación
                Tests en verde.
                """,
                new MobileSessionInsightsResponse(
                        "He aplicado el cambio",
                        new MobileSessionBlockerResponse("NONE", "Sin bloqueo activo"),
                        "Publicar la pull request")));

        SessionSpeechPreparationResult result = service.prepareLatestResponse(44L, SessionSpeechMode.BRIEF);

        assertEquals("Codex ya dejo la correccion lista. Falta decidir si se publica.", result.text());
        assertEquals(List.of("briefing-cache", "briefing:deepseek", "model:deepseek-v4-flash"), result.sectionsUsed());
        verify(briefingClient, never()).createBriefing(any());
    }

    @Test
    void deepSeekBriefingOutputIsCleanedBeforeSpeechAndCache() {
        briefingProperties.setEnabled(true);
        when(briefingClient.supports("deepseek")).thenReturn(true);
        when(briefingClient.createBriefing(any())).thenReturn(new SessionSpeechBriefingResult(
                """
                Resumen para decidir:
                Codex corrigio el problema principal.
                Ruta: `/srv/atenea/workspace/repos/clients/fomasys/src/app.js`
                Comando: `npm run test`
                https://example.test/noise
                Verificacion en verde y siguiente paso publicar.
                """,
                "deepseek",
                "deepseek-v4-flash"));
        when(mobileSessionService.getSessionSummary(44L)).thenReturn(summary(
                false,
                "## Qué he hecho\nHe aplicado el cambio.\n\n## Verificación\nTests en verde.",
                new MobileSessionInsightsResponse(
                        "He aplicado el cambio",
                        new MobileSessionBlockerResponse("NONE", "Sin bloqueo activo"),
                        "Publicar la pull request")));

        SessionSpeechPreparationResult result = service.prepareLatestResponse(44L, SessionSpeechMode.BRIEF);

        assertTrue(result.text().startsWith("Codex corrigio el problema principal."));
        assertTrue(!result.text().contains("/srv/atenea"));
        assertTrue(!result.text().contains("npm run test"));
        assertTrue(!result.text().contains("https://"));
        verify(briefingCacheService).save(
                eq(44L),
                eq(SessionSpeechMode.BRIEF),
                eq("deepseek"),
                eq("deepseek-v4-flash"),
                eq("session-speech-briefing-v1"),
                any(),
                any(),
                any(),
                eq(result.text()),
                eq(false));
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
                "Punto actual: He aplicado el parche principal. Bloqueo técnico: Los tests siguen fallando en CI. Siguiente paso: Revisar el stacktrace del test roto. Verificación: Typecheck en verde. Hay una ejecución en curso.",
                result.text());
        assertTrue(result.sectionsUsed().contains("runInProgress"));
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
