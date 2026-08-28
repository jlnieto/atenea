package com.atenea.remoteworker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DevelopmentChangeBranchPublicationWorkerClientTest {

    @TempDir Path temporary;

    @Test
    void exchangesOnlyExactServerOwnedPublicationIdentity() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AtomicReference<JsonNode> requestBody = new AtomicReference<>();
        AtomicReference<String> idempotencyHeader = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/development-changes/branches/publish", exchange -> {
            JsonNode request = mapper.readTree(exchange.getRequestBody());
            requestBody.set(request);
            idempotencyHeader.set(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
            ObjectNode response = mapper.createObjectNode();
            for (String field : new String[]{
                    "schemaVersion", "protocolVersion", "effect", "operationId",
                    "idempotencyKey", "operation", "changeKey", "databaseProjectId",
                    "projectId", "repositoryBranch", "baseCommit",
                    "sourceCommit", "workspaceBranch", "workspaceIdentity",
                    "workerId", "sourceRevision", "requestFingerprintSha256"}) {
                response.set(field, request.get(field));
            }
            response.put("state", "PUBLISHED");
            response.set("sourceFingerprintSha256",
                    request.get("sourceFingerprintSha256"));
            response.put("publishedHeadSha", "3".repeat(40));
            response.put("remoteDisposition", "CREATED");
            response.put("publicationReceiptSha256", "d".repeat(64));
            response.put("valuesExposed", false);
            byte[] bytes = mapper.writeValueAsBytes(response);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            Path token = temporary.resolve("worker.token");
            Files.writeString(token, "t".repeat(64));
            RemoteWorkerProperties properties = new RemoteWorkerProperties();
            properties.setEndpoint("http://127.0.0.1:" + server.getAddress().getPort());
            properties.setTokenFile(token.toString());
            UUID changeKey = UUID.fromString("8bf60472-3c0e-49aa-99bf-6dc3c7e60eaf");
            UUID operationId = UUID.fromString("17f120f6-79e2-49e4-bd13-23db520d1374");
            UUID idempotencyKey = UUID.fromString("61552669-4b46-431c-811d-344293ab3c67");
            var command = new DevelopmentChangeBranchPublicationCommand(
                    operationId,
                    idempotencyKey,
                    changeKey,
                    7L,
                    "atenea",
                    "https://github.com/jlnieto/atenea.git",
                    "main",
                    "1".repeat(40),
                    "2".repeat(40),
                    "atenea/change-" + changeKey,
                    "remote:ax42-01:change:" + changeKey,
                    "ax42-01",
                    3L,
                    "a".repeat(64));

            DevelopmentChangeBranchPublication result =
                    new DevelopmentChangeBranchPublicationWorkerClient(properties, mapper)
                            .publish(command);

            assertEquals("3".repeat(40), result.publishedHeadSha());
            assertEquals(idempotencyKey.toString(), idempotencyHeader.get());
            assertEquals(command.workspaceBranch(),
                    requestBody.get().get("workspaceBranch").asText());
            assertFalse(requestBody.get().has("workspacePath"));
            assertFalse(requestBody.get().has("remote"));
            assertFalse(requestBody.get().has("credentials"));
        } finally {
            server.stop(0);
        }
    }
}
