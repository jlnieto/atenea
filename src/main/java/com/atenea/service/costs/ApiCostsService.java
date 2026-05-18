package com.atenea.service.costs;

import com.atenea.api.mobile.MobileApiCostLineResponse;
import com.atenea.api.mobile.MobileApiCostModelResponse;
import com.atenea.api.mobile.MobileApiCostProviderResponse;
import com.atenea.api.mobile.MobileApiCostsOverviewResponse;
import com.atenea.api.mobile.MobileApiUsageLineResponse;
import com.atenea.api.mobile.MobileApiUsageSummaryResponse;
import com.atenea.api.mobile.MobileCodexAuthStatusResponse;
import com.atenea.codexappserver.CodexAuthStatusService;
import com.atenea.deepseek.DeepSeekProperties;
import com.atenea.persistence.costs.ApiUsageRecordEntity;
import com.atenea.persistence.costs.ApiUsageRecordRepository;
import com.atenea.service.core.CoreVoiceProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
public class ApiCostsService {

    private static final Pattern MODEL_PATTERN = Pattern.compile(
            "(?i)\\b((?:gpt|o|text|tts|whisper|dall-e|deepseek)[a-z0-9._-]*)\\b");

    private final HttpClient openAiHttpClient;
    private final HttpClient deepSeekHttpClient;
    private final ObjectMapper objectMapper;
    private final ApiCostsProperties properties;
    private final CoreVoiceProperties coreVoiceProperties;
    private final DeepSeekProperties deepSeekProperties;
    private final ApiUsageRecordRepository apiUsageRecordRepository;
    private final CodexAuthStatusService codexAuthStatusService;

    public ApiCostsService(
            @Qualifier("apiCostsHttpClient") HttpClient httpClient,
            @Qualifier("deepSeekCostsHttpClient") HttpClient deepSeekHttpClient,
            ObjectMapper objectMapper,
            ApiCostsProperties properties,
            CoreVoiceProperties coreVoiceProperties,
            DeepSeekProperties deepSeekProperties,
            ApiUsageRecordRepository apiUsageRecordRepository,
            CodexAuthStatusService codexAuthStatusService
    ) {
        this.openAiHttpClient = httpClient;
        this.deepSeekHttpClient = deepSeekHttpClient;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.coreVoiceProperties = coreVoiceProperties;
        this.deepSeekProperties = deepSeekProperties;
        this.apiUsageRecordRepository = apiUsageRecordRepository;
        this.codexAuthStatusService = codexAuthStatusService;
    }

    public MobileApiCostsOverviewResponse getOverview(int days) {
        int safeDays = Math.max(1, Math.min(days, 180));
        Instant endAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant startAt = endAt.minus(safeDays, ChronoUnit.DAYS);
        return getOverview(startAt, endAt, safeDays);
    }

    public MobileApiCostsOverviewResponse getOverview(LocalDate startDate, LocalDate endDate) {
        LocalDate safeEndDate = endDate == null ? LocalDate.now(ZoneOffset.UTC) : endDate;
        LocalDate safeStartDate = startDate == null ? safeEndDate.minusDays(29) : startDate;
        if (safeStartDate.isAfter(safeEndDate)) {
            LocalDate swap = safeStartDate;
            safeStartDate = safeEndDate;
            safeEndDate = swap;
        }
        long days = ChronoUnit.DAYS.between(safeStartDate, safeEndDate) + 1;
        if (days > 180) {
            safeStartDate = safeEndDate.minusDays(179);
            days = 180;
        }
        Instant startAt = safeStartDate.atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant endAt = safeEndDate.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        return getOverview(startAt, endAt, (int) days);
    }

    public List<MobileCodexAuthStatusResponse> getCodexAuthStatuses() {
        return codexAuthStatusService.getStatuses();
    }

    private MobileApiCostsOverviewResponse getOverview(Instant startAt, Instant endAt, int limit) {
        List<MobileApiCostProviderResponse> providers = List.of(
                openAiCosts(startAt, endAt, limit),
                deepSeekCosts(startAt, endAt));
        String currency = aggregateCurrency(providers);
        double total = providers.stream()
                .filter(MobileApiCostProviderResponse::configured)
                .mapToDouble(MobileApiCostProviderResponse::total)
                .sum();
        return new MobileApiCostsOverviewResponse(
                Instant.now(),
                startAt,
                endAt,
                currency,
                round(total),
                providers,
                openAiUsageSummaries(startAt, endAt),
                codexAuthStatusService.getStatuses());
    }

