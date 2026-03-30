package com.atenea.service.mobile;

import com.atenea.api.billing.BillingQueueItemResponse;
import com.atenea.api.billing.BillingQueueResponse;
import com.atenea.api.mobile.MobileInboxItemResponse;
import com.atenea.api.mobile.MobileInboxResponse;
import com.atenea.api.mobile.MobileInboxSummaryResponse;
import com.atenea.api.project.ProjectOverviewResponse;
import com.atenea.persistence.worksession.SessionDeliverableBillingStatus;
import com.atenea.persistence.worksession.WorkSessionPullRequestStatus;
import com.atenea.persistence.worksession.WorkSessionStatus;
import com.atenea.service.billing.BillingQueueService;
import com.atenea.service.project.ProjectOverviewService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MobileInboxService {

    private final ProjectOverviewService projectOverviewService;
    private final BillingQueueService billingQueueService;

    public MobileInboxService(
            ProjectOverviewService projectOverviewService,
            BillingQueueService billingQueueService
    ) {
        this.projectOverviewService = projectOverviewService;
        this.billingQueueService = billingQueueService;
    }

    @Transactional(readOnly = true)
    public MobileInboxResponse getInbox() {
        List<MobileInboxItemResponse> items = new ArrayList<>();
        int runInProgressCount = 0;
        int closeBlockedCount = 0;
        int pullRequestOpenCount = 0;
        int readyToCloseCount = 0;

        for (ProjectOverviewResponse project : projectOverviewService.getOverview()) {
            ProjectOverviewResponse.WorkSessionOverviewResponse session = project.workSession();
            if (session == null) {
                continue;
            }
            if (session.runInProgress()) {
                runInProgressCount++;
                items.add(item(
                        "RUN_IN_PROGRESS",
                        "info",
                        "Run in progress",
                        "Codex is still working on this session",
                        "Open session",
                        project,
                        session,
                        session.lastActivityAt()));
            }
            if (session.closeBlockedState() != null) {
                closeBlockedCount++;
                items.add(item(
                        "CLOSE_BLOCKED",
                        "warning",
                        "Close blocked",
                        session.closeBlockedReason(),
                        session.closeBlockedAction(),
                        project,
                        session,
                        session.lastActivityAt()));
            }
            if (session.pullRequestStatus() == WorkSessionPullRequestStatus.OPEN) {
                pullRequestOpenCount++;
                items.add(item(
                        "PULL_REQUEST_OPEN",
                        "info",
                        "Pull request open",
                        "Session pull request is still open",
                        "Sync pull request",
                        project,
                        session,
                        session.publishedAt() != null ? session.publishedAt() : session.lastActivityAt()));
            }
            if (session.pullRequestStatus() == WorkSessionPullRequestStatus.MERGED
                    && session.status() != WorkSessionStatus.CLOSED) {
                readyToCloseCount++;
                items.add(item(
                        "READY_TO_CLOSE",
                        "info",
                        "Ready to close",
                        "Pull request is merged and the session can be reconciled",
                        "Close session",
                        project,
                        session,
                        session.lastActivityAt()));
            }
        }

        BillingQueueResponse billingQueue = billingQueueService.getQueue(SessionDeliverableBillingStatus.READY, null, null, null);
        int billingReadyCount = billingQueue.items().size();
        for (BillingQueueItemResponse billingItem : billingQueue.items()) {
            items.add(new MobileInboxItemResponse(
                    "BILLING_READY",
                    "info",
                    "Billing ready",
                    "Approved price estimate is ready to bill",
                    "Open billing",
                    billingItem.projectId(),
                    billingItem.projectName(),
                    billingItem.sessionId(),
                    billingItem.sessionTitle(),
                    billingItem.approvedAt()));
        }

        items.sort(Comparator
                .comparing(MobileInboxItemResponse::severity, this::compareSeverity)
                .thenComparing(MobileInboxItemResponse::updatedAt, Comparator.nullsLast(Comparator.reverseOrder())));

        return new MobileInboxResponse(
                items,
                new MobileInboxSummaryResponse(
                        runInProgressCount,
                        closeBlockedCount,
                        pullRequestOpenCount,
                        readyToCloseCount,
                        billingReadyCount));
    }

    private MobileInboxItemResponse item(
            String type,
            String severity,
            String title,
            String message,
            String action,
            ProjectOverviewResponse project,
            ProjectOverviewResponse.WorkSessionOverviewResponse session,
            Instant updatedAt
    ) {
        return new MobileInboxItemResponse(
                type,
                severity,
                title,
                message,
                action,
                project.project().id(),
                project.project().name(),
                session.sessionId(),
                session.title(),
                updatedAt);
    }

    private int compareSeverity(String left, String right) {
        return severityRank(left) - severityRank(right);
    }

    private int severityRank(String severity) {
        if ("warning".equalsIgnoreCase(severity)) {
            return 0;
        }
        if ("info".equalsIgnoreCase(severity)) {
            return 1;
        }
        return 2;
    }
}
