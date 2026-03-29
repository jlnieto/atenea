package com.atenea.api.worksession;

import com.atenea.persistence.worksession.SessionDeliverableType;
import com.atenea.service.worksession.SessionDeliverableGenerationService;
import com.atenea.service.worksession.SessionDeliverableService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SessionDeliverableController {

    private final SessionDeliverableService sessionDeliverableService;
    private final SessionDeliverableGenerationService sessionDeliverableGenerationService;

    public SessionDeliverableController(
            SessionDeliverableService sessionDeliverableService,
            SessionDeliverableGenerationService sessionDeliverableGenerationService
    ) {
        this.sessionDeliverableService = sessionDeliverableService;
        this.sessionDeliverableGenerationService = sessionDeliverableGenerationService;
    }

    @GetMapping("/api/sessions/{sessionId}/deliverables")
    public SessionDeliverablesViewResponse getDeliverables(@PathVariable Long sessionId) {
        return sessionDeliverableService.getDeliverablesView(sessionId);
    }

    @GetMapping("/api/sessions/{sessionId}/deliverables/approved")
    public SessionDeliverablesViewResponse getApprovedDeliverables(@PathVariable Long sessionId) {
        return sessionDeliverableService.getApprovedDeliverablesView(sessionId);
    }

    @GetMapping("/api/sessions/{sessionId}/deliverables/price-estimate/approved-summary")
    public ApprovedPriceEstimateSummaryResponse getApprovedPriceEstimateSummary(@PathVariable Long sessionId) {
        return sessionDeliverableService.getApprovedPriceEstimateSummary(sessionId);
    }

    @GetMapping("/api/sessions/{sessionId}/deliverables/types/{type}/history")
    public SessionDeliverableHistoryResponse getDeliverableHistory(
            @PathVariable Long sessionId,
            @PathVariable SessionDeliverableType type
    ) {
        return sessionDeliverableService.getDeliverableHistory(sessionId, type);
    }

    @GetMapping("/api/sessions/{sessionId}/deliverables/{deliverableId}")
    public SessionDeliverableResponse getDeliverable(
            @PathVariable Long sessionId,
            @PathVariable Long deliverableId
    ) {
        return sessionDeliverableService.getDeliverable(sessionId, deliverableId);
    }

    @PostMapping("/api/sessions/{sessionId}/deliverables/{type}/generate")
    @ResponseStatus(HttpStatus.CREATED)
    public SessionDeliverableResponse generateDeliverable(
            @PathVariable Long sessionId,
            @PathVariable SessionDeliverableType type
    ) {
        return sessionDeliverableGenerationService.generateDeliverable(sessionId, type);
    }

    @PostMapping("/api/sessions/{sessionId}/deliverables/{deliverableId}/approve")
    public SessionDeliverableResponse approveDeliverable(
            @PathVariable Long sessionId,
            @PathVariable Long deliverableId
    ) {
        return sessionDeliverableService.approveDeliverable(sessionId, deliverableId);
    }
}
