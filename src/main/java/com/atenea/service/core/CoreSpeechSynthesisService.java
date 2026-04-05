package com.atenea.service.core;

import com.atenea.persistence.core.CoreCommandEntity;
import com.atenea.persistence.core.CoreCommandRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;

@Service
public class CoreSpeechSynthesisService {

    private static final MediaType DEFAULT_MEDIA_TYPE = MediaType.parseMediaType("audio/mpeg");

    private final CoreCommandRepository coreCommandRepository;
    private final SessionSpeechPreparationService sessionSpeechPreparationService;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final CoreVoiceProperties properties;

    public CoreSpeechSynthesisService(
            CoreCommandRepository coreCommandRepository,
            SessionSpeechPreparationService sessionSpeechPreparationService,
            @Qualifier("coreVoiceHttpClient")
            HttpClient coreVoiceHttpClient,
            ObjectMapper objectMapper,
            CoreVoiceProperties properties
    ) {
        this.coreCommandRepository = coreCommandRepository;
        this.sessionSpeechPreparationService = sessionSpeechPreparationService;
        this.httpClient = coreVoiceHttpClient;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public CoreSpeechAudioResponse synthesizeCommandSpeech(Long commandId) {
        CoreCommandEntity command = coreCommandRepository.findById(commandId)
                .orElseThrow(() -> new CoreCommandNotFoundException(commandId));

        String text = command.getSpeakableMessage();
        if (text == null || text.isBlank()) {
            text = command.getOperatorMessage();
        }
        if (text == null || text.isBlank()) {
            throw new CoreVoiceUnavailableException("Atenea Core speech synthesis is not available for this command");
        }
        return synthesizeText(text.trim());
    }

    public CoreSpeechAudioResponse synthesizeLatestSessionResponse(Long sessionId, SessionSpeechMode mode) {
        SessionSpeechPreparationResult prepared = sessionSpeechPreparationService.prepareLatestResponse(sessionId, mode);
        return synthesizeText(prepared.text());
    }

    public CoreSpeechAudioResponse synthesizePreview(String text, String voice, Double speed) {
        if (text == null || text.isBlank()) {
            throw new CoreVoiceUnavailableException("Atenea Core voice preview requires non-empty text");
        }
        return synthesizeText(text.trim(), voice, speed);
    }

    public CoreSpeechAudioResponse synthesizeText(String text) {
        return synthesizeText(text, null, null);
    }

    private CoreSpeechAudioResponse synthesizeText(String text, String voiceOverride, Double speedOverride) {
        ensureEnabled();
        try {
            HttpRequest request = HttpRequest.newBuilder(speechUri())
                    .header("Authorization", "Bearer " + properties.getApiKey())
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .header("Accept", mediaType().toString())
                    .header("X-Client-Request-Id", "atenea-core-speech-" + UUID.randomUUID())
                    .timeout(properties.getReadTimeout())
                    .POST(HttpRequest.BodyPublishers.ofString(writeBody(text, voiceOverride, speedOverride), StandardCharsets.UTF_8))
                    .build();

            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() / 100 != 2) {
                throw classifyFailure(response.statusCode(), response.body());
            }
            if (response.body() == null || response.body().length == 0) {
                throw new CoreSpeechSynthesisException("Speech synthesis returned empty audio");
            }
            return new CoreSpeechAudioResponse(response.body(), mediaType());
        } catch (CoreVoiceUnavailableException | CoreSpeechSynthesisException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new CoreSpeechSynthesisException(
                    "Speech synthesis request failed: " + exception.getMessage(),
                    exception);
        }
    }

    private String writeBody(String text, String voiceOverride, Double speedOverride) {
        try {
            String voice = voiceOverride != null && !voiceOverride.isBlank()
                    ? voiceOverride.trim()
                    : properties.getSpeechVoice();
            double speed = speedOverride != null && speedOverride > 0
                    ? speedOverride
                    : properties.getSpeechSpeed();
            return objectMapper.writeValueAsString(Map.of(
                    "model", properties.getSpeechModel(),
                    "voice", voice,
                    "input", text,
                    "instructions", properties.getSpeechInstructions(),
                    "response_format", properties.getSpeechFormat(),
                    "speed", speed));
        } catch (Exception exception) {
            throw new CoreSpeechSynthesisException("Unable to build speech synthesis request body", exception);
        }
    }

    private CoreSpeechSynthesisException classifyFailure(int statusCode, byte[] body) {
        String detail = extractErrorDetail(body);
        if (statusCode == 401 || statusCode == 403) {
            return new CoreSpeechSynthesisException("Speech synthesis API rejected authentication: " + detail);
        }
        if (statusCode == 400 || statusCode == 413 || statusCode == 415) {
            return new CoreSpeechSynthesisException("Speech synthesis request was rejected: " + detail);
        }
        return new CoreSpeechSynthesisException(
                "Speech synthesis API returned HTTP " + statusCode + ": " + detail);
    }

    private String extractErrorDetail(byte[] body) {
        try {
            JsonNode json = objectMapper.readTree(body);
            JsonNode errorNode = json.get("error");
            if (errorNode != null && errorNode.hasNonNull("message")) {
                return errorNode.get("message").asText();
            }
        } catch (Exception ignored) {
        }
        if (body == null || body.length == 0) {
            return "unknown speech synthesis error";
        }
        return new String(body, StandardCharsets.UTF_8).replaceAll("\\s+", " ").trim();
    }

    private void ensureEnabled() {
        if (!properties.isEnabled()) {
            throw new CoreVoiceUnavailableException("Atenea Core voice synthesis is disabled in this environment");
        }
        if (properties.getApiKey() == null || properties.getApiKey().isBlank()) {
            throw new CoreVoiceUnavailableException("Atenea Core voice synthesis API key is not configured");
        }
    }

    private URI speechUri() {
        return properties.getApiBaseUrl().resolve("/v1/audio/speech");
    }

    private MediaType mediaType() {
        return switch (properties.getSpeechFormat().toLowerCase()) {
            case "wav" -> MediaType.parseMediaType("audio/wav");
            case "aac" -> MediaType.parseMediaType("audio/aac");
            case "flac" -> MediaType.parseMediaType("audio/flac");
            case "opus" -> MediaType.parseMediaType("audio/ogg");
            default -> DEFAULT_MEDIA_TYPE;
        };
    }
}
