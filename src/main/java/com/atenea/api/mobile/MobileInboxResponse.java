package com.atenea.api.mobile;

import java.util.List;

public record MobileInboxResponse(
        List<MobileInboxItemResponse> items,
        MobileInboxSummaryResponse summary
) {
}
