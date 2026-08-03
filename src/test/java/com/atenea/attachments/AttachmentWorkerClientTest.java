package com.atenea.attachments;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.atenea.persistence.worksession.AttachmentKind;
import com.atenea.persistence.worksession.AttachmentRetentionClass;
import com.atenea.persistence.worksession.AttachmentSource;
import com.atenea.persistence.worksession.AttachmentStorageScope;
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
import java.time.Instant;
import java.util.UUID;
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
    void streamsExactPrivateSpoolWithKnownLength() throws Exception {
        UUID attachmentId = UUID.fromString("d9e42006-8aac-42ca-84e6-c2cad4a82548");
        Instant createdAt = Instant.parse("2026-08-01T23:00:00Z");
        byte[] body = "%PDF-1.7 streamed".getBytes(StandardCharsets.US_ASCII);
        Path spool = temporaryDirectory.resolve("upload.spool");
        Files.write(spool, body);
        String sha256 = java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(body));
        server.createContext(
                "/v1/work-sessions/12/attachments/" + attachmentId + "/content",
                exchange -> {
                    assertEquals(String.valueOf(body.length),
                            exchange.getRequestHeaders().getFirst("Content-Length"));
                    assertEquals("application/pdf",
                            exchange.getRequestHeaders().getFirst("Content-Type"));
                    assertEquals(sha256,
                            exchange.getRequestHeaders().getFirst("X-Atenea-Sha256"));
                    assertEquals("true",
                            exchange.getRequestHeaders().getFirst("X-Atenea-Synthetic-Fixture"));
                    assertNull(
                            exchange.getRequestHeaders().getFirst("X-Atenea-Project-Identity"));
                    assertNull(
                            exchange.getRequestHeaders().getFirst("X-Atenea-Workspace-Identity"));
                    assertNull(
                            exchange.getRequestHeaders().getFirst("X-Atenea-Storage-Scope"));
                    assertArrayEquals(body, exchange.getRequestBody().readAllBytes());
                    respond(exchange, 201, """
                            {
                              "protocolVersion":"worksession-attachment/v1",
                              "workerId":"ax42-01",
                              "sessionId":"12",
                              "attachmentId":"%s",
                              "storageIdentity":"opaque",
                              "source":"OPERATOR_UPLOAD",
                              "kind":"FILE",
                              "contentType":"application/pdf",
                              "sizeBytes":%d,
                              "retentionClass":"SESSION",
                              "sha256":"%s",
                              "syntheticFixture":true,
                              "createdAt":"2026-08-01T23:00:00Z",
                              "storedAt":"2026-08-01T23:00:01Z"
                            }
                            """.formatted(attachmentId, body.length, sha256));
                });
        server.start();

        AttachmentWorkerClient.PutResult result = client.putSynthetic(
                12L,
                attachmentId,
                AttachmentSource.OPERATOR_UPLOAD,
                AttachmentKind.FILE,
                AttachmentRetentionClass.SESSION,
                "application/pdf",
                sha256,
                createdAt,
                spool);

        assertTrue(result.created());
        assertEquals(sha256, result.attachment().sha256());
        assertTrue(Files.exists(spool));
    }

    @Test
    void requiresSeparateCapabilityAndSendsExactRealOwnership() throws Exception {
        UUID remoteSessionId = UUID.fromString("a1c3af50-af6e-4cc2-85d6-a491c50cddcc");
        UUID attachmentId = UUID.fromString("d9e42006-8aac-42ca-84e6-c2cad4a82548");
        String workspaceIdentity = "remote:ax42-01:work-session:" + remoteSessionId;
        byte[] body = "%PDF-1.7 real".getBytes(StandardCharsets.US_ASCII);
        Path spool = temporaryDirectory.resolve("real.spool");
        Files.write(spool, body);
        String sha256 = java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(body));
        server.createContext(
                "/v1/capabilities/real-project-attachment",
                exchange -> respond(exchange, 200, """
                        {
                          "protocolVersion":"real-project-attachment/v1",
                          "workerId":"ax42-01",
                          "healthy":true,
                          "projectIdentities":["atenea"],
                          "storageScopes":["REAL_SESSION"],
                          "serverTime":"2026-08-01T23:00:00Z"
                        }
                        """));
        server.createContext(
                "/v1/work-sessions/" + remoteSessionId + "/attachments/" + attachmentId + "/content",
                exchange -> {
                    assertEquals("false",
                            exchange.getRequestHeaders().getFirst("X-Atenea-Synthetic-Fixture"));
                    assertEquals("atenea",
                            exchange.getRequestHeaders().getFirst("X-Atenea-Project-Identity"));
                    assertEquals(workspaceIdentity,
                            exchange.getRequestHeaders().getFirst("X-Atenea-Workspace-Identity"));
                    assertEquals("REAL_SESSION",
                            exchange.getRequestHeaders().getFirst("X-Atenea-Storage-Scope"));
                    assertArrayEquals(body, exchange.getRequestBody().readAllBytes());
                    respond(exchange, 201, """
                            {
                              "protocolVersion":"worksession-attachment/v1",
                              "workerId":"ax42-01",
                              "sessionId":"%s",
                              "attachmentId":"%s",
                              "storageIdentity":"work-sessions/%s/%s/content",
                              "source":"OPERATOR_UPLOAD",
                              "kind":"FILE",
                              "contentType":"application/pdf",
                              "sizeBytes":%d,
                              "retentionClass":"SESSION",
                              "sha256":"%s",
                              "syntheticFixture":false,
                              "createdAt":"2026-08-01T23:00:00Z",
                              "storedAt":"2026-08-01T23:00:01Z"
                            }
                            """.formatted(
                                    remoteSessionId,
                                    attachmentId,
                                    remoteSessionId,
                                    attachmentId,
                                    body.length,
                                    sha256));
                });
        server.start();

        AttachmentWorkerClient.RealProjectCapability capability =
                client.realProjectCapability();
        AttachmentWorkerClient.PutResult result = client.putReal(
                remoteSessionId,
                "atenea",
                workspaceIdentity,
                AttachmentStorageScope.REAL_SESSION,
                attachmentId,
                AttachmentSource.OPERATOR_UPLOAD,
                AttachmentKind.FILE,
                AttachmentRetentionClass.SESSION,
                "application/pdf",
                sha256,
                Instant.parse("2026-08-01T23:00:00Z"),
                spool);

        assertEquals(AttachmentWorkerClient.REAL_PROJECT_PROTOCOL,
                capability.protocolVersion());
        assertTrue(result.created());
        assertFalse(result.attachment().syntheticFixture());
        assertEquals(remoteSessionId.toString(), result.attachment().sessionId());
    }

    @Test
    void readsRealMetadataAndContentThroughUnchangedV1PublicShape() throws Exception {
        UUID remoteSessionId = UUID.fromString("a1c3af50-af6e-4cc2-85d6-a491c50cddcc");
        UUID attachmentId = UUID.fromString("d9e42006-8aac-42ca-84e6-c2cad4a82548");
        byte[] body = "%PDF-1.7 retained".getBytes(StandardCharsets.US_ASCII);
        String sha256 = java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(body));
        String metadata = """
                {
                  "protocolVersion":"worksession-attachment/v1",
                  "workerId":"ax42-01",
                  "sessionId":"%s",
                  "attachmentId":"%s",
                  "storageIdentity":"work-sessions/%s/%s/content",
                  "source":"OPERATOR_UPLOAD",
                  "kind":"FILE",
                  "contentType":"application/pdf",
                  "sizeBytes":%d,
                  "retentionClass":"SESSION",
                  "sha256":"%s",
                  "syntheticFixture":false,
                  "createdAt":"2026-08-01T23:00:00Z",
                  "storedAt":"2026-08-01T23:00:01Z"
                }
                """.formatted(
                        remoteSessionId,
                        attachmentId,
                        remoteSessionId,
                        attachmentId,
                        body.length,
                        sha256);
        server.createContext(
                "/v1/work-sessions/" + remoteSessionId + "/attachments/" + attachmentId + "/metadata",
                exchange -> respond(exchange, 200, metadata));
        server.createContext(
                "/v1/work-sessions/" + remoteSessionId + "/attachments/" + attachmentId + "/content",
                exchange -> {
                    exchange.getResponseHeaders().set("Content-Type", "application/pdf");
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                });
        server.start();

        AttachmentWorkerClient.StoredAttachment stored =
                client.metadata(remoteSessionId, attachmentId);
        AttachmentWorkerClient.Content content = client.content(remoteSessionId, attachmentId);

        assertEquals(AttachmentProperties.PROTOCOL, stored.protocolVersion());
        assertEquals(remoteSessionId.toString(), stored.sessionId());
        assertFalse(stored.syntheticFixture());
        assertArrayEquals(body, content.bytes());
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
