package com.atenea.previews;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "atenea.previews")
public class PreviewProperties {

    public static final String PROTOCOL = "session-preview/v1";

    private boolean enabled;
    private String workerId = "ax42-01";
    private String endpoint = "http://127.0.0.1:8789";
    private String privateHost = "100.81.98.93";
    private String tokenFile = "";
    private Set<String> syntheticProjectAllowlist = new LinkedHashSet<>();
    private Duration connectTimeout = Duration.ofSeconds(3);
    private Duration requestTimeout = Duration.ofSeconds(10);
    private Duration reconciliationInterval = Duration.ofSeconds(30);
    private int reconciliationBatchSize = 20;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getWorkerId() { return workerId; }
    public void setWorkerId(String workerId) { this.workerId = workerId; }
    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
    public String getPrivateHost() { return privateHost; }
    public void setPrivateHost(String privateHost) { this.privateHost = privateHost; }
    public String getTokenFile() { return tokenFile; }
    public void setTokenFile(String tokenFile) { this.tokenFile = tokenFile; }
    public Set<String> getSyntheticProjectAllowlist() { return syntheticProjectAllowlist; }
    public void setSyntheticProjectAllowlist(Set<String> value) {
        syntheticProjectAllowlist = value == null ? new LinkedHashSet<>() : new LinkedHashSet<>(value);
    }
    public Duration getConnectTimeout() { return connectTimeout; }
    public void setConnectTimeout(Duration connectTimeout) { this.connectTimeout = connectTimeout; }
    public Duration getRequestTimeout() { return requestTimeout; }
    public void setRequestTimeout(Duration requestTimeout) { this.requestTimeout = requestTimeout; }
    public Duration getReconciliationInterval() { return reconciliationInterval; }
    public void setReconciliationInterval(Duration value) { reconciliationInterval = value; }
    public int getReconciliationBatchSize() { return reconciliationBatchSize; }
    public void setReconciliationBatchSize(int value) { reconciliationBatchSize = value; }
}
