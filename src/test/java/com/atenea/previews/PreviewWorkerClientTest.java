package com.atenea.previews;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PreviewWorkerClientTest {

    private static final String TOKEN = "synthetic-preview-token-".repeat(3);
    private static final UUID PREVIEW =
            UUID.fromString("61000000-0000-4000-8000-000000000001");

    @TempDir
    Path temporaryDirectory;

    private HttpServer server;
    private PreviewProperties properties;
    private PreviewWorkerClient client;

    @BeforeEach
    void setUp() throws IOException {
        Path token = temporaryDirectory.resolve("preview.token");
        Files.writeString(token, TOKEN);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        properties = new PreviewProperties();
        properties.setEndpoint("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setTokenFile(token.toString());
        properties.setConnectTimeout(Duration.ofSeconds(1));
        properties.setRequestTimeout(Duration.ofSeconds(1));
        client = new PreviewWorkerClient(
                properties,
                new ObjectMapper().registerModule(new JavaTimeModule()));
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void authenticatedActivationReturnsOnlyPrivateProjectionContract() {
        server.createContext("/v1/previews/" + PREVIEW + "/activate", exchange -> {
            assertEquals("Bearer " + TOKEN, exchange.getRequestHeaders().getFirst("Authorization"));
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            assertFalse(request.contains("upstreamPort"));
            respond(exchange, 201, projection());
        });
        server.start();

        PreviewWorkerClient.Projection result = client.activate(
                ownership(),
                UUID.fromString("61000000-0000-4000-8000-000000000002"));

        assertEquals("READY", result.state());
        assertEquals("http://100.81.98.93:19000/ready", result.privateUrl());
        assertFalse(result.tunnel().credentialIncluded());
        assertFalse(result.tunnel().runtimePortExposed());
    }

    @Test
    void workerOwnershipRejectionIsSanitized() {
        server.createContext("/v1/health", exchange -> respond(
                exchange,
                409,
                "{\"error\":\"allocation_ownership_conflict\",\"message\":\"/private/path\"}"));
        server.start();

        PreviewWorkerException failure = assertThrows(
                PreviewWorkerException.class, client::health);

        assertEquals(409, failure.getStatusCode());
        assertEquals("allocation_ownership_conflict", failure.getCode());
        assertFalse(failure.getMessage().contains("/private/path"));
    }

    @Test
    void absentCredentialFailsBeforeAnyMutation() throws IOException {
        properties.setTokenFile(temporaryDirectory.resolve("absent").toString());
        client = new PreviewWorkerClient(
                properties,
                new ObjectMapper().registerModule(new JavaTimeModule()));
        server.start();

        PreviewWorkerException failure = assertThrows(
                PreviewWorkerException.class, client::health);

        assertEquals("preview_worker_unavailable", failure.getCode());
    }

    private PreviewWorkerClient.Ownership ownership() {
        return new PreviewWorkerClient.Ownership(
                PREVIEW,
                "12",
                "synthetic-preview",
                "ax42-01",
                "ws-61000000000040008000000000000002",
                "a".repeat(64),
                1);
    }

    private String projection() {
        return """
                {
                  "protocolVersion":"session-preview/v1",
                  "previewId":"61000000-0000-4000-8000-000000000001",
                  "workSessionId":"12",
                  "projectId":"synthetic-preview",
                  "workerId":"ax42-01",
                  "allocationIdentity":"ws-61000000000040008000000000000002",
                  "allocationFingerprint":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                  "lifecycleRevision":2,
                  "state":"READY",
                  "privateUrl":"http://100.81.98.93:19000/ready",
                  "leaseExpiresAt":"2026-07-29T02:00:00Z",
                  "hardExpiresAt":"2026-07-29T09:55:00Z",
                  "localhostCompatible":true,
                  "tunnel":{
                    "sshDestination":"codex-worker",
                    "remoteHost":"100.81.98.93",
                    "remotePort":19000,
                    "path":"/ready",
                    "credentialIncluded":false,
                    "runtimePortExposed":false
                  },
                  "syntheticFixture":true
                }
                """;
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
