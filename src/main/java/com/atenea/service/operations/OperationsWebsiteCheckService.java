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
        long started = System.nanoTime();
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(website.getUrl()))
                    .timeout(Duration.ofMillis(website.getTimeoutMillis()))
                    .GET()
                    .build();
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            long durationMillis = elapsedMillis(started);
            boolean healthy = response.statusCode() == website.getExpectedStatus();
            return new WebsiteCheckResponse(
                    website.getId(),
                    website.getName(),
                    website.getUrl(),
                    website.getExpectedStatus(),
                    response.statusCode(),
                    durationMillis,
                    healthy,
                    healthy ? null : "Unexpected HTTP status " + response.statusCode());
        } catch (Exception exception) {
            return new WebsiteCheckResponse(
                    website.getId(),
                    website.getName(),
                    website.getUrl(),
                    website.getExpectedStatus(),
                    null,
                    elapsedMillis(started),
                    false,
                    exception.getMessage());
        }
    }

    private long elapsedMillis(long startedNanos) {
        return Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
    }
}