    private MobileApiCostProviderResponse openAiCosts(Instant startAt, Instant endAt, int limit) {
        ApiCostsProperties.OpenAi openai = properties.getOpenai();
        if (!openai.isEnabled()) {
            return unavailable("openai", "disabled");
        }
        String adminKey = openAiAdminKey(openai);
        if (adminKey == null || adminKey.isBlank()) {
            return unavailable("openai", "admin_key_not_configured");
        }

        try {
            HttpRequest request = HttpRequest.newBuilder(costsUri(startAt, endAt, limit))
                    .header("Authorization", "Bearer " + adminKey)
                    .header("Accept", "application/json")
                    .timeout(openai.getReadTimeout())
                    .GET()
                    .build();
            HttpResponse<byte[]> response = openAiHttpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
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
                + "&group_by=" + encode("line_item")
                + "&group_by=" + encode("project_id");
        return properties.getOpenai().getApiBaseUrl().resolve("/v1/organization/costs?" + query);
    }

    private List<MobileApiUsageSummaryResponse> openAiUsageSummaries(Instant startAt, Instant endAt) {
        ApiCostsProperties.OpenAi openai = properties.getOpenai();
        if (!openai.isEnabled()) {
            return List.of(unavailableUsage("completions", "disabled"));
        }
        String adminKey = openAiAdminKey(openai);
        if (adminKey == null || adminKey.isBlank()) {
            return List.of(unavailableUsage("completions", "admin_key_not_configured"));
        }
        OpenAiCatalog catalog = openAiCatalog(adminKey, openai);
        return List.of(
                openAiUsage("completions", "/v1/organization/usage/completions", startAt, endAt, adminKey, openai, catalog),
                openAiUsage("audio_transcriptions", "/v1/organization/usage/audio_transcriptions", startAt, endAt, adminKey, openai, catalog),
                openAiUsage("audio_speeches", "/v1/organization/usage/audio_speeches", startAt, endAt, adminKey, openai, catalog));
    }

    private MobileApiUsageSummaryResponse openAiUsage(
            String usageType,
            String path,
            Instant startAt,
            Instant endAt,
            String adminKey,
            ApiCostsProperties.OpenAi openai,
            OpenAiCatalog catalog
    ) {
        try {
            HttpRequest request = HttpRequest.newBuilder(usageUri(path, startAt, endAt))
                    .header("Authorization", "Bearer " + adminKey)
                    .header("Accept", "application/json")
                    .timeout(openai.getReadTimeout())
                    .GET()
                    .build();
            HttpResponse<byte[]> response = openAiHttpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() / 100 != 2) {
                return unavailableUsage(usageType, "http_" + response.statusCode());
            }
            return parseOpenAiUsage(usageType, response.body(), catalog);
        } catch (Exception exception) {
            return unavailableUsage(usageType, "error: " + exception.getMessage());
        }
    }

    private URI usageUri(String path, Instant startAt, Instant endAt) {
        String query = "start_time=" + startAt.getEpochSecond()
                + "&end_time=" + endAt.getEpochSecond()
                + "&bucket_width=1d"
                + "&limit=31"
                + "&group_by=" + encode("model")
                + "&group_by=" + encode("project_id")
                + "&group_by=" + encode("api_key_id");
        return properties.getOpenai().getApiBaseUrl().resolve(path + "?" + query);
    }

