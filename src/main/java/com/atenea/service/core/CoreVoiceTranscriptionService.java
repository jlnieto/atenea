package com.atenea.service.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class CoreVoiceTranscriptionService {

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final CoreVoiceProperties properties;

    public CoreVoiceTranscriptionService(
            @Qualifier("coreVoiceHttpClient")
            HttpClient coreVoiceHttpClient,
            ObjectMapper objectMapper,
            CoreVoiceProperties properties
    ) {
        this.httpClient = coreVoiceHttpClient;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public String transcribe(MultipartFile audio) {
        ensureEnabled();
        validateAudio(audio);

        String boundary = "atenea-core-voice-" + UUID.randomUUID();
        try {
            HttpRequest request = HttpRequest.newBuilder(transcriptionUri())
                    .header("Authorization", "Bearer " + properties.getApiKey())
                    .header("X-Client-Request-Id", "atenea-core-voice-" + UUID.randomUUID())
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .timeout(properties.getReadTimeout())
                    .POST(HttpRequest.BodyPublishers.ofByteArray(buildMultipartBody(audio, boundary)))
                    .build();

            HttpResponse<String> response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                throw classifyFailure(response.statusCode(), response.body());
            }

            JsonNode json = objectMapper.readTree(response.body());
            JsonNode textNode = json.get("text");
            String transcript = textNode == null ? null : textNode.asText(null);
            if (transcript == null || transcript.isBlank()) {
                throw new CoreVoiceTranscriptionException("Voice transcription returned an empty transcript");
            }
            return transcript.trim();
        } catch (CoreVoiceTranscriptionException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new CoreVoiceTranscriptionException(
                    "Voice transcription request failed: " + exception.getMessage(),
                    exception);
        }
    }

    private byte[] buildMultipartBody(MultipartFile audio, String boundary) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeTextPart(output, boundary, "model", properties.getTranscriptionModel());
        writeTextPart(output, boundary, "response_format", "json");
        if (properties.getPrompt() != null && !properties.getPrompt().isBlank()) {
            writeTextPart(output, boundary, "prompt", properties.getPrompt().trim());
        }
        writeFilePart(output, boundary, audio);
        output.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return output.toByteArray();
    }

    private void writeTextPart(ByteArrayOutputStream output, String boundary, String name, String value)
            throws IOException {
        output.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        output.write(("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        output.write(value.getBytes(StandardCharsets.UTF_8));
        output.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private void writeFilePart(ByteArrayOutputStream output, String boundary, MultipartFile audio)
            throws IOException {
        String filename = audio.getOriginalFilename() == null || audio.getOriginalFilename().isBlank()
                ? "voice-command-" + Instant.now().toEpochMilli() + ".m4a"
                : audio.getOriginalFilename();
        String contentType = audio.getContentType() == null || audio.getContentType().isBlank()
                ? "application/octet-stream"
                : audio.getContentType();

        output.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        output.write(("Content-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\n")
                .getBytes(StandardCharsets.UTF_8));
        output.write(("Content-Type: " + contentType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        output.write(audio.getBytes());
        output.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private CoreVoiceTranscriptionException classifyFailure(int statusCode, String body) {
        String detail = extractErrorDetail(body);
        if (statusCode == 401 || statusCode == 403) {
            return new CoreVoiceTranscriptionException("Voice transcription API rejected authentication: " + detail);
        }
        if (statusCode == 400 || statusCode == 413 || statusCode == 415) {
            return new CoreVoiceTranscriptionException("Voice transcription request was rejected: " + detail);
        }
        return new CoreVoiceTranscriptionException(
                "Voice transcription API returned HTTP " + statusCode + ": " + detail);
    }

    private String extractErrorDetail(String body) {
        try {
            JsonNode json = objectMapper.readTree(body);
            JsonNode errorNode = json.get("error");
            if (errorNode != null && errorNode.hasNonNull("message")) {
                return errorNode.get("message").asText();
            }
        } catch (Exception ignored) {
        }
        return body == null || body.isBlank()
                ? "unknown voice transcription error"
                : body.replaceAll("\\s+", " ").trim();
    }

    private void validateAudio(MultipartFile audio) {
        if (audio == null || audio.isEmpty()) {
            throw new CoreVoiceTranscriptionException("Voice command audio payload is empty");
        }
        if (audio.getSize() > properties.getMaxUploadBytes()) {
            throw new CoreVoiceTranscriptionException(
                    "Voice command audio payload exceeds the configured upload limit");
        }
    }

    private void ensureEnabled() {
        if (!properties.isEnabled()) {
            throw new CoreVoiceUnavailableException("Atenea Core voice transcription is disabled in this environment");
        }
        if (properties.getApiKey() == null || properties.getApiKey().isBlank()) {
            throw new CoreVoiceUnavailableException("Atenea Core voice transcription API key is not configured");
        }
    }

    private URI transcriptionUri() {
        return properties.getApiBaseUrl().resolve("/v1/audio/transcriptions");
    }
}
