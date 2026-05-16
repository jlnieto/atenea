package com.atenea.service.voice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.atenea.service.core.CoreVoiceProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Method;
import java.net.http.HttpClient;
import org.junit.jupiter.api.Test;

class MobileVoiceRealtimeSessionServiceTest {

    @Test
    void realtimeSessionIsTranscriptionOnlySoProviderCannotSpeakAutonomously() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        CoreVoiceProperties properties = new CoreVoiceProperties();
        properties.setEnabled(true);
        properties.setApiKey("test-key");
        MobileVoiceRealtimeSessionService service = new MobileVoiceRealtimeSessionService(
                HttpClient.newHttpClient(),
                objectMapper,
                properties);

        Method method = MobileVoiceRealtimeSessionService.class.getDeclaredMethod(
                "writeBody",
                String.class,
                String.class,
                Double.class);
        method.setAccessible(true);
        String body = (String) method.invoke(service, "Proyecto: fomasys", "marin", 1.05d);

        JsonNode session = objectMapper.readTree(body).path("session");
        assertEquals("transcription", session.path("type").asText());
        assertFalse(session.has("model"));
        assertFalse(session.has("instructions"));
        assertEquals(false, session.path("audio").path("input").path("turn_detection").path("create_response").asBoolean());
        assertEquals("gpt-4o-mini-transcribe", session.path("audio").path("input").path("transcription").path("model").asText());
        assertEquals("es", session.path("audio").path("input").path("transcription").path("language").asText());
        assertFalse(session.path("audio").path("input").path("transcription").path("prompt").asText().length() > 1024);
        assertFalse(session.has("output_modalities"));
        assertFalse(session.path("audio").has("output"));
    }

    @Test
    void transcriptionPromptIsAlwaysWithinProviderLimit() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        CoreVoiceProperties properties = new CoreVoiceProperties();
        properties.setEnabled(true);
        properties.setApiKey("test-key");
        MobileVoiceRealtimeSessionService service = new MobileVoiceRealtimeSessionService(
                HttpClient.newHttpClient(),
                objectMapper,
                properties);

        Method method = MobileVoiceRealtimeSessionService.class.getDeclaredMethod(
                "writeBody",
                String.class,
                String.class,
                Double.class);
        method.setAccessible(true);
        String body = (String) method.invoke(service, "Proyecto: fomasys\n".repeat(300), "marin", 1.05d);

        String prompt = objectMapper.readTree(body)
                .path("session")
                .path("audio")
                .path("input")
                .path("transcription")
                .path("prompt")
                .asText();
        assertFalse(prompt.length() > 1024);
    }
}
