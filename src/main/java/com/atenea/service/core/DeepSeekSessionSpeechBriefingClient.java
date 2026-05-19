package com.atenea.service.core;

import com.atenea.deepseek.DeepSeekProperties;
import com.atenea.service.costs.ApiUsageRecordRequest;
import com.atenea.service.costs.ApiUsageRecordingService;
import com.atenea.service.costs.DeepSeekUsagePricing;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;

@Service
public class DeepSeekSessionSpeechBriefingClient implements SessionSpeechBriefingClient {

    private static final String FEATURE = "session_speech_briefing";
    private static final String SYSTEM_PROMPT = """
            Eres el editor de voz de Atenea para un operador que escucha desde movil.

            Tu trabajo no es resumir bonito: es convertir una respuesta tecnica de Codex en una lectura extremadamente util, breve, accionable y natural para TTS.

            Devuelve exactamente un objeto JSON y nada mas:
            {"speech":"texto listo para leer"}

            Reglas de calidad:
            - Escribe siempre en espanol natural.
            - Empieza por el resultado o estado real, no por contexto.
            - Mantén solo informacion para tomar decisiones: que quedo hecho, que fallo, que se verifico, que bloqueo existe y cual es el siguiente paso.
            - Si Codex no pudo probar, dilo de forma clara y corta.
            - Si hay una ejecucion en curso, dilo claramente.
            - No incluyas rutas, URLs, comandos, hashes, nombres internos de ficheros, trazas, codigo, markdown, listas largas ni relleno.
            - No digas "he revisado" salvo que sea informacion relevante para decidir.
            - No inventes verificaciones, despliegues, PRs ni pruebas.
            - No prometas acciones futuras como si ya estuvieran hechas.
            - Para modo brief, maximo dos o tres frases.
            - Para modo full, organiza en frases cortas y escucha natural, sin encabezados markdown.
            - Respeta el limite maximo de caracteres indicado por el backend.
            """;

    private final DeepSeekProperties deepSeekProperties;
    private final SessionSpeechBriefingProperties briefingProperties;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final ApiUsageRecordingService usageRecordingService;

    public DeepSeekSessionSpeechBriefingClient(
            DeepSeekProperties deepSeekProperties,
            SessionSpeechBriefingProperties briefingProperties,
            @Qualifier("deepSeekChatHttpClient")
            HttpClient httpClient,
            ObjectMapper objectMapper,
            ApiUsageRecordingService usageRecordingService
    ) {
        this.deepSeekProperties = deepSeekProperties;
        this.briefingProperties = briefingProperties;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.usageRecordingService = usageRecordingService;
    }

    @Override
    public boolean supports(String provider) {
        return "deepseek".equalsIgnoreCase(provider == null ? "" : provider.trim());
    }