    private MobileApiUsageSummaryResponse parseOpenAiUsage(
            String usageType,
            byte[] body,
            OpenAiCatalog catalog
    ) throws Exception {
        List<MobileApiUsageLineResponse> lines = objectMapper.readTree(body).path("data").findValues("results")
                .stream()
                .flatMap(results -> iterable(results).stream())
                .map(result -> usageLine(usageType, result, catalog))
                .filter(line -> line.requests() > 0
                        || line.inputTokens() > 0
                        || line.cachedInputTokens() > 0
                        || line.outputTokens() > 0
                        || line.inputAudioTokens() > 0
                        || line.outputAudioTokens() > 0
                        || line.characters() > 0)
                .sorted(Comparator.comparingLong((MobileApiUsageLineResponse line) -> usageWeight(line)).reversed())
                .toList();
        return new MobileApiUsageSummaryResponse(
                "openai",
                usageType,
                "ok",
                lines.stream().mapToLong(MobileApiUsageLineResponse::requests).sum(),
                lines.stream().mapToLong(MobileApiUsageLineResponse::inputTokens).sum(),
                lines.stream().mapToLong(MobileApiUsageLineResponse::cachedInputTokens).sum(),
                lines.stream().mapToLong(MobileApiUsageLineResponse::outputTokens).sum(),
                lines.stream().mapToLong(MobileApiUsageLineResponse::inputAudioTokens).sum(),
                lines.stream().mapToLong(MobileApiUsageLineResponse::outputAudioTokens).sum(),
                lines.stream().mapToLong(MobileApiUsageLineResponse::characters).sum(),
                lines);
    }

    private MobileApiUsageLineResponse usageLine(String usageType, JsonNode result, OpenAiCatalog catalog) {
        String projectId = textOrDefault(result.path("project_id"), null);
        String apiKeyId = textOrDefault(result.path("api_key_id"), null);
        return new MobileApiUsageLineResponse(
                usageType,
                textOrDefault(result.path("model"), "Sin modelo"),
                projectId,
                catalog.projectNames().get(projectId),
                apiKeyId,
                catalog.apiKeyNames().get(apiKeyId),
                result.path("num_model_requests").asLong(result.path("num_requests").asLong(0L)),
                result.path("input_tokens").asLong(0L),
                result.path("input_cached_tokens").asLong(0L),
                result.path("output_tokens").asLong(0L),
                result.path("input_audio_tokens").asLong(0L),
                result.path("output_audio_tokens").asLong(0L),
                result.path("characters").asLong(0L));
    }

    private long usageWeight(MobileApiUsageLineResponse line) {
        return line.inputTokens()
                + line.cachedInputTokens()
                + line.outputTokens()
                + line.inputAudioTokens()
                + line.outputAudioTokens()
                + line.characters()
                + line.requests();
    }

    private MobileApiUsageSummaryResponse unavailableUsage(String usageType, String status) {
        return new MobileApiUsageSummaryResponse("openai", usageType, status, 0, 0, 0, 0, 0, 0, 0, List.of());
    }

