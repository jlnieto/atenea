package com.atenea.service.core;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "atenea.core.voice")
public class CoreVoiceProperties {

    private boolean enabled = false;
    private URI apiBaseUrl = URI.create("https://api.openai.com");
    private String apiKey;
    private String transcriptionModel = "gpt-4o-mini-transcribe";
    private String prompt = """
            Transcribe the operator voice command faithfully.
            Preserve project names, branch names, repository names, pull request references, and delivery terms.
            The command may be in Spanish or English.
            """;
    private Duration connectTimeout = Duration.ofSeconds(10);
    private Duration readTimeout = Duration.ofSeconds(60);
    private long maxUploadBytes = 10_000_000L;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public URI getApiBaseUrl() {
        return apiBaseUrl;
    }

    public void setApiBaseUrl(URI apiBaseUrl) {
        this.apiBaseUrl = apiBaseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getTranscriptionModel() {
        return transcriptionModel;
    }

    public void setTranscriptionModel(String transcriptionModel) {
        this.transcriptionModel = transcriptionModel;
    }

    public String getPrompt() {
        return prompt;
    }

    public void setPrompt(String prompt) {
        this.prompt = prompt;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(Duration readTimeout) {
        this.readTimeout = readTimeout;
    }

    public long getMaxUploadBytes() {
        return maxUploadBytes;
    }

    public void setMaxUploadBytes(long maxUploadBytes) {
        this.maxUploadBytes = maxUploadBytes;
    }
}
