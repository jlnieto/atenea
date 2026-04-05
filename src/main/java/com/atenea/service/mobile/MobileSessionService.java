package com.atenea.service.mobile;

import com.atenea.api.mobile.MobileSessionActionsResponse;
import com.atenea.api.mobile.MobileSessionSummaryResponse;
import com.atenea.api.worksession.ApprovedPriceEstimateSummaryResponse;
import com.atenea.api.worksession.SessionDeliverablesViewResponse;
import com.atenea.api.worksession.WorkSessionConversationViewResponse;
import com.atenea.persistence.worksession.SessionDeliverableBillingStatus;
import com.atenea.persistence.worksession.WorkSessionPullRequestStatus;
import com.atenea.persistence.worksession.WorkSessionStatus;
import com.atenea.service.worksession.ApprovedPriceEstimateNotFoundException;
import com.atenea.service.worksession.SessionDeliverableService;
import com.atenea.service.worksession.WorkSessionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MobileSessionService {

    private final WorkSessionService workSessionService;
    private final SessionDeliverableService sessionDeliverableService;
    private final MobileSessionInsightsService mobileSessionInsightsService;

    public MobileSessionService(
            WorkSessionService workSessionService,
            SessionDeliverableService sessionDeliverableService,
            MobileSessionInsightsService mobileSessionInsightsService
    ) {
        this.workSessionService = workSessionService;
        this.sessionDeliverableService = sessionDeliverableService;
        this.mobileSessionInsightsService = mobileSessionInsightsService;
    }

    @Transactional(readOnly = true)
    public MobileSessionSummaryResponse getSessionSummary(Long sessionId) {
        WorkSessionConversationViewResponse conversation = workSessionService.getSessionConversationView(sessionId);
        SessionDeliverablesViewResponse approvedDeliverables = sessionDeliverableService.getApprovedDeliverablesView(sessionId);
        ApprovedPriceEstimateSummaryResponse approvedPriceEstimate = getApprovedPriceEstimateOrNull(sessionId);
        MobileSessionActionsResponse actions = toActions(conversation, approvedPriceEstimate);

        return new MobileSessionSummaryResponse(
                conversation,
                approvedDeliverables,
                approvedPriceEstimate,
                actions,
                mobileSessionInsightsService.buildInsights(conversation, actions)
        );
    }

    private ApprovedPriceEstimateSummaryResponse getApprovedPriceEstimateOrNull(Long sessionId) {
        try {
            return sessionDeliverableService.getApprovedPriceEstimateSummary(sessionId);
        } catch (ApprovedPriceEstimateNotFoundException exception) {
            return null;
        }
    }

    private MobileSessionActionsResponse toActions(
            WorkSessionConversationViewResponse conversation,
            ApprovedPriceEstimateSummaryResponse approvedPriceEstimate
    ) {
        var session = conversation.view().session();
        boolean isClosed = session.status() == WorkSessionStatus.CLOSED;
        boolean isOpen = session.status() == WorkSessionStatus.OPEN;
        boolean runInProgress = session.repoState().runInProgress();
        boolean hasPullRequest = session.pullRequestUrl() != null && !session.pullRequestUrl().isBlank();
        boolean canClose = isOpen
                && !runInProgress
                && session.pullRequestStatus() == WorkSessionPullRequestStatus.MERGED;

        return new MobileSessionActionsResponse(
                conversation.view().canCreateTurn(),
                !isClosed && !runInProgress && session.pullRequestStatus() == WorkSessionPullRequestStatus.NOT_CREATED,
                hasPullRequest,
                canClose,
                !isClosed,
                !isClosed,
                approvedPriceEstimate != null
                        && approvedPriceEstimate.billingStatus() == SessionDeliverableBillingStatus.READY
        );
    }
}
