package com.atenea.service.costs;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class DeepSeekUsagePricing {

    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000L);
    private static final BigDecimal FLASH_CACHE_HIT_PER_MILLION = new BigDecimal("0.0028");
    private static final BigDecimal FLASH_CACHE_MISS_PER_MILLION = new BigDecimal("0.14");
    private static final BigDecimal FLASH_OUTPUT_PER_MILLION = new BigDecimal("0.28");
    private static final BigDecimal PRO_CACHE_HIT_PER_MILLION = new BigDecimal("0.003625");
    private static final BigDecimal PRO_CACHE_MISS_PER_MILLION = new BigDecimal("0.435");
    private static final BigDecimal PRO_OUTPUT_PER_MILLION = new BigDecimal("0.87");

    private DeepSeekUsagePricing() {
    }

    public static BigDecimal estimateUsd(
            String model,
            long inputCacheHitTokens,
            long inputCacheMissTokens,
            long outputTokens
    ) {
        boolean pro = model != null && model.toLowerCase().contains("pro");
        BigDecimal hitRate = pro ? PRO_CACHE_HIT_PER_MILLION : FLASH_CACHE_HIT_PER_MILLION;
        BigDecimal missRate = pro ? PRO_CACHE_MISS_PER_MILLION : FLASH_CACHE_MISS_PER_MILLION;
        BigDecimal outputRate = pro ? PRO_OUTPUT_PER_MILLION : FLASH_OUTPUT_PER_MILLION;
        return cost(inputCacheHitTokens, hitRate)
                .add(cost(inputCacheMissTokens, missRate))
                .add(cost(outputTokens, outputRate))
                .setScale(8, RoundingMode.HALF_UP);
    }

    private static BigDecimal cost(long tokens, BigDecimal perMillion) {
        if (tokens <= 0) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(tokens).multiply(perMillion).divide(MILLION, 12, RoundingMode.HALF_UP);
    }
}
