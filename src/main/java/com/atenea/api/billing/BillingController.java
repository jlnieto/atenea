package com.atenea.api.billing;

import com.atenea.persistence.worksession.SessionDeliverableBillingStatus;
import com.atenea.service.billing.BillingQueueService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/billing")
public class BillingController {

    private final BillingQueueService billingQueueService;

    public BillingController(BillingQueueService billingQueueService) {
        this.billingQueueService = billingQueueService;
    }

    @GetMapping("/queue")
    public BillingQueueResponse getQueue(
            @RequestParam(required = false) SessionDeliverableBillingStatus billingStatus,
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) Long sessionId,
            @RequestParam(required = false) String q
    ) {
        return billingQueueService.getQueue(billingStatus, projectId, sessionId, q);
    }

    @GetMapping("/queue/summary")
    public BillingQueueSummaryResponse getQueueSummary(
            @RequestParam(required = false) SessionDeliverableBillingStatus billingStatus,
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) Long sessionId,
            @RequestParam(required = false) String q
    ) {
        return billingQueueService.getQueueSummary(billingStatus, projectId, sessionId, q);
    }
}
