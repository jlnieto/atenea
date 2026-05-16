package com.atenea.service.costs;

import com.atenea.api.mobile.MobileApiCostLineResponse;
import com.atenea.api.mobile.MobileApiCostProviderResponse;
import com.atenea.api.mobile.MobileApiCostsOverviewResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
public class ApiCostsService {

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final ApiCostsProperties properties;

    public ApiCostsService(
            @Qualifier("apiCostsHttpClient") HttpClient httpClient,
            ObjectMapper objectMapper,
            ApiCostsProperties properties
    ) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public MobileApiCostsOverviewResponse getOverview(int days) {
        int safeDays = Math.max(1, Math.min(days, 180));
        Instant endAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant startAt = endAt.minus(safeDays, ChronoUnit.DAYS);
        return new MobileApiCostsOverviewResponse(
                Instant.now(),
                startAt,
                endAt,
                List.of(openAiCosts(startAt, endAt, safeDays)));
    }

    private MobileApiCostProviderResponse openAiCosts(Instant startAt, Instant endAt, int limit) {
        ApiCostsProperties.OpenAi openai = properties.getOpenai();
        if (!openai.isEnabled()) {
            return unavailable("openai", "disabled");
        }
        if (openai.getAdminKey() == null || openai.getAdminKey().isBlank()) {
            return unavailable("openai", "admin_key_not_configured");
        }

        try {
            HttpRequest request = HttpRequest.newBuilder(costsUri(startAt, endAt, limit))
                    .header("Authorization", "Bearer " + openai.getAdminKey())
                    .header("Accept", "application/json")
                    .timeout(openai.getReadTimeout())
                    .GET()
                    .build();
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() / 100 != 2) {
                return unavailable("openai", "http_" + response.statusCode());
            }
            return parseOpenAiCosts(response.body());
        } catch (Exception exception) {
            return unavailable("openai", "error: " + exception.getMessage());
        }
    }

    private URI costsUri(Instant startAt, Instant endAt, int limit) {
        String query = "start_time=" + startAt.getEpochSecond()
                + "&end_time=" + endAt.getEpochSecond()
                + "&bucket_width=1d"
                + "&limit=" + limit
                + "&group_by[]=" + encode("line_item")
                + "&group_by[]=" + encode("project_id");
        return properties.getOpenai().getApiBaseUrl().resolve("/v1/organization/costs?" + query);
    }

    private MobileApiCostProviderResponse parseOpenAiCosts(byte[] body) throws Exception {
        JsonNode root = objectMapper.readTree(body);
        Map<String, CostAccumulator> lines = new LinkedHashMap<>();
        for (JsonNode bucket : root.path("data")) {
            for (JsonNode result : bucket.path("results")) {
                String lineItem = textOrDefault(result.path("line_item"), "Sin desglose");
                String projectId = textOrDefault(result.path("project_id"), null);
                String currency = textOrDefault(result.path("amount").path("currency"), "usd");
                double value = result.path("amount").path("value").asDouble(0.0d);
                String key = lineItem + "\u0000" + (projectId == null ? "" : projectId) + "\u0000" + currency;
                lines.computeIfAbsent(key, ignored -> new CostAccumulator(lineItem, projectId, currency)).amount += value;
            }
        }

        List<MobileApiCostLineResponse> responseLines = lines.values().stream()
                .sorted(Comparator.comparingDouble((CostAccumulator line) -> line.amount).reversed())
                .map(line -> new MobileApiCostLineResponse(line.label, line.projectId, line.currency, round(line.amount)))
                .toList();
        double total = responseLines.stream().mapToDouble(MobileApiCostLineResponse::amount).sum();
        String currency = responseLines.isEmpty() ? "usd" : responseLines.getFirst().currency();
        return new MobileApiCostProviderResponse("openai", true, "ok", currency, round(total), responseLines);
    }

    private MobileApiCostProviderResponse unavailable(String provider, String status) {
        return new MobileApiCostProviderResponse(provider, false, status, "usd", 0.0d, List.of());
    }

    private String textOrDefault(JsonNode node, String fallback) {
        return node != null && node.isTextual() && !node.asText().isBlank() ? node.asText() : fallback;
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private double round(double value) {
        return Math.round(value * 10000.0d) / 10000.0d;
    }

    private static class CostAccumulator {
        private final String label;
        private final String projectId;
        private final String currency;
        private double amount;

        private CostAccumulator(String label, String projectId, String currency) {
            this.label = label;
            this.projectId = projectId;
            this.currency = currency;
        }
    }
}
