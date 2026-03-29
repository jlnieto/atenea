package com.atenea.api.worksession;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atenea.api.ApiExceptionHandler;
import com.atenea.persistence.worksession.SessionDeliverableStatus;
import com.atenea.persistence.worksession.SessionDeliverableType;
import com.atenea.service.worksession.SessionDeliverableGenerationService;
import com.atenea.service.worksession.SessionDeliverableNotFoundException;
import com.atenea.service.worksession.SessionDeliverableService;
import com.atenea.service.worksession.ApprovedPriceEstimateNotFoundException;
import com.atenea.service.worksession.WorkSessionNotFoundException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class SessionDeliverableControllerTest {

    @Mock
    private SessionDeliverableService sessionDeliverableService;

    @Mock
    private SessionDeliverableGenerationService sessionDeliverableGenerationService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new SessionDeliverableController(
                        sessionDeliverableService,
                        sessionDeliverableGenerationService))
                .setControllerAdvice(new ApiExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(
                        Jackson2ObjectMapperBuilder.json().build()))
                .build();
    }

    @Test
    void getDeliverablesReturnsLatestDeliverablesView() throws Exception {
        when(sessionDeliverableService.getDeliverablesView(12L)).thenReturn(new SessionDeliverablesViewResponse(
                12L,
                List.of(
                        new SessionDeliverableSummaryResponse(
                                81L,
                                SessionDeliverableType.WORK_TICKET,
                                SessionDeliverableStatus.SUCCEEDED,
                                2,
                                "Ticket final",
                                true,
                                Instant.parse("2026-03-29T08:10:00Z"),
                                Instant.parse("2026-03-29T08:05:00Z"),
                                "# Ticket final",
                                81L)),
                false,
                false,
                Instant.parse("2026-03-29T08:05:00Z")));

        mockMvc.perform(get("/api/sessions/12/deliverables"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value(12))
                .andExpect(jsonPath("$.deliverables[0].id").value(81))
                .andExpect(jsonPath("$.deliverables[0].type").value("WORK_TICKET"))
                .andExpect(jsonPath("$.deliverables[0].version").value(2))
                .andExpect(jsonPath("$.allCoreDeliverablesPresent").value(false))
                .andExpect(jsonPath("$.allCoreDeliverablesApproved").value(false));
    }

    @Test
    void getDeliverablesReturnsNotFoundWhenSessionDoesNotExist() throws Exception {
        when(sessionDeliverableService.getDeliverablesView(12L)).thenThrow(new WorkSessionNotFoundException(12L));

        mockMvc.perform(get("/api/sessions/12/deliverables"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("WorkSession with id '12' was not found"));
    }

    @Test
    void getApprovedDeliverablesReturnsLatestApprovedView() throws Exception {
        when(sessionDeliverableService.getApprovedDeliverablesView(12L)).thenReturn(new SessionDeliverablesViewResponse(
                12L,
                List.of(
                        new SessionDeliverableSummaryResponse(
                                82L,
                                SessionDeliverableType.PRICE_ESTIMATE,
                                SessionDeliverableStatus.SUCCEEDED,
                                1,
                                "Price estimate v1",
                                true,
                                Instant.parse("2026-03-29T09:10:00Z"),
                                Instant.parse("2026-03-29T09:10:00Z"),
                                "# Price Estimate",
                                82L)),
                false,
                false,
                Instant.parse("2026-03-29T09:10:00Z")));

        mockMvc.perform(get("/api/sessions/12/deliverables/approved"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value(12))
                .andExpect(jsonPath("$.deliverables[0].type").value("PRICE_ESTIMATE"))
                .andExpect(jsonPath("$.deliverables[0].approved").value(true));
    }

    @Test
    void getApprovedPriceEstimateSummaryReturnsStructuredPricingReadModel() throws Exception {
        when(sessionDeliverableService.getApprovedPriceEstimateSummary(12L)).thenReturn(
                new ApprovedPriceEstimateSummaryResponse(
                        12L,
                        82L,
                        1,
                        "Price estimate v1",
                        "EUR",
                        43.0,
                        6.5,
                        240.0,
                        279.0,
                        320.0,
                        "competitive",
                        "low",
                        "medium",
                        List.of("Solo trabajo de esta session"),
                        List.of("No incluye soporte posterior"),
                        Instant.parse("2026-03-29T09:10:00Z"),
                        Instant.parse("2026-03-29T09:10:00Z")));

        mockMvc.perform(get("/api/sessions/12/deliverables/price-estimate/approved-summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value(12))
                .andExpect(jsonPath("$.deliverableId").value(82))
                .andExpect(jsonPath("$.recommendedPrice").value(279.0))
                .andExpect(jsonPath("$.equivalentHours").value(6.5))
                .andExpect(jsonPath("$.currency").value("EUR"));
    }

    @Test
    void getApprovedPriceEstimateSummaryReturnsNotFoundWhenMissing() throws Exception {
        when(sessionDeliverableService.getApprovedPriceEstimateSummary(12L))
                .thenThrow(new ApprovedPriceEstimateNotFoundException(12L));

        mockMvc.perform(get("/api/sessions/12/deliverables/price-estimate/approved-summary"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Approved PRICE_ESTIMATE was not found for WorkSession '12'"));
    }

    @Test
    void getDeliverableReturnsDetailedDeliverable() throws Exception {
        when(sessionDeliverableService.getDeliverable(12L, 81L)).thenReturn(new SessionDeliverableResponse(
                81L,
                12L,
                SessionDeliverableType.WORK_BREAKDOWN,
                SessionDeliverableStatus.SUCCEEDED,
                1,
                "Breakdown",
                "# Breakdown",
                "{\"summary\":\"done\"}",
                "{\"sessionId\":12}",
                "Generated from stable snapshot",
                null,
                "gpt-5.4",
                "pricing-v1",
                false,
                null,
                Instant.parse("2026-03-29T08:00:00Z"),
                Instant.parse("2026-03-29T08:05:00Z")));

        mockMvc.perform(get("/api/sessions/12/deliverables/81"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(81))
                .andExpect(jsonPath("$.sessionId").value(12))
                .andExpect(jsonPath("$.type").value("WORK_BREAKDOWN"))
                .andExpect(jsonPath("$.contentMarkdown").value("# Breakdown"))
                .andExpect(jsonPath("$.model").value("gpt-5.4"));
    }

    @Test
    void getDeliverableHistoryReturnsAllVersionsForType() throws Exception {
        when(sessionDeliverableService.getDeliverableHistory(12L, SessionDeliverableType.WORK_TICKET))
                .thenReturn(new SessionDeliverableHistoryResponse(
                        12L,
                        SessionDeliverableType.WORK_TICKET,
                        84L,
                        81L,
                        List.of(
                                new SessionDeliverableSummaryResponse(
                                        84L,
                                        SessionDeliverableType.WORK_TICKET,
                                        SessionDeliverableStatus.SUCCEEDED,
                                        3,
                                        "Work ticket v3",
                                        false,
                                        null,
                                        Instant.parse("2026-03-29T09:20:00Z"),
                                        "# Ticket v3",
                                        81L),
                                new SessionDeliverableSummaryResponse(
                                        81L,
                                        SessionDeliverableType.WORK_TICKET,
                                        SessionDeliverableStatus.SUPERSEDED,
                                        2,
                                        "Work ticket v2",
                                        true,
                                        Instant.parse("2026-03-29T09:10:00Z"),
                                        Instant.parse("2026-03-29T09:10:00Z"),
                                        "# Ticket v2",
                                        81L))));

        mockMvc.perform(get("/api/sessions/12/deliverables/types/WORK_TICKET/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value(12))
                .andExpect(jsonPath("$.type").value("WORK_TICKET"))
                .andExpect(jsonPath("$.latestGeneratedDeliverableId").value(84))
                .andExpect(jsonPath("$.latestApprovedDeliverableId").value(81))
                .andExpect(jsonPath("$.versions[0].version").value(3))
                .andExpect(jsonPath("$.versions[1].approved").value(true));
    }

    @Test
    void getDeliverableReturnsNotFoundWhenDeliverableDoesNotExist() throws Exception {
        when(sessionDeliverableService.getDeliverable(12L, 81L))
                .thenThrow(new SessionDeliverableNotFoundException(12L, 81L));

        mockMvc.perform(get("/api/sessions/12/deliverables/81"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(
                        "SessionDeliverable with id '81' was not found for WorkSession '12'"));
    }

    @Test
    void generateDeliverableCreatesWorkTicketVersion() throws Exception {
        when(sessionDeliverableGenerationService.generateDeliverable(12L, SessionDeliverableType.WORK_TICKET))
                .thenReturn(new SessionDeliverableResponse(
                        82L,
                        12L,
                        SessionDeliverableType.WORK_TICKET,
                        SessionDeliverableStatus.SUCCEEDED,
                        3,
                        "Work ticket v3",
                        "# Work Ticket",
                        null,
                        "{\"session\":{\"id\":12}}",
                        "Generated from persisted session evidence snapshot",
                        null,
                        "codex-app-server",
                        "session-deliverables-work-ticket-v1",
                        false,
                        null,
                        Instant.parse("2026-03-29T09:00:00Z"),
                        Instant.parse("2026-03-29T09:01:00Z")));

        mockMvc.perform(post("/api/sessions/12/deliverables/WORK_TICKET/generate"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(82))
                .andExpect(jsonPath("$.type").value("WORK_TICKET"))
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.promptVersion").value("session-deliverables-work-ticket-v1"));
    }

    @Test
    void generateDeliverableCreatesWorkBreakdownVersion() throws Exception {
        when(sessionDeliverableGenerationService.generateDeliverable(12L, SessionDeliverableType.WORK_BREAKDOWN))
                .thenReturn(new SessionDeliverableResponse(
                        83L,
                        12L,
                        SessionDeliverableType.WORK_BREAKDOWN,
                        SessionDeliverableStatus.SUCCEEDED,
                        1,
                        "Work breakdown v1",
                        "# Work Breakdown",
                        null,
                        "{\"session\":{\"id\":12}}",
                        "Generated from persisted session evidence snapshot",
                        null,
                        "codex-app-server",
                        "session-deliverables-work-breakdown-v1",
                        false,
                        null,
                        Instant.parse("2026-03-29T09:05:00Z"),
                        Instant.parse("2026-03-29T09:06:00Z")));

        mockMvc.perform(post("/api/sessions/12/deliverables/WORK_BREAKDOWN/generate"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(83))
                .andExpect(jsonPath("$.type").value("WORK_BREAKDOWN"))
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.promptVersion").value("session-deliverables-work-breakdown-v1"));
    }

    @Test
    void generateDeliverableCreatesPriceEstimateVersion() throws Exception {
        when(sessionDeliverableGenerationService.generateDeliverable(12L, SessionDeliverableType.PRICE_ESTIMATE))
                .thenReturn(new SessionDeliverableResponse(
                        84L,
                        12L,
                        SessionDeliverableType.PRICE_ESTIMATE,
                        SessionDeliverableStatus.SUCCEEDED,
                        1,
                        "Price estimate v1",
                        "# Price Estimate",
                        "{\"currency\":\"EUR\",\"recommendedPrice\":279.0}",
                        "{\"pricingPolicy\":{\"baseHourlyRate\":43.0}}",
                        "Generated from persisted session evidence snapshot",
                        null,
                        "codex-app-server",
                        "session-deliverables-price-estimate-v2",
                        false,
                        null,
                        Instant.parse("2026-03-29T09:07:00Z"),
                        Instant.parse("2026-03-29T09:08:00Z")));

        mockMvc.perform(post("/api/sessions/12/deliverables/PRICE_ESTIMATE/generate"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(84))
                .andExpect(jsonPath("$.type").value("PRICE_ESTIMATE"))
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.contentJson").value("{\"currency\":\"EUR\",\"recommendedPrice\":279.0}"))
                .andExpect(jsonPath("$.promptVersion").value("session-deliverables-price-estimate-v2"));
    }

    @Test
    void approveDeliverableMarksVersionAsApproved() throws Exception {
        when(sessionDeliverableService.approveDeliverable(12L, 84L)).thenReturn(new SessionDeliverableResponse(
                84L,
                12L,
                SessionDeliverableType.PRICE_ESTIMATE,
                SessionDeliverableStatus.SUCCEEDED,
                1,
                "Price estimate v1",
                "# Price Estimate",
                "{\"currency\":\"EUR\",\"recommendedPrice\":279.0}",
                "{\"pricingPolicy\":{\"baseHourlyRate\":43.0}}",
                "Generated from persisted session evidence snapshot",
                null,
                "codex-app-server",
                "session-deliverables-price-estimate-v2",
                true,
                Instant.parse("2026-03-29T09:15:00Z"),
                Instant.parse("2026-03-29T09:07:00Z"),
                Instant.parse("2026-03-29T09:15:00Z")));

        mockMvc.perform(post("/api/sessions/12/deliverables/84/approve"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(84))
                .andExpect(jsonPath("$.approved").value(true))
                .andExpect(jsonPath("$.approvedAt").isNotEmpty());
    }
}
