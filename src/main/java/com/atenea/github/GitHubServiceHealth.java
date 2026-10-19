package com.atenea.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.stereotype.Component;

/** Public, fixed-origin advisory only. It can never satisfy or bypass a gate. */
@Component
public class GitHubServiceHealth {
    private final ObjectMapper mapper;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private long expires;
    private String cached = "UNKNOWN";
    public GitHubServiceHealth(ObjectMapper mapper) { this.mapper = mapper; }

    public synchronized String observe() {
        long now = System.currentTimeMillis();
        if (expires > now) return cached;
        expires = now + 60_000;
        try {
            var response = client.send(HttpRequest.newBuilder(URI.create("https://www.githubstatus.com/api/v2/summary.json"))
                    .timeout(Duration.ofSeconds(3)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            cached = response.statusCode() == 200 && response.body().length <= 262144 ? parse(mapper.readTree(response.body())) : "UNKNOWN";
        } catch (Exception unavailable) {
            if (unavailable instanceof InterruptedException) Thread.currentThread().interrupt();
            cached = "UNKNOWN";
        }
        return cached;
    }

    static String parse(JsonNode value) {
        if (value == null || !value.path("components").isArray()) return "UNKNOWN";
        for (var component : value.path("components")) {
            if (!"Actions".equals(component.path("name").asText())) continue;
            return switch (component.path("status").asText()) {
                case "operational" -> "OPERATIONAL";
                case "degraded_performance", "partial_outage", "major_outage", "under_maintenance" -> "DEGRADED";
                default -> "UNKNOWN";
            };
        }
        return "UNKNOWN";
    }
}
