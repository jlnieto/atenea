package com.atenea.service.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

import com.atenea.api.mobile.MobileSessionActionsResponse;
import com.atenea.api.mobile.MobileSessionBlockerResponse;
import com.atenea.api.mobile.MobileSessionInsightsResponse;
import com.atenea.api.worksession.SessionOperationalSnapshotResponse;
import com.atenea.api.worksession.WorkSessionOperationalState;
import com.atenea.api.worksession.WorkSessionResponse;
import com.atenea.api.worksession.WorkSessionViewResponse;
import com.atenea.deepseek.DeepSeekProperties;
import com.atenea.persistence.worksession.WorkSessionPullRequestStatus;
import com.atenea.persistence.worksession.WorkSessionStatus;
import com.atenea.service.costs.ApiUsageRecordingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DeepSeekSessionSpeechBriefingClientTest {

    @Mock
    private ApiUsageRecordingService usageRecordingService;

    private HttpServer server;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void createsSpeechBriefingFromDeepSeekChatCompletion() {
        server.createContext("/chat/completions", exchange -> {
            String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (!requestBody.contains("deepseek-v4-flash") || !requestBody.contains("latestCodexResponse")) {
                exchange.sendResponseHeaders(400, -1);
                return;
            }
            byte[] response = """
                    {
                      "id": "ds-test",
                      "choices": [
                        {
                          "message": {
                            "content": "{\\"speech\\":\\"Codex ha terminado y las pruebas principales pasan.\\"}"
                          }
                        }
                      ],
                      "usage": {
                        "prompt_tokens": 100,
                        "prompt_cache_hit_tokens": 20,
                        "prompt_cache_miss_tokens": 80,
                        "completion_tokens": 12,
                        "total_tokens": 112
                      }
                    }
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });

        DeepSeekSessionSpeechBriefingClient client = new DeepSeekSessionSpeechBriefingClient(
                deepSeekProperties(),
                briefingProperties(),
                HttpClient.newHttpClient(),
                new ObjectMapper(),
                usageRecordingService);

        SessionSpeechBriefingResult result = client.createBriefing(request());

        assertEquals("Codex ha terminado y las pruebas principales pasan.", result.text());
        assertEquals("deepseek", result.provider());
        assertEquals("deepseek-v4-flash", result.model());
        verify(usageRecordingService).record(any());
    }

    private DeepSeekProperties deepSeekProperties() {
        DeepSeekProperties properties = new DeepSeekProperties();
        properties.setApiBaseUrl(URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
        properties.setApiKey("test-key");
        return properties;
    }

    private SessionSpeechBriefingProperties briefingProperties() {
        SessionSpeechBriefingProperties properties = new SessionSpeechBriefingProperties();
        properties.setEnabled(true);
        properties.setModel("deepseek-v4-flash");
        return properties;
    }

    private SessionSpeechBriefingRequest request() {
        WorkSessionResponse session = new WorkSessionResponse(
                44L,
                7L,
                WorkSessionStatus.OPEN,
                WorkSessionOperationalState.IDLE,
                "Sesion de voz",
                "main",
                "atenea/session-44",
                "thread-1",
                null,
                WorkSessionPullRequestStatus.NOT_CREATED,
                null,
                Instant.parse("2026-03-30T10:00:00Z"),
                Instant.parse("2026-03-30T10:05:00Z"),
                null,
                null,
                null,
                null,
                null,
                false,
                new SessionOperationalSnapshotResponse(true, true, "main", false));
        return new SessionSpeechBriefingRequest(
                44L,
                SessionSpeechMode.BRIEF,
                430,
                new WorkSessionViewResponse(session, false, true, null, null, "## Verificación\nTests en verde."),
                new MobileSessionInsightsResponse(
                        "Cambio aplicado",
                        new MobileSessionBlockerResponse("NONE", "Sin bloqueo activo"),
                        "Publicar la pull request"),
                new MobileSessionActionsResponse(true, true, true, true, true, true, true),
                null,
                "## Verificación\nTests en verde.");
    }
}
