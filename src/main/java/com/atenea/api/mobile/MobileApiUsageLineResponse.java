package com.atenea.api.mobile;

public record MobileApiUsageLineResponse(
        String usageType,
        String model,
        String projectId,
        String projectName,
        String apiKeyId,
        String apiKeyName,
        long requests,
        long inputTokens,
        long cachedInputTokens,
        long outputTokens,
        long inputAudioTokens,
        long outputAudioTokens,
        long characters
) {
}