    private OpenAiCatalog openAiCatalog(String adminKey, ApiCostsProperties.OpenAi openai) {
        Map<String, String> projectNames = new HashMap<>();
        Map<String, String> apiKeyNames = new HashMap<>();
        try {
            HttpRequest request = HttpRequest.newBuilder(properties.getOpenai().getApiBaseUrl()
                            .resolve("/v1/organization/projects?limit=100"))
                    .header("Authorization", "Bearer " + adminKey)
                    .header("Accept", "application/json")
                    .timeout(openai.getReadTimeout())
                    .GET()
                    .build();
            HttpResponse<byte[]> response = openAiHttpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() / 100 != 2) {
                return new OpenAiCatalog(projectNames, apiKeyNames);
            }
            for (JsonNode project : objectMapper.readTree(response.body()).path("data")) {
                String projectId = textOrDefault(project.path("id"), null);
                String projectName = textOrDefault(project.path("name"), projectId);
                if (projectId != null) {
                    projectNames.put(projectId, projectName);
                    apiKeyNames.putAll(openAiProjectApiKeys(adminKey, openai, projectId));
                }
            }
        } catch (Exception ignored) {
        }
        return new OpenAiCatalog(projectNames, apiKeyNames);
    }

    private Map<String, String> openAiProjectApiKeys(
            String adminKey,
            ApiCostsProperties.OpenAi openai,
            String projectId
    ) {
        Map<String, String> apiKeyNames = new HashMap<>();
        try {
            HttpRequest request = HttpRequest.newBuilder(properties.getOpenai().getApiBaseUrl()
                            .resolve("/v1/organization/projects/" + encode(projectId) + "/api_keys?limit=100"))
                    .header("Authorization", "Bearer " + adminKey)
                    .header("Accept", "application/json")
                    .timeout(openai.getReadTimeout())
                    .GET()
                    .build();
            HttpResponse<byte[]> response = openAiHttpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() / 100 != 2) {
                return apiKeyNames;
            }
            for (JsonNode apiKey : objectMapper.readTree(response.body()).path("data")) {
                String id = textOrDefault(apiKey.path("id"), null);
                String name = textOrDefault(apiKey.path("name"), id);
                if (id != null) {
                    apiKeyNames.put(id, name);
                }
            }
        } catch (Exception ignored) {
        }
        return apiKeyNames;
    }

    private MobileApiCostProviderResponse deepSeekCosts(Instant startAt, Instant endAt) {
        if (!properties.getDeepseek().isEnabled()) {
            return unavailable("deepseek", "disabled");
        }
        if (deepSeekProperties.getApiKey() == null || deepSeekProperties.getApiKey().isBlank()) {
            return unavailable("deepseek", "api_key_not_configured");
        }
        List<ApiUsageRecordEntity> usage = apiUsageRecordRepository
                .findByProviderAndStartedAtGreaterThanEqualAndStartedAtLessThan("deepseek", startAt, endAt);
        MobileApiCostProviderResponse recorded = recordedUsageCosts("deepseek", usage);
        return new MobileApiCostProviderResponse(
                recorded.provider(),
                true,
                deepSeekBalanceStatus(),
                recorded.currency(),
                recorded.total(),
                recorded.modelTotals(),
                recorded.lines());
    }

    private MobileApiCostProviderResponse recordedUsageCosts(String provider, List<ApiUsageRecordEntity> usage) {
        Map<String, CostAccumulator> lines = new LinkedHashMap<>();
        Map<String, CostAccumulator> models = new LinkedHashMap<>();
        for (ApiUsageRecordEntity record : usage) {
            String currency = firstNonBlank(record.getCurrency(), "usd").toLowerCase();
            String model = firstNonBlank(record.getModel(), "unknown");
            String feature = firstNonBlank(record.getFeature(), "unknown");
            double value = record.getEstimatedCost() == null ? 0.0d : record.getEstimatedCost().doubleValue();
            String lineKey = feature + "\u0000" + model + "\u0000" + currency;
            lines.computeIfAbsent(lineKey, ignored -> new CostAccumulator(feature, null, model, currency)).amount += value;
            String modelKey = model + "\u0000" + currency;
            models.computeIfAbsent(modelKey, ignored -> new CostAccumulator(model, null, model, currency)).amount += value;
        }
        List<MobileApiCostLineResponse> responseLines = lines.values().stream()
                .sorted(Comparator.comparingDouble((CostAccumulator line) -> line.amount).reversed())
                .map(line -> new MobileApiCostLineResponse(line.label, line.projectId, line.model, line.currency, round(line.amount)))
                .toList();
        List<MobileApiCostModelResponse> modelTotals = models.values().stream()
                .sorted(Comparator.comparingDouble((CostAccumulator line) -> line.amount).reversed())
                .map(line -> new MobileApiCostModelResponse(provider, line.model, line.currency, round(line.amount)))
                .toList();
        double total = responseLines.stream().mapToDouble(MobileApiCostLineResponse::amount).sum();
        String currency = responseLines.isEmpty() ? "usd" : responseLines.getFirst().currency();
        return new MobileApiCostProviderResponse(provider, true, "ok", currency, round(total), modelTotals, responseLines);
    }

    private String deepSeekBalanceStatus() {
        try {
            HttpRequest request = HttpRequest.newBuilder(deepSeekProperties.getApiBaseUrl().resolve("/user/balance"))
                    .header("Authorization", "Bearer " + deepSeekProperties.getApiKey())
                    .header("Accept", "application/json")
                    .timeout(deepSeekProperties.getReadTimeout())
                    .GET()
                    .build();
            HttpResponse<byte[]> response = deepSeekHttpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() / 100 != 2) {
                return "balance_http_" + response.statusCode();
            }
            JsonNode root = objectMapper.readTree(response.body());
            StringBuilder status = new StringBuilder(root.path("is_available").asBoolean(false)
                    ? "balance_available"
                    : "balance_unavailable");
            for (JsonNode balance : root.path("balance_infos")) {
                String currency = textOrDefault(balance.path("currency"), "").toUpperCase();
                String total = textOrDefault(balance.path("total_balance"), "");
                if (!currency.isBlank() && !total.isBlank()) {
                    status.append("; ").append(total).append(" ").append(currency);
                }
            }
            return status.toString();
        } catch (Exception exception) {
            return "balance_error: " + exception.getMessage();
        }
    }

    private MobileApiCostProviderResponse parseOpenAiCosts(byte[] body) throws Exception {
        JsonNode root = objectMapper.readTree(body);
        Map<String, CostAccumulator> lines = new LinkedHashMap<>();
        Map<String, CostAccumulator> models = new LinkedHashMap<>();
        for (JsonNode bucket : root.path("data")) {
            for (JsonNode result : bucket.path("results")) {
                String lineItem = textOrDefault(result.path("line_item"), "Sin desglose");
                String projectId = textOrDefault(result.path("project_id"), null);
                String model = firstNonBlank(textOrDefault(result.path("model"), null), modelFromLineItem(lineItem));
                String currency = textOrDefault(result.path("amount").path("currency"), "usd");
                double value = result.path("amount").path("value").asDouble(0.0d);
                String key = lineItem + "\u0000" + (projectId == null ? "" : projectId) + "\u0000" + model + "\u0000" + currency;
                lines.computeIfAbsent(key, ignored -> new CostAccumulator(lineItem, projectId, model, currency)).amount += value;
                String modelKey = model + "\u0000" + currency;
                models.computeIfAbsent(modelKey, ignored -> new CostAccumulator(model, null, model, currency)).amount += value;
            }
        }

        List<MobileApiCostLineResponse> responseLines = lines.values().stream()
                .sorted(Comparator.comparingDouble((CostAccumulator line) -> line.amount).reversed())
                .map(line -> new MobileApiCostLineResponse(line.label, line.projectId, line.model, line.currency, round(line.amount)))
                .toList();
        List<MobileApiCostModelResponse> modelTotals = models.values().stream()
                .sorted(Comparator.comparingDouble((CostAccumulator line) -> line.amount).reversed())
                .map(line -> new MobileApiCostModelResponse("openai", line.model, line.currency, round(line.amount)))
                .toList();
        double total = responseLines.stream().mapToDouble(MobileApiCostLineResponse::amount).sum();
        String currency = responseLines.isEmpty() ? "usd" : responseLines.getFirst().currency();
        return new MobileApiCostProviderResponse("openai", true, "ok", currency, round(total), modelTotals, responseLines);
    }

    private MobileApiCostProviderResponse unavailable(String provider, String status) {
        return new MobileApiCostProviderResponse(provider, false, status, "usd", 0.0d, List.of(), List.of());
    }

    private String openAiAdminKey(ApiCostsProperties.OpenAi openai) {
        if (openai.getAdminKey() != null && !openai.getAdminKey().isBlank()) {
            return openai.getAdminKey();
        }
        return coreVoiceProperties.getApiKey();
    }

    private String aggregateCurrency(List<MobileApiCostProviderResponse> providers) {
        return providers.stream()
                .filter(MobileApiCostProviderResponse::configured)
                .map(MobileApiCostProviderResponse::currency)
                .filter(currency -> currency != null && !currency.isBlank())
                .distinct()
                .reduce((left, right) -> left.equalsIgnoreCase(right) ? left : "mixed")
                .orElse("usd");
    }

    private String modelFromLineItem(String lineItem) {
        if (lineItem == null || lineItem.isBlank()) {
            return "Sin modelo";
        }
        var matcher = MODEL_PATTERN.matcher(lineItem);
        if (matcher.find()) {
            return matcher.group(1).toLowerCase();
        }
        return lineItem;
    }

    private String textOrDefault(JsonNode node, String fallback) {
        return node != null && node.isTextual() && !node.asText().isBlank() ? node.asText() : fallback;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "Sin modelo";
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private List<JsonNode> iterable(JsonNode node) {
        List<JsonNode> values = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(values::add);
        }
        return values;
    }

    private double round(double value) {
        return Math.round(value * 10000.0d) / 10000.0d;
    }

    private record OpenAiCatalog(
            Map<String, String> projectNames,
            Map<String, String> apiKeyNames
    ) {
    }

    private static class CostAccumulator {
        private final String label;
        private final String projectId;
        private final String model;
        private final String currency;
        private double amount;

        private CostAccumulator(String label, String projectId, String model, String currency) {
            this.label = label;
            this.projectId = projectId;
            this.model = model;
            this.currency = currency;
        }
    }
}
