package com.atenea.service.operations;

import com.atenea.api.operations.WebsiteCheckResponse;
import com.atenea.persistence.operations.ManagedWebsiteEntity;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.stereotype.Service;

@Service
public class OperationsWebsiteCheckService {

    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public WebsiteCheckResponse check(ManagedWebsiteEntity website) {
        return check(website, website.getTimeoutMillis());
    }

    public WebsiteCheckResponse check(ManagedWebsiteEntity website, int maxTimeoutMillis) {
        long started = System.nanoTime();
        int timeoutMillis = effectiveTimeoutMillis(website, maxTimeoutMillis);
        int degradedThresholdMillis = effectiveDegradedThresholdMillis(website, timeoutMillis);
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(website.getUrl()))
                    .timeout(Duration.ofMillis(timeoutMillis))
                    .GET()
                    .build();
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            long durationMillis = elapsedMillis(started);
            boolean expectedStatus = response.statusCode() == website.getExpectedStatus();
            boolean degraded = expectedStatus && durationMillis > degradedThresholdMillis;
            String state = !expectedStatus ? "DOWN" : degraded ? "DEGRADED" : "OK";
            boolean healthy = "OK".equals(state);
            return new WebsiteCheckResponse(
                    website.getId(),
                    website.getName(),
                    website.getUrl(),
                    website.getExpectedStatus(),
                    response.statusCode(),
                    durationMillis,
                    degradedThresholdMillis,
                    timeoutMillis,
                    state,
                    healthy,
                    checkError(response.statusCode(), website.getExpectedStatus(), durationMillis, degradedThresholdMillis));
        } catch (Exception exception) {
            return new WebsiteCheckResponse(
                    website.getId(),
                    website.getName(),
                    website.getUrl(),
                    website.getExpectedStatus(),
                    null,
                    elapsedMillis(started),
                    degradedThresholdMillis,
                    timeoutMillis,
                    "DOWN",
                    false,
                    exception.getMessage());
        }
    }

    private String checkError(
            int actualStatus,
            int expectedStatus,
            long durationMillis,
            int degradedThresholdMillis
    ) {
        if (actualStatus != expectedStatus) {
            return "Unexpected HTTP status " + actualStatus;
        }
        if (durationMillis > degradedThresholdMillis) {
            return "Slow response " + durationMillis + "ms above " + degradedThresholdMillis + "ms threshold";
        }
        return null;
    }

    private int effectiveTimeoutMillis(ManagedWebsiteEntity website, int maxTimeoutMillis) {
        int configured = website.getTimeoutMillis() <= 0 ? 10_000 : website.getTimeoutMillis();
        int cap = maxTimeoutMillis <= 0 ? configured : maxTimeoutMillis;
        return Math.max(200, Math.min(configured, cap));
    }

    private int effectiveDegradedThresholdMillis(ManagedWebsiteEntity website, int timeoutMillis) {
        int configured = website.getDegradedThresholdMillis() <= 0 ? 2_500 : website.getDegradedThresholdMillis();
        return Math.max(200, Math.min(configured, timeoutMillis));
    }

    private long elapsedMillis(long startedNanos) {
        return Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
    }
}
