package com.atenea.remoteworker;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "atenea.remote-worker")
public class RemoteWorkerProperties {

    public static final String PROTOCOL = "agent-run-worker/v1";

    private boolean enabled;
    private String workerId = "ax42-01";
    private String endpoint = "http://127.0.0.1:8787";
    private String tokenFile = "";
    private Set<String> syntheticProjectAllowlist = new LinkedHashSet<>();
    private Duration connectTimeout = Duration.ofSeconds(3);
    private Duration requestTimeout = Duration.ofSeconds(5);
    private Duration pollInterval = Duration.ofMillis(250);
    private Duration leaseDuration = Duration.ofSeconds(90);
    private Duration reconciliationTimeout = Duration.ofMinutes(2);
    private Duration syntheticDuration = Duration.ofMillis(2_500);

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getWorkerId() { return workerId; }
    public void setWorkerId(String workerId) { this.workerId = workerId; }
    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
    public String getTokenFile() { return tokenFile; }
    public void setTokenFile(String tokenFile) { this.tokenFile = tokenFile; }
    public Set<String> getSyntheticProjectAllowlist() { return syntheticProjectAllowlist; }
    public void setSyntheticProjectAllowlist(Set<String> syntheticProjectAllowlist) {
        this.syntheticProjectAllowlist = syntheticProjectAllowlist == null
                ? new LinkedHashSet<>()
                : new LinkedHashSet<>(syntheticProjectAllowlist);
    }
    public Duration getConnectTimeout() { return connectTimeout; }
    public void setConnectTimeout(Duration connectTimeout) { this.connectTimeout = connectTimeout; }
    public Duration getRequestTimeout() { return requestTimeout; }
    public void setRequestTimeout(Duration requestTimeout) { this.requestTimeout = requestTimeout; }
    public Duration getPollInterval() { return pollInterval; }
    public void setPollInterval(Duration pollInterval) { this.pollInterval = pollInterval; }
    public Duration getLeaseDuration() { return leaseDuration; }
    public void setLeaseDuration(Duration leaseDuration) { this.leaseDuration = leaseDuration; }
    public Duration getReconciliationTimeout() { return reconciliationTimeout; }
    public void setReconciliationTimeout(Duration reconciliationTimeout) { this.reconciliationTimeout = reconciliationTimeout; }
    public Duration getSyntheticDuration() { return syntheticDuration; }
    public void setSyntheticDuration(Duration syntheticDuration) { this.syntheticDuration = syntheticDuration; }
}
