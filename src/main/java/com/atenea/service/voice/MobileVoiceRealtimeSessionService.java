package com.atenea.service.voice;

import com.atenea.api.mobile.MobileVoiceRealtimeSessionResponse;
import com.atenea.service.core.CoreSpeechSynthesisException;
import com.atenea.service.core.CoreVoiceProperties;
import com.atenea.service.core.CoreVoiceUnavailableException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;

@Service
public class MobileVoiceRealtimeSessionService {

    private static final int MAX_REALTIME_TRANSCRIPTION_PROMPT_CHARS = 1024;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final CoreVoiceProperties properties;

    public MobileVoiceRealtimeSessionService(
            @Qualifier("coreVoiceHttpClient") HttpClient coreVoiceHttpClient,
            ObjectMapper objectMapper,
            CoreVoiceProperties properties
    ) {
        this.httpClient = coreVoiceHttpClient;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public MobileVoiceRealtimeSessionResponse createSession(String operatorContext) {
        return createSession(operatorContext, null, null);
    }

    public MobileVoiceRealtimeSessionResponse createSession(String operatorContext, String voiceOverride, Double speedOverride) {
        ensureEnabled();
        try {
            HttpRequest request = HttpRequest.newBuilder(realtimeClientSecretsUri())
                    .header("Authorization", "Bearer " + properties.getApiKey())
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .header("Accept", MediaType.APPLICATION_JSON_VALUE)
                    .header("X-Client-Request-Id", "atenea-mobile-realtime-" + UUID.randomUUID())
                    .timeout(properties.getReadTimeout())
                    .POST(HttpRequest.BodyPublishers.ofString(
                            writeBody(operatorContext, voiceOverride, speedOverride),
                            StandardCharsets.UTF_8))
                    .build();

            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() / 100 != 2) {
                throw new CoreSpeechSynthesisException(
                        "Realtime session API returned HTTP " + response.statusCode() + ": " + errorDetail(response.body()));
            }
            JsonNode json = objectMapper.readTree(response.body());
            JsonNode clientSecret = json.path("client_secret");
            String value = firstText(clientSecret.path("value"), json.path("value"));
            if (value == null || value.isBlank()) {
                throw new CoreSpeechSynthesisException("Realtime session API did not return a client secret");
            }
            Long expiresAt = firstLong(clientSecret.path("expires_at"), json.path("expires_at"));
            String voice = resolveVoice(voiceOverride);
            return new MobileVoiceRealtimeSessionResponse(
                    "openai",
                    "transcription",
                    properties.getRealtimeModel(),
                    voice,
                    value,
                    expiresAt,
                    "ready");
        } catch (CoreVoiceUnavailableException | CoreSpeechSynthesisException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new CoreSpeechSynthesisException(
                    "Realtime session request failed: " + exception.getMessage(),
                    exception);
        }
    }

    private String writeBody(String operatorContext, String voiceOverride, Double speedOverride) {
        try {
            Map<String, Object> turnDetection = new LinkedHashMap<>();
            turnDetection.put("type", "semantic_vad");
            turnDetection.put("create_response", false);
            turnDetection.put("interrupt_response", false);
            turnDetection.put("eagerness", "medium");

            Map<String, Object> input = new LinkedHashMap<>();
            input.put("noise_reduction", Map.of("type", "near_field"));
            input.put("transcription", Map.of(
                    "model", properties.getTranscriptionModel(),
                    "prompt", realtimeTranscriptionPrompt(operatorContext),
                    "language", "es"));
            input.put("turn_detection", turnDetection);

            Map<String, Object> audio = new LinkedHashMap<>();
            audio.put("input", input);

            Map<String, Object> session = new LinkedHashMap<>();
            session.put("type", "transcription");
            session.put("audio", audio);

            return objectMapper.writeValueAsString(Map.of("session", session));
        } catch (Exception exception) {
            throw new CoreSpeechSynthesisException("Unable to build realtime session request body", exception);
        }
    }

