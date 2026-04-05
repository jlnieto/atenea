package com.atenea.api.mobile;

public record MobileSessionInsightsResponse(
        String latestProgress,
        MobileSessionBlockerResponse currentBlocker,
        String nextStepRecommended
) {
}
