package com.atenea.attachments;

import java.time.Duration;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "atenea.attachments")
public class AttachmentProperties {

    public static final String PROTOCOL = "worksession-attachment/v1";
    public static final long DEFAULT_MAX_FILE_BYTES = 16L * 1024L * 1024L;
    public static final long DEFAULT_MAX_SESSION_BYTES = 256L * 1024L * 1024L;
    public static final int DEFAULT_MAX_ATTACHMENTS_PER_TURN = 4;
    public static final long DEFAULT_MAX_ATTACHMENT_BYTES_PER_TURN = 32L * 1024L * 1024L;
    public static final List<String> TURN_IMAGE_CONTENT_TYPES = List.of(
            "image/png",
            "image/jpeg",
            "image/webp");

    private boolean enabled;
    private String workerId = "ax42-01";
    private String endpoint = "http://127.0.0.1:8788";
    private String tokenFile = "";
    private Set<String> syntheticProjectAllowlist = new LinkedHashSet<>();
    private Set<String> realProjectAllowlist = new LinkedHashSet<>();
    private Duration connectTimeout = Duration.ofSeconds(3);
    private Duration requestTimeout = Duration.ofSeconds(15);
    private long maxFileBytes = DEFAULT_MAX_FILE_BYTES;
    private long maxSessionBytes = DEFAULT_MAX_SESSION_BYTES;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getWorkerId() {
        return workerId;
    }

    public void setWorkerId(String workerId) {
        this.workerId = workerId;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public String getTokenFile() {
        return tokenFile;
    }

    public void setTokenFile(String tokenFile) {
        this.tokenFile = tokenFile;
    }

    public Set<String> getSyntheticProjectAllowlist() {
        return syntheticProjectAllowlist;
    }

    public void setSyntheticProjectAllowlist(Set<String> syntheticProjectAllowlist) {
        this.syntheticProjectAllowlist = syntheticProjectAllowlist == null
                ? new LinkedHashSet<>()
                : new LinkedHashSet<>(syntheticProjectAllowlist);
    }

    public Set<String> getRealProjectAllowlist() {
        return realProjectAllowlist;
    }

    public void setRealProjectAllowlist(Set<String> realProjectAllowlist) {
        this.realProjectAllowlist = realProjectAllowlist == null
                ? new LinkedHashSet<>()
                : new LinkedHashSet<>(realProjectAllowlist);
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getRequestTimeout() {
        return requestTimeout;
    }

    public void setRequestTimeout(Duration requestTimeout) {
        this.requestTimeout = requestTimeout;
    }

    public long getMaxFileBytes() {
        return maxFileBytes;
    }

    public void setMaxFileBytes(long maxFileBytes) {
        this.maxFileBytes = maxFileBytes;
    }

    public long getMaxSessionBytes() {
        return maxSessionBytes;
    }

    public void setMaxSessionBytes(long maxSessionBytes) {
        this.maxSessionBytes = maxSessionBytes;
    }
}
