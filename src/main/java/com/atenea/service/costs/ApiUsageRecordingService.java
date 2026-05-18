package com.atenea.service.costs;

import com.atenea.persistence.costs.ApiUsageRecordEntity;
import com.atenea.persistence.costs.ApiUsageRecordRepository;
import java.math.BigDecimal;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ApiUsageRecordingService {

    private final ApiUsageRecordRepository repository;

    public ApiUsageRecordingService(ApiUsageRecordRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public ApiUsageRecordEntity record(ApiUsageRecordRequest request) {
        Instant now = Instant.now();
        ApiUsageRecordEntity entity = new ApiUsageRecordEntity();
        entity.setProvider(normalize(request.provider(), "unknown"));
        entity.setModel(normalize(request.model(), "unknown"));
        entity.setFeature(normalize(request.feature(), "unknown"));
        entity.setEnvironment(blankToNull(request.environment()));
        entity.setStatus(normalize(request.status(), "succeeded"));
        entity.setProjectId(request.projectId());
        entity.setWorkSessionId(request.workSessionId());
        entity.setAgentRunId(request.agentRunId());
        entity.setSessionTurnId(request.sessionTurnId());
        entity.setCoreCommandId(request.coreCommandId());
        entity.setProviderRequestId(blankToNull(request.providerRequestId()));
        entity.setCurrency(normalize(request.currency(), "usd"));
        entity.setEstimatedCost(request.estimatedCost() == null ? BigDecimal.ZERO : request.estimatedCost());
        entity.setInputTokens(request.inputTokens());
        entity.setInputCacheHitTokens(request.inputCacheHitTokens());
        entity.setInputCacheMissTokens(request.inputCacheMissTokens());
        entity.setOutputTokens(request.outputTokens());
        entity.setTotalTokens(request.totalTokens());
        entity.setAudioInputSeconds(request.audioInputSeconds());
        entity.setAudioOutputSeconds(request.audioOutputSeconds());
        entity.setRequestCount(request.requestCount() == null ? 1 : request.requestCount());
        entity.setMetadataJson(blankToNull(request.metadataJson()));
        entity.setStartedAt(request.startedAt() == null ? now : request.startedAt());
        entity.setFinishedAt(request.finishedAt());
        entity.setCreatedAt(now);
        return repository.save(entity);
    }

    private String normalize(String value, String fallback) {
        String normalized = blankToNull(value);
        return normalized == null ? fallback : normalized;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
