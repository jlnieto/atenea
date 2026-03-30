package com.atenea.api.mobile;

public record MobileInboxSummaryResponse(
        int runInProgressCount,
        int closeBlockedCount,
        int pullRequestOpenCount,
        int readyToCloseCount,
        int billingReadyCount
) {
}
