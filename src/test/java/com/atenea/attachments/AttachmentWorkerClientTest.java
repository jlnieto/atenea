package com.atenea.attachments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AttachmentWorkerClientTest {

    private static final String TEST_TOKEN = "synthetic-test-token-".repeat(3);

    @TempDir
    Path temporaryDirectory;

    private HttpServer server;
    private AttachmentProperties properties;
    private AttachmentWorkerClient client;

    @BeforeEach
    void setUp() throws IOException {
        Path tokenFile = temporaryDirectory.resolve("worker.token");
        Files.writeString(tokenFile, TEST_TOKEN, StandardCharsets.UTF_8);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        properties = new AttachmentProperties();
        properties.setEndpoint("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setTokenFile(tokenFile.toString());
        properties.setConnectTimeout(Duration.ofSeconds(1));
        properties.setRequestTimeout(Duration.ofSeconds(1));
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        client = new AttachmentWorkerClient(properties, objectMapper);
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void sendsPrivateAuthenticationAndAcceptsOnlyCompatibleHealthShape() {
        server.createContext("/v1/health", exchange -> {
            assertEquals("Bearer " + TEST_TOKEN, exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, 200, """
                    {
                      "protocolVersion":"worksession-attachment/v1",
                      "workerId":"ax42-01",
                      "healthy":true,
                      "maxFileBytes":16777216,
                      "maxSessionBytes":268435456,
                      "contentTypes":["image/png"],
                      "serverTime":"2026-07-28T23:00:00Z"
                    }
                    """);
        });
        server.start();

        AttachmentWorkerClient.Health health = client.health();

        assertTrue(health.healthy());
        assertEquals("ax42-01", health.workerId());
        assertEquals(AttachmentProperties.DEFAULT_MAX_FILE_BYTES, health.maxFileBytes());
    }

    @Test
    void convertsWorkerRejectionToSanitizedActionableFailure() {
        server.createContext("/v1/health", exchange -> respond(
                exchange,
                415,
                "{\"error\":\"unsupported_content_type\",\"detail\":\"private internal path\"}"));
        server.start();

        AttachmentWorkerException failure =
                assertThrows(AttachmentWorkerException.class, client::health);

        assertEquals(415, failure.getStatusCode());
        assertEquals("unsupported_content_type", failure.getCode());
        assertTrue(failure.getMessage().contains("formato"));
        assertTrue(!failure.getMessage().contains("private internal path"));
    }

    @Test
    void missingCredentialFailsBeforeNetworkAccess() throws IOException {
        properties.setTokenFile(temporaryDirectory.resolve("missing.token").toString());
        client = new AttachmentWorkerClient(
                properties,
                new ObjectMapper().registerModule(new JavaTimeModule()));
        server.start();

        AttachmentWorkerException failure =
                assertThrows(AttachmentWorkerException.class, client::health);

        assertEquals("attachment_worker_unavailable", failure.getCode());
        assertTrue(failure.getMessage().contains("credencial privada"));
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