    private String realtimeInstructions(String operatorContext) {
        String context = operatorContext == null || operatorContext.isBlank()
                ? "Sin foco operativo cargado desde Atenea."
                : operatorContext.trim();
        return properties.getRealtimeInstructions()
                + "\n\nREGLA CRITICA DE VERACIDAD:\n"
                + "No inventes proyectos, worksessions, servidores, emails, tareas, ramas ni respuestas de Codex.\n"
                + "Interpreta siempre el audio del operador como espanol de Espana, aunque alguna palabra suene parecida a otro idioma.\n"
                + "Responde siempre en espanol de Espana, hablame de tu y evita construcciones latinoamericanas si hay alternativa natural en Espana.\n"
                + "Solo puedes mencionar datos que aparezcan literalmente en el contexto operativo incluido abajo o que el operador haya dicho en esta sesion de voz.\n"
                + "Si el operador pregunta donde estas o por un proyecto y el dato no esta en el contexto operativo, responde: \"No tengo ese contexto cargado en esta sesion de voz\".\n"
                + "Si el operador pide ejecutar trabajo, hacer cambios, pasar algo a Codex, revisar codigo o actuar sobre Atenea, responde solo: \"Lo paso a Atenea Core\".\n"
                + "Si el operador pide guardar, crear, anotar o tomar una nota, no afirmes que esta guardada; la app Android ejecutara la accion real con backend.\n"
                + "No respondas nunca de forma autonoma a audio de entrada. La app Android decidira que transcripciones se procesan y cuando se crea una respuesta.\n"
                + "No digas que no tienes conexion operativa con Codex; Atenea Core procesara la transcripcion de forma externa a esta sesion Realtime.\n\n"
                + "CONTEXTO OPERATIVO REAL:\n"
                + context;
    }

    private String realtimeTranscriptionPrompt(String operatorContext) {
        String context = limit(operatorContext == null || operatorContext.isBlank()
                ? "Sin foco operativo cargado desde Atenea."
                : operatorContext.trim(), 420);
        return limit("""
                Transcribe fielmente comandos de voz para Atenea en espanol de Espana.
                La palabra de activacion es Atenea. Acepta variantes foneticas como Athenea, Antena, Antenea o Atenia.
                Prioriza vocabulario de trabajo: nota, fin, confirma, cancela, continua, repite, Codex, proyecto, WorkSession.
                No traduzcas a otros idiomas. No completes frases que el operador no haya dicho.

                Contexto operativo:
                %s
                """.formatted(context), MAX_REALTIME_TRANSCRIPTION_PROMPT_CHARS);
    }

    private static String limit(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, Math.max(0, maxChars - 1)).stripTrailing() + "…";
    }

    private String resolveVoice(String voiceOverride) {
        String candidate = voiceOverride == null || voiceOverride.isBlank()
                ? properties.getRealtimeVoice()
                : voiceOverride.trim().toLowerCase();
        return switch (candidate) {
            case "alloy", "ash", "ballad", "coral", "echo", "sage", "shimmer", "verse", "marin", "cedar" -> candidate;
            default -> properties.getRealtimeVoice();
        };
    }

    private double resolveSpeed(Double speedOverride) {
        double value = speedOverride == null ? properties.getRealtimeSpeed() : speedOverride;
        return Math.max(0.6d, Math.min(1.5d, value));
    }

    private String errorDetail(byte[] body) {
        try {
            JsonNode json = objectMapper.readTree(body);
            JsonNode errorNode = json.get("error");
            if (errorNode != null && errorNode.hasNonNull("message")) {
                return errorNode.get("message").asText();
            }
        } catch (Exception ignored) {
        }
        if (body == null || body.length == 0) {
            return "unknown realtime session error";
        }
        return new String(body, StandardCharsets.UTF_8).replaceAll("\\s+", " ").trim();
    }

    private void ensureEnabled() {
        if (!properties.isEnabled()) {
            throw new CoreVoiceUnavailableException("Atenea Realtime voice is disabled in this environment");
        }
        if (properties.getApiKey() == null || properties.getApiKey().isBlank()) {
            throw new CoreVoiceUnavailableException("Atenea Realtime voice API key is not configured");
        }
    }

    private URI realtimeClientSecretsUri() {
        return properties.getApiBaseUrl().resolve("/v1/realtime/client_secrets");
    }

    private static String firstText(JsonNode first, JsonNode second) {
        if (first != null && first.isTextual() && !first.asText().isBlank()) {
            return first.asText();
        }
        if (second != null && second.isTextual() && !second.asText().isBlank()) {
            return second.asText();
        }
        return null;
    }

    private static Long firstLong(JsonNode first, JsonNode second) {
        if (first != null && first.canConvertToLong()) {
            return first.asLong();
        }
        if (second != null && second.canConvertToLong()) {
            return second.asLong();
        }
        return null;
    }
}
