package com.atenea.service.costs;

import java.math.BigDecimal;
import java.time.Instant;

public record ApiUsageRecordRequest(
        String provider,
        String model,
        String feature,
        String environment,
        String status,
        Long projectId,
        Long workSessionId,
        Long agentRunId,
        Long sessionTurnId,
        Long coreCommandId,
        String providerRequestId,
        String currency,
        BigDecimal estimatedCost,
        Long inputTokens,
        Long inputCacheHitTokens,
        Long inputCacheMissTokens,
        Long outputTokens,
        Long totalTokens,
        BigDecimal audioInputSeconds,
        BigDecimal audioOutputSeconds,
        Integer requestCount,
        String metadataJson,
        Instant startedAt,
        Instant finishedAt
) {
}
