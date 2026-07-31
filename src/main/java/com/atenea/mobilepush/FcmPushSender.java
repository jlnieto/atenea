package com.atenea.mobilepush;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URLEncoder;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
public class FcmPushSender {

    private static final Logger log = LoggerFactory.getLogger(FcmPushSender.class);
    private static final String FCM_SCOPE = "https://www.googleapis.com/auth/firebase.messaging";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final MobilePushProperties properties;
    private String cachedAccessToken;
    private Instant cachedAccessTokenExpiresAt;

    public FcmPushSender(
            @Qualifier("mobilePushHttpClient") HttpClient httpClient,
            ObjectMapper objectMapper,
            MobilePushProperties properties
    ) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public void send(List<FcmPushMessage> messages) {
        if (messages.isEmpty()) {
            return;
        }
        if (!properties.isEnabled()) {
            log.debug("Mobile push disabled; skipping {} FCM messages", messages.size());
            return;
        }
        if (!isConfigured()) {
            log.warn("Mobile push FCM is not configured; skipping {} FCM messages", messages.size());
            return;
        }

        try {
            String accessToken = accessToken();
            for (FcmPushMessage message : messages) {
                sendOne(accessToken, message);
            }
        } catch (Exception exception) {
            throw new IllegalStateException("Could not send FCM push notification: " + exception.getMessage(), exception);
        }
    }

    public boolean isReady() {
        return properties.isEnabled() && isConfigured();
    }

    private void sendOne(String accessToken, FcmPushMessage message) throws Exception {
        URI uri = properties.getFcmApiBaseUrl()
                .resolve("/v1/projects/" + properties.getFcmProjectId().trim() + "/messages:send");
        Map<String, Object> payload = Map.of(
                "message", Map.of(
                        "token", message.to(),
                        "notification", Map.of(
                                "title", message.title(),
                                "body", message.body() == null ? "" : message.body()
                        ),
                        "data", stringData(message.data()),
                        "android", Map.of(
                                "notification", Map.of(
                                        "channel_id", "default",
                                        "sound", "default"
                                )
                        )
                )
        );
        HttpRequest request = HttpRequest.newBuilder(uri)
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(
                        objectMapper.writeValueAsString(payload),
                        StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("FCM API returned HTTP " + response.statusCode() + ": " + response.body());
        }
    }

    private synchronized String accessToken() throws Exception {
        Instant now = Instant.now();
        if (cachedAccessToken != null
                && cachedAccessTokenExpiresAt != null
                && cachedAccessTokenExpiresAt.isAfter(now.plusSeconds(60))) {
            return cachedAccessToken;
        }

        String assertion = signedJwt(now);
        String requestBody = "grant_type="
                + URLEncoder.encode("urn:ietf:params:oauth:grant-type:jwt-bearer", StandardCharsets.UTF_8)
                + "&assertion="
                + URLEncoder.encode(assertion, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(properties.getFcmTokenUrl())
                .header("Content-Type", "application/x-www-form-urlencoded")
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("Google OAuth token endpoint returned HTTP " + response.statusCode() + ": " + response.body());
        }
        JsonNode json = objectMapper.readTree(response.body());
        cachedAccessToken = json.path("access_token").asText(null);
        long expiresIn = Math.max(60L, json.path("expires_in").asLong(3600L));
        cachedAccessTokenExpiresAt = now.plusSeconds(expiresIn);
        if (cachedAccessToken == null || cachedAccessToken.isBlank()) {
            throw new IllegalStateException("Google OAuth token endpoint did not return access_token");
        }
        return cachedAccessToken;
    }

    private String signedJwt(Instant now) throws Exception {
        Map<String, Object> header = new HashMap<>();
        header.put("alg", "RS256");
        header.put("typ", "JWT");
        if (hasText(properties.getFcmPrivateKeyId())) {
            header.put("kid", properties.getFcmPrivateKeyId().trim());
        }
        Map<String, Object> claims = Map.of(
                "iss", properties.getFcmClientEmail().trim(),
                "scope", FCM_SCOPE,
                "aud", properties.getFcmTokenUrl().toString(),
                "iat", now.getEpochSecond(),
                "exp", now.plusSeconds(3600).getEpochSecond()
        );

        String unsigned = base64Url(objectMapper.writeValueAsBytes(header))
                + "."
                + base64Url(objectMapper.writeValueAsBytes(claims));
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(privateKey());
        signature.update(unsigned.getBytes(StandardCharsets.UTF_8));
        return unsigned + "." + base64Url(signature.sign());
    }

    private PrivateKey privateKey() throws Exception {
        String pem = properties.getFcmPrivateKey()
                .replace("\\n", "\n")
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] keyBytes = Base64.getDecoder().decode(pem);
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(keyBytes));
    }

    private boolean isConfigured() {
        return hasText(properties.getFcmProjectId())
                && hasText(properties.getFcmClientEmail())
                && hasText(properties.getFcmPrivateKey());
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private Map<String, String> stringData(Map<String, Object> data) {
        Map<String, String> result = new HashMap<>();
        data.forEach((key, value) -> result.put(key, value == null ? "" : String.valueOf(value)));
        return result;
    }

    public record FcmPushMessage(
            String to,
            String title,
            String body,
            Map<String, Object> data
    ) {
    }
}
