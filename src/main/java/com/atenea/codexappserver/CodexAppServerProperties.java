package com.atenea.codexappserver;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "atenea.codex-app-server")
public class CodexAppServerProperties {

    private URI url = URI.create("ws://host.docker.internal:8092");
    private String prompt = "say hello";
    private String cwd = "/workspace/repos/internal/atenea";
    private String model;
    private Duration connectTimeout = Duration.ofSeconds(10);
    private Duration completionTimeout = Duration.ofSeconds(300);

    public URI getUrl() {
        return url;
    }

    public void setUrl(URI url) {
        this.url = url;
    }

    public String getPrompt() {
        return prompt;
    }

    public void setPrompt(String prompt) {
        this.prompt = prompt;
    }

    public String getCwd() {
        return cwd;
    }

    public void setCwd(String cwd) {
        this.cwd = cwd;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getCompletionTimeout() {
        return completionTimeout;
    }

    public void setCompletionTimeout(Duration completionTimeout) {
        this.completionTimeout = completionTimeout;
    }
}
