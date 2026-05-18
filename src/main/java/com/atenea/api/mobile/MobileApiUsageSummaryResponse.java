package com.atenea.api.mobile;

import java.util.List;

public record MobileApiUsageSummaryResponse(
        String provider,
        String usageType,
        String status,
        long requests,
        long inputTokens,
        long cachedInputTokens,
        long outputTokens,
        long inputAudioTokens,
        long outputAudioTokens,
        long characters,
        List<MobileApiUsageLineResponse> lines
) {
}
