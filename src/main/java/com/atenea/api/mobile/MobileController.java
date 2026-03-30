package com.atenea.api.mobile;

import com.atenea.api.billing.BillingQueueResponse;
import com.atenea.api.billing.BillingQueueSummaryResponse;
import com.atenea.api.worksession.CloseWorkSessionConversationViewResponse;
import com.atenea.api.worksession.CreateSessionTurnConversationViewResponse;
import com.atenea.api.worksession.CreateSessionTurnRequest;
import com.atenea.api.worksession.MarkPriceEstimateBilledRequest;
import com.atenea.api.worksession.PublishWorkSessionConversationViewResponse;
import com.atenea.api.worksession.PublishWorkSessionRequest;
import com.atenea.api.worksession.ResolveWorkSessionConversationViewResponse;
import com.atenea.api.worksession.ResolveWorkSessionRequest;
import com.atenea.api.worksession.SessionDeliverableResponse;
import com.atenea.api.worksession.SessionDeliverablesViewResponse;
import com.atenea.api.worksession.SyncWorkSessionPullRequestConversationViewResponse;
import com.atenea.api.worksession.WorkSessionConversationViewResponse;
import com.atenea.persistence.worksession.SessionDeliverableBillingStatus;
import com.atenea.persistence.worksession.SessionDeliverableType;
import com.atenea.service.billing.BillingQueueService;
import com.atenea.service.mobile.MobileInboxService;
import com.atenea.service.mobile.MobileProjectOverviewService;
import com.atenea.service.mobile.MobileSessionEventService;
import com.atenea.service.mobile.MobileSessionService;
import com.atenea.service.mobile.MobileStreamService;
import com.atenea.service.worksession.SessionDeliverableGenerationService;
import com.atenea.service.worksession.SessionDeliverableService;
import com.atenea.service.worksession.SessionTurnService;
import com.atenea.service.worksession.WorkSessionGitHubService;
import com.atenea.service.worksession.WorkSessionService;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/mobile")
public class MobileController {

    private final MobileProjectOverviewService mobileProjectOverviewService;
    private final MobileInboxService mobileInboxService;
    private final MobileSessionService mobileSessionService;
    private final MobileSessionEventService mobileSessionEventService;
    private final MobileStreamService mobileStreamService;
    private final WorkSessionService workSessionService;
    private final SessionTurnService sessionTurnService;
    private final WorkSessionGitHubService workSessionGitHubService;
    private final SessionDeliverableService sessionDeliverableService;
    private final SessionDeliverableGenerationService sessionDeliverableGenerationService;
    private final BillingQueueService billingQueueService;

    public MobileController(
            MobileProjectOverviewService mobileProjectOverviewService,
            MobileInboxService mobileInboxService,
            MobileSessionService mobileSessionService,
            MobileSessionEventService mobileSessionEventService,
            MobileStreamService mobileStreamService,
            WorkSessionService workSessionService,
            SessionTurnService sessionTurnService,
            WorkSessionGitHubService workSessionGitHubService,
            SessionDeliverableService sessionDeliverableService,
            SessionDeliverableGenerationService sessionDeliverableGenerationService,
            BillingQueueService billingQueueService
    ) {
        this.mobileProjectOverviewService = mobileProjectOverviewService;
        this.mobileInboxService = mobileInboxService;
        this.mobileSessionService = mobileSessionService;
        this.mobileSessionEventService = mobileSessionEventService;
        this.mobileStreamService = mobileStreamService;
        this.workSessionService = workSessionService;
        this.sessionTurnService = sessionTurnService;
        this.workSessionGitHubService = workSessionGitHubService;
        this.sessionDeliverableService = sessionDeliverableService;
        this.sessionDeliverableGenerationService = sessionDeliverableGenerationService;
        this.billingQueueService = billingQueueService;
    }

    @GetMapping("/projects/overview")
    public List<MobileProjectOverviewResponse> getProjectOverview() {
        return mobileProjectOverviewService.getOverview();
    }

    @GetMapping("/inbox")
    public MobileInboxResponse getInbox() {
        return mobileInboxService.getInbox();
    }

    @GetMapping("/inbox/stream")
    public SseEmitter streamInbox() {
        return mobileStreamService.streamInbox();
    }

    @GetMapping("/sessions/{sessionId}/summary")
    public MobileSessionSummaryResponse getSessionSummary(@PathVariable Long sessionId) {
        return mobileSessionService.getSessionSummary(sessionId);
    }

