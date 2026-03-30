package com.atenea.api.billing;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atenea.api.ApiExceptionHandler;
import com.atenea.persistence.worksession.SessionDeliverableBillingStatus;
import com.atenea.persistence.worksession.WorkSessionPullRequestStatus;
import com.atenea.service.billing.BillingQueueService;
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
class BillingControllerTest {

    @Mock
    private BillingQueueService billingQueueService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new BillingController(billingQueueService))
                .setControllerAdvice(new ApiExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(
                        Jackson2ObjectMapperBuilder.json().build()))
                .build();
    }

    @Test
    void getQueueReturnsBillingItemsWithFilters() throws Exception {
        when(billingQueueService.getQueue(SessionDeliverableBillingStatus.READY, 7L, null, "Atenea"))
                .thenReturn(new BillingQueueResponse(List.of(
                        new BillingQueueItemResponse(
                                7L,
                                "Atenea",
                                12L,
                                "Billing flow",
                                81L,
                                2,
                                SessionDeliverableBillingStatus.READY,
                                null,
                                null,
                                "EUR",
                                279.0,
                                240.0,
                                320.0,
                                Instant.parse("2026-03-29T10:00:00Z"),
                                Instant.parse("2026-03-29T09:30:00Z"),
                                "https://github.com/acme/atenea/pull/42",
                                WorkSessionPullRequestStatus.MERGED))));

        mockMvc.perform(get("/api/billing/queue")
                        .param("billingStatus", "READY")
                        .param("projectId", "7")
                        .param("q", "Atenea"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].projectId").value(7))
                .andExpect(jsonPath("$.items[0].billingStatus").value("READY"))
                .andExpect(jsonPath("$.items[0].recommendedPrice").value(279.0))
                .andExpect(jsonPath("$.items[0].pullRequestStatus").value("MERGED"));
    }

    @Test
    void getQueueSummaryReturnsCountsAndAmounts() throws Exception {
        when(billingQueueService.getQueueSummary(null, null, null, null))
                .thenReturn(new BillingQueueSummaryResponse(
                        2,
                        1,
                        List.of(new BillingAmountSummaryResponse("EUR", 558.0)),
                        List.of(new BillingAmountSummaryResponse("EUR", 320.0))));

        mockMvc.perform(get("/api/billing/queue/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.readyCount").value(2))
                .andExpect(jsonPath("$.billedCount").value(1))
                .andExpect(jsonPath("$.readyAmounts[0].currency").value("EUR"))
                .andExpect(jsonPath("$.readyAmounts[0].total").value(558.0))
                .andExpect(jsonPath("$.billedAmounts[0].total").value(320.0));
    }
}
