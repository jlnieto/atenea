package com.atenea.deepseek;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "atenea.deepseek")
public class DeepSeekProperties {

    private URI apiBaseUrl = URI.create("https://api.deepseek.com");
    private String apiKey;
    private Duration connectTimeout = Duration.ofSeconds(10);
    private Duration readTimeout = Duration.ofSeconds(30);

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
}