    @GetMapping("/sessions/{sessionId}/events")
    public MobileSessionEventsResponse getSessionEvents(
            @PathVariable Long sessionId,
            @RequestParam(required = false) Instant after,
            @RequestParam(required = false) Integer limit
    ) {
        return mobileSessionEventService.getEvents(sessionId, after, limit);
    }

    @GetMapping("/sessions/{sessionId}/events/stream")
    public SseEmitter streamSessionEvents(@PathVariable Long sessionId) {
        return mobileStreamService.streamSessionEvents(sessionId);
    }

    @PostMapping("/projects/{projectId}/sessions/resolve")
    public ResolveWorkSessionConversationViewResponse resolveSession(
            @PathVariable Long projectId,
            @Valid @RequestBody(required = false) ResolveWorkSessionRequest request
    ) {
        return workSessionService.resolveSessionConversationView(projectId, request);
    }

    @GetMapping("/sessions/{sessionId}/conversation")
    public WorkSessionConversationViewResponse getSessionConversation(@PathVariable Long sessionId) {
        return workSessionService.getSessionConversationView(sessionId);
    }

    @PostMapping("/sessions/{sessionId}/turns")
    @ResponseStatus(HttpStatus.CREATED)
    public CreateSessionTurnConversationViewResponse createTurn(
            @PathVariable Long sessionId,
            @Valid @RequestBody CreateSessionTurnRequest request
    ) {
        sessionTurnService.createTurn(sessionId, request);
        return new CreateSessionTurnConversationViewResponse(workSessionService.getSessionConversationView(sessionId));
    }

    @PostMapping("/sessions/{sessionId}/publish")
    public PublishWorkSessionConversationViewResponse publishSession(
            @PathVariable Long sessionId,
            @Valid @RequestBody(required = false) PublishWorkSessionRequest request
    ) {
        return workSessionGitHubService.publishSessionConversationView(sessionId, request);
    }

    @PostMapping("/sessions/{sessionId}/pull-request/sync")
    public SyncWorkSessionPullRequestConversationViewResponse syncPullRequest(@PathVariable Long sessionId) {
        return workSessionGitHubService.syncPullRequestConversationView(sessionId);
    }

    @PostMapping("/sessions/{sessionId}/close")
    public CloseWorkSessionConversationViewResponse closeSession(@PathVariable Long sessionId) {
        return workSessionService.closeSessionConversationView(sessionId);
    }

    @GetMapping("/sessions/{sessionId}/deliverables")
    public SessionDeliverablesViewResponse getDeliverables(@PathVariable Long sessionId) {
        return sessionDeliverableService.getDeliverablesView(sessionId);
    }

    @GetMapping("/sessions/{sessionId}/deliverables/approved")
    public SessionDeliverablesViewResponse getApprovedDeliverables(@PathVariable Long sessionId) {
        return sessionDeliverableService.getApprovedDeliverablesView(sessionId);
    }

    @PostMapping("/sessions/{sessionId}/deliverables/{type}/generate")
    @ResponseStatus(HttpStatus.CREATED)
    public SessionDeliverableResponse generateDeliverable(
            @PathVariable Long sessionId,
            @PathVariable SessionDeliverableType type
    ) {
        return sessionDeliverableGenerationService.generateDeliverable(sessionId, type);
    }

    @PostMapping("/sessions/{sessionId}/deliverables/{deliverableId}/approve")
    public SessionDeliverableResponse approveDeliverable(
            @PathVariable Long sessionId,
            @PathVariable Long deliverableId
    ) {
        return sessionDeliverableService.approveDeliverable(sessionId, deliverableId);
    }

    @PostMapping("/sessions/{sessionId}/deliverables/{deliverableId}/billing/mark-billed")
    public SessionDeliverableResponse markPriceEstimateBilled(
            @PathVariable Long sessionId,
            @PathVariable Long deliverableId,
            @Valid @RequestBody MarkPriceEstimateBilledRequest request
    ) {
        return sessionDeliverableService.markPriceEstimateBilled(sessionId, deliverableId, request);
    }

    @GetMapping("/billing/queue")
    public BillingQueueResponse getBillingQueue(
            @RequestParam(required = false) SessionDeliverableBillingStatus billingStatus,
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) Long sessionId,
            @RequestParam(required = false) String q
    ) {
        return billingQueueService.getQueue(billingStatus, projectId, sessionId, q);
    }

    @GetMapping("/billing/queue/summary")
    public BillingQueueSummaryResponse getBillingQueueSummary(
            @RequestParam(required = false) SessionDeliverableBillingStatus billingStatus,
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) Long sessionId,
            @RequestParam(required = false) String q
    ) {
        return billingQueueService.getQueueSummary(billingStatus, projectId, sessionId, q);
    }
}
