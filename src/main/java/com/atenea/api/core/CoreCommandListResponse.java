package com.atenea.api.core;

import java.util.List;

public record CoreCommandListResponse(
        List<CoreCommandSummaryResponse> items
) {
}