    @Override
    public SessionSpeechBriefingResult createBriefing(SessionSpeechBriefingRequest request) {
        ensureAvailable();
        Instant startedAt = Instant.now();
        String model = briefingProperties.getModel();
        try {
            HttpRequest httpRequest = HttpRequest.newBuilder(chatCompletionsUri())
                    .header("Authorization", "Bearer " + deepSeekProperties.getApiKey())
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .header("Accept", MediaType.APPLICATION_JSON_VALUE)
                    .header("X-Client-Request-Id", "atenea-speech-briefing-" + UUID.randomUUID())
                    .timeout(deepSeekProperties.getReadTimeout())
                    .POST(HttpRequest.BodyPublishers.ofString(writeRequestBody(request), StandardCharsets.UTF_8))
                    .build();

            HttpResponse<byte[]> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofByteArray());
            JsonNode body = parseResponseBody(response.body());
            Instant finishedAt = Instant.now();
            if (response.statusCode() / 100 != 2) {
                recordUsage(request, body, model, "failed", startedAt, finishedAt);
                throw new DeepSeekBriefingFailureRecordedException(
                        "DeepSeek speech briefing failed with HTTP " + response.statusCode());
            }
            String speech = extractSpeech(body);
            recordUsage(request, body, model, "succeeded", startedAt, finishedAt);
            return new SessionSpeechBriefingResult(speech, "deepseek", model);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            recordFailure(request, model, startedAt);
            throw new IllegalStateException("DeepSeek speech briefing was interrupted", exception);
        } catch (DeepSeekBriefingFailureRecordedException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            recordFailure(request, model, startedAt);
            throw exception;
        } catch (Exception exception) {
            recordFailure(request, model, startedAt);
            throw new IllegalStateException("DeepSeek speech briefing request failed", exception);
        }
    }

    private void ensureAvailable() {
        if (!briefingProperties.isEnabled()) {
            throw new CoreVoiceUnavailableException("Session speech briefing is disabled");
        }
        if (deepSeekProperties.getApiKey() == null || deepSeekProperties.getApiKey().isBlank()) {
            throw new CoreVoiceUnavailableException("DeepSeek API key is not configured for session speech briefing");
        }
    }

    private String writeRequestBody(SessionSpeechBriefingRequest request) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", briefingProperties.getModel());
            body.put("temperature", briefingProperties.getTemperature());
            body.put("max_tokens", briefingProperties.getMaxOutputTokens());
            body.put("response_format", Map.of("type", "json_object"));
            body.put("messages", List.of(
                    Map.of("role", "system", "content", SYSTEM_PROMPT),
                    Map.of("role", "user", "content", objectMapper.writeValueAsString(promptContext(request)))));
            return objectMapper.writeValueAsString(body);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not serialize DeepSeek speech briefing request", exception);
        }
    }

    private Map<String, Object> promptContext(SessionSpeechBriefingRequest request) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("promptVersion", briefingProperties.getPromptVersion());
        context.put("mode", request.mode().name().toLowerCase(Locale.ROOT));
        context.put("maxOutputCharacters", request.maxOutputCharacters());
        context.put("sessionId", request.sessionId());
        context.put("runInProgress", request.view().runInProgress());
        context.put("canCreateTurn", request.view().canCreateTurn());
        context.put("lastError", cleanForPrompt(request.view().lastError(), 1_000));
        Map<String, Object> session = new LinkedHashMap<>();
        session.put("status", request.view().session().status().name());
        session.put("operationalState", request.view().session().operationalState() == null
                ? ""
                : request.view().session().operationalState().name());
        session.put("title", request.view().session().title() == null ? "" : request.view().session().title());
        session.put("pullRequestStatus", request.view().session().pullRequestStatus() == null
                ? ""
                : request.view().session().pullRequestStatus().name());
        context.put("session", session);
        if (request.latestRun() != null) {
            Map<String, Object> latestRun = new LinkedHashMap<>();
            latestRun.put("status", request.latestRun().status().name());
            latestRun.put("outputSummary", cleanForPrompt(request.latestRun().outputSummary(), 2_000));
            latestRun.put("errorSummary", cleanForPrompt(request.latestRun().errorSummary(), 1_000));
            context.put("latestRun", latestRun);
        }
        if (request.insights() != null) {
            Map<String, Object> insights = new LinkedHashMap<>();
            insights.put("latestProgress", cleanForPrompt(request.insights().latestProgress(), 700));
            insights.put("nextStepRecommended", cleanForPrompt(request.insights().nextStepRecommended(), 700));
            if (request.insights().currentBlocker() != null) {
                Map<String, Object> blocker = new LinkedHashMap<>();
                blocker.put("category", request.insights().currentBlocker().category() == null
                        ? ""
                        : request.insights().currentBlocker().category());
                blocker.put("summary", cleanForPrompt(request.insights().currentBlocker().summary(), 700));
                insights.put("currentBlocker", blocker);
            }
            context.put("insights", insights);
        }
        if (request.actions() != null) {
            context.put("availableActions", Map.of(
                    "canPublish", request.actions().canPublish(),
                    "canSyncPullRequest", request.actions().canSyncPullRequest(),
                    "canClose", request.actions().canClose(),
                    "canGenerateDeliverables", request.actions().canGenerateDeliverables(),
                    "canApproveDeliverables", request.actions().canApproveDeliverables(),
                    "canMarkApprovedPriceEstimateBilled", request.actions().canMarkApprovedPriceEstimateBilled()));
        }
        context.put("latestCodexResponse", cleanForPrompt(request.sourceText(), briefingProperties.getMaxInputCharacters()));
        return context;
    }

    private String extractSpeech(JsonNode body) {
        String content = body.path("choices").path(0).path("message").path("content").asText("");
        if (content.isBlank()) {
            throw new IllegalStateException("DeepSeek speech briefing returned empty content");
        }
        JsonNode contentJson;
        try {
            contentJson = objectMapper.readTree(stripCodeFences(content));
        } catch (Exception exception) {
            throw new IllegalStateException("DeepSeek speech briefing returned non-JSON content", exception);
        }
        String speech = contentJson.path("speech").asText("").trim();
        if (speech.isBlank()) {
            throw new IllegalStateException("DeepSeek speech briefing returned an empty speech field");
        }
        return speech.replaceAll("\\s+", " ").trim();
    }

    private JsonNode parseResponseBody(byte[] body) {
        try {
            return objectMapper.readTree(body == null ? new byte[0] : body);
        } catch (Exception exception) {
            throw new IllegalStateException("DeepSeek speech briefing returned invalid JSON", exception);
        }
    }

    private void recordUsage(
            SessionSpeechBriefingRequest request,
            JsonNode body,
            String model,
            String status,
            Instant startedAt,
            Instant finishedAt
    ) {
        JsonNode usage = body.path("usage");
        long promptTokens = longValue(usage, "prompt_tokens");
        long cacheHitTokens = longValue(usage, "prompt_cache_hit_tokens");
        long cacheMissTokens = longValue(usage, "prompt_cache_miss_tokens");
        if (cacheHitTokens == 0 && cacheMissTokens == 0 && promptTokens > 0) {
            cacheMissTokens = promptTokens;
        }
        long completionTokens = longValue(usage, "completion_tokens");
        long totalTokens = longValue(usage, "total_tokens");
        BigDecimal estimatedCost = DeepSeekUsagePricing.estimateUsd(model, cacheHitTokens, cacheMissTokens, completionTokens);
        safeRecordUsage(new ApiUsageRecordRequest(
                "deepseek",
                model,
                FEATURE,
                null,
                status,
                request.view().session().projectId(),
                request.sessionId(),
                null,
                null,
                null,
                body.path("id").asText(null),
                "usd",
                estimatedCost,
                promptTokens,
                cacheHitTokens,
                cacheMissTokens,
                completionTokens,
                totalTokens,
                null,
                null,
                1,
                usageMetadata(request),
                startedAt,
                finishedAt));
    }

    private void recordFailure(SessionSpeechBriefingRequest request, String model, Instant startedAt) {
        safeRecordUsage(new ApiUsageRecordRequest(
                "deepseek",
                model,
                FEATURE,
                null,
                "failed",
                request.view().session().projectId(),
                request.sessionId(),
                null,
                null,
                null,
                null,
                "usd",
                BigDecimal.ZERO,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                1,
                usageMetadata(request),
                startedAt,
                Instant.now()));
    }

    private void safeRecordUsage(ApiUsageRecordRequest request) {
        try {
            usageRecordingService.record(request);
        } catch (Exception ignored) {
        }
    }

    private String usageMetadata(SessionSpeechBriefingRequest request) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "mode", request.mode().name(),
                    "promptVersion", briefingProperties.getPromptVersion()));
        } catch (Exception ignored) {
            return null;
        }
    }

    private long longValue(JsonNode node, String fieldName) {
        JsonNode value = node.path(fieldName);
        return value.isNumber() ? value.asLong() : 0L;
    }

    private String cleanForPrompt(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value
                .replaceAll("(?s)```.*?```", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, Math.max(0, maxLength - 3)).trim() + "...";
    }

    private String stripCodeFences(String answer) {
        String trimmed = answer.trim();
        if (!trimmed.startsWith("```")) {
            return trimmed;
        }
        int firstNewline = trimmed.indexOf('\n');
        int closingFence = trimmed.lastIndexOf("```");
        if (firstNewline < 0 || closingFence <= firstNewline) {
            return trimmed;
        }
        return trimmed.substring(firstNewline + 1, closingFence).trim();
    }

    private URI chatCompletionsUri() {
        String base = deepSeekProperties.getApiBaseUrl().toString().replaceAll("/+$", "");
        return URI.create(base + "/chat/completions");
    }

    private static class DeepSeekBriefingFailureRecordedException extends IllegalStateException {

        DeepSeekBriefingFailureRecordedException(String message) {
            super(message);
        }
    }
}
