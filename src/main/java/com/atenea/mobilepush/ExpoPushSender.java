package com.atenea.mobilepush;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ExpoPushSender {

    private static final Logger log = LoggerFactory.getLogger(ExpoPushSender.class);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final MobilePushProperties properties;

    public ExpoPushSender(
            HttpClient httpClient,
            ObjectMapper objectMapper,
            MobilePushProperties properties
    ) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public boolean isEnabled() {
        return properties.isEnabled();
    }

    public void send(List<ExpoPushMessage> messages) {
        if (messages.isEmpty()) {
            return;
        }
        if (!properties.isEnabled()) {
            log.debug("Mobile push disabled; skipping {} Expo messages", messages.size());
            return;
        }

        try {
            HttpRequest request = HttpRequest.newBuilder(properties.getExpoPushUrl())
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(
                            objectMapper.writeValueAsString(messages),
                            StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("Expo push API returned HTTP " + response.statusCode() + ": " + response.body());
            }
        } catch (Exception exception) {
            throw new IllegalStateException("Could not send Expo push notification: " + exception.getMessage(), exception);
        }
    }

    public record ExpoPushMessage(
            String to,
            String title,
            String body,
            String sound,
            java.util.Map<String, Object> data
    ) {
    }
}
