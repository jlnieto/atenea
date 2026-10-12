package com.atenea.api.mobile;

public record MobileSessionActionsResponse(
        boolean canCreateTurn,
        boolean canPublish,
        boolean canSyncPullRequest,
        boolean canGenerateDeliverables,
        boolean canApproveDeliverables,
        boolean canMarkApprovedPriceEstimateBilled
) {
}
