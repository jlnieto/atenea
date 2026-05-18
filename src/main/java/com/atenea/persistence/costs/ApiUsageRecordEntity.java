package com.atenea.persistence.costs;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "api_usage_record")
public class ApiUsageRecordEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 40)
    private String provider;

    @Column(nullable = false, length = 120)
    private String model;

    @Column(nullable = false, length = 80)
    private String feature;

    @Column(length = 40)
    private String environment;

    @Column(nullable = false, length = 40)
    private String status;

    @Column(name = "project_id")
    private Long projectId;

    @Column(name = "work_session_id")
    private Long workSessionId;

    @Column(name = "agent_run_id")
    private Long agentRunId;

    @Column(name = "session_turn_id")
    private Long sessionTurnId;

    @Column(name = "core_command_id")
    private Long coreCommandId;

    @Column(name = "provider_request_id", length = 160)
    private String providerRequestId;

    @Column(nullable = false, length = 12)
    private String currency = "usd";

    @Column(name = "estimated_cost", nullable = false, precision = 18, scale = 8)
    private BigDecimal estimatedCost = BigDecimal.ZERO;

    @Column(name = "input_tokens")
    private Long inputTokens;

    @Column(name = "input_cache_hit_tokens")
    private Long inputCacheHitTokens;

    @Column(name = "input_cache_miss_tokens")
    private Long inputCacheMissTokens;

    @Column(name = "output_tokens")
    private Long outputTokens;

    @Column(name = "total_tokens")
    private Long totalTokens;

    @Column(name = "audio_input_seconds", precision = 12, scale = 3)
    private BigDecimal audioInputSeconds;

    @Column(name = "audio_output_seconds", precision = 12, scale = 3)
    private BigDecimal audioOutputSeconds;

    @Column(name = "request_count", nullable = false)
    private Integer requestCount = 1;

    @Column(name = "metadata_json", columnDefinition = "TEXT")
    private String metadataJson;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public Long getId() {
        return id;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getFeature() {
        return feature;
    }

    public void setFeature(String feature) {
        this.feature = feature;
    }

    public String getEnvironment() {
        return environment;
    }

    public void setEnvironment(String environment) {
        this.environment = environment;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Long getProjectId() {
        return projectId;
    }

    public void setProjectId(Long projectId) {
        this.projectId = projectId;
    }

    public Long getWorkSessionId() {
        return workSessionId;
    }

    public void setWorkSessionId(Long workSessionId) {
        this.workSessionId = workSessionId;
    }

    public Long getAgentRunId() {
        return agentRunId;
    }

    public void setAgentRunId(Long agentRunId) {
        this.agentRunId = agentRunId;
    }

    public Long getSessionTurnId() {
        return sessionTurnId;
    }

    public void setSessionTurnId(Long sessionTurnId) {
        this.sessionTurnId = sessionTurnId;
    }

    public Long getCoreCommandId() {
        return coreCommandId;
    }

    public void setCoreCommandId(Long coreCommandId) {
        this.coreCommandId = coreCommandId;
    }

    public String getProviderRequestId() {
        return providerRequestId;
    }

    public void setProviderRequestId(String providerRequestId) {
        this.providerRequestId = providerRequestId;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public BigDecimal getEstimatedCost() {
        return estimatedCost;
    }

    public void setEstimatedCost(BigDecimal estimatedCost) {
        this.estimatedCost = estimatedCost;
    }

    public Long getInputTokens() {
        return inputTokens;
    }

    public void setInputTokens(Long inputTokens) {
        this.inputTokens = inputTokens;
    }

    public Long getInputCacheHitTokens() {
        return inputCacheHitTokens;
    }

    public void setInputCacheHitTokens(Long inputCacheHitTokens) {
        this.inputCacheHitTokens = inputCacheHitTokens;
    }

    public Long getInputCacheMissTokens() {
        return inputCacheMissTokens;
    }

    public void setInputCacheMissTokens(Long inputCacheMissTokens) {
        this.inputCacheMissTokens = inputCacheMissTokens;
    }

    public Long getOutputTokens() {
        return outputTokens;
    }

    public void setOutputTokens(Long outputTokens) {
        this.outputTokens = outputTokens;
    }

    public Long getTotalTokens() {
        return totalTokens;
    }

    public void setTotalTokens(Long totalTokens) {
        this.totalTokens = totalTokens;
    }

    public BigDecimal getAudioInputSeconds() {
        return audioInputSeconds;
    }

    public void setAudioInputSeconds(BigDecimal audioInputSeconds) {
        this.audioInputSeconds = audioInputSeconds;
    }

    public BigDecimal getAudioOutputSeconds() {
        return audioOutputSeconds;
    }

    public void setAudioOutputSeconds(BigDecimal audioOutputSeconds) {
        this.audioOutputSeconds = audioOutputSeconds;
    }

    public Integer getRequestCount() {
        return requestCount;
    }

    public void setRequestCount(Integer requestCount) {
        this.requestCount = requestCount;
    }

    public String getMetadataJson() {
        return metadataJson;
    }

    public void setMetadataJson(String metadataJson) {
        this.metadataJson = metadataJson;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(Instant finishedAt) {
        this.finishedAt = finishedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
