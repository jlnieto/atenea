package com.atenea.remoteworker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.atenea.persistence.developmentchange.DevelopmentChangeWorkspaceOperationKind;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DevelopmentChangeWorkspaceWorkerClientTest {

    private static final UUID CHANGE_KEY =
            UUID.fromString("8bf60472-3c0e-49aa-99bf-6dc3c7e60eaf");
    private static final UUID OPERATION_ID =
            UUID.fromString("17f120f6-79e2-49e4-bd13-23db520d1374");
    private static final UUID IDEMPOTENCY_KEY =
            UUID.fromString("61552669-4b46-431c-811d-344293ab3c67");
    private static final String BASE_COMMIT = "1".repeat(40);
    private static final String SOURCE_FINGERPRINT = "a".repeat(64);

    @TempDir Path temporaryDirectory;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicReference<JsonNode> received = new AtomicReference<>();
    private HttpServer server;
    private RemoteWorkerProperties properties;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        Path token = temporaryDirectory.resolve("worker.token");
        Files.writeString(token, "synthetic-worker-token-value-0000000000000000\n");
        properties = new RemoteWorkerProperties();
        properties.setEndpoint("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setTokenFile(token.toString());
        properties.setConnectTimeout(Duration.ofSeconds(1));
        properties.setWorkspaceProvisionTimeout(Duration.ofSeconds(2));
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void sendsOnlyTheFixedServerOwnedContractAndAcceptsExactOwnedObservation() {
        server.createContext("/v1/development-changes/workspaces/provision",
                exchange -> respond(exchange, ResponseMode.OWNED));
        DevelopmentChangeWorkspaceWorkerClient client =
                new DevelopmentChangeWorkspaceWorkerClient(properties, objectMapper);

        DevelopmentChangeWorkspaceObservation observation = client.execute(command());

        assertEquals(DevelopmentChangeWorkspaceObservation.Disposition.OWNED,
                observation.disposition());
        assertEquals(BASE_COMMIT, observation.canonicalCommit());
        JsonNode request = received.get();
        assertEquals(1, request.path("schemaVersion").asInt());
        assertEquals("development-change-workspace/v1",
                request.path("protocolVersion").asText());
        assertEquals("CREATE_IF_ABSENT_EXACT", request.path("effect").asText());
        assertEquals(ProjectCodexIdentity.PROJECT_IDENTITY,
                request.path("projectId").asText());
        assertEquals(ProjectCodexIdentity.REPOSITORY,
                request.path("repository").asText());
        assertEquals("atenea/change-" + CHANGE_KEY,
                request.path("workspaceBranch").asText());
        assertFalse(request.has("workspacePath"));
        assertFalse(request.has("slot"));
        assertFalse(request.has("remoteSessionId"));
        assertEquals(Set.of(
                        "schemaVersion", "protocolVersion", "effect", "operationId",
                        "idempotencyKey", "operation", "predecessorOperationId",
                        "changeKey", "databaseProjectId", "projectId", "repository",
                        "repositoryBranch", "baseCommit", "expectedCanonicalCommit",
                        "workspaceBranch", "workspaceIdentity", "workerId",
                        "sourceRevision", "sourceFingerprintSha256",
                        "requestFingerprintSha256"),
                iterableSet(request.fieldNames()));
    }

    @Test
    void inspectAndReconcileUseObserveOnlyWithAnExactPredecessor() {
        server.createContext("/v1/development-changes/workspaces/inspect",
                exchange -> respond(exchange, ResponseMode.OWNED));
        server.createContext("/v1/development-changes/workspaces/reconcile",
                exchange -> respond(exchange, ResponseMode.OWNED));
        DevelopmentChangeWorkspaceWorkerClient client =
                new DevelopmentChangeWorkspaceWorkerClient(properties, objectMapper);

        client.execute(command(
                DevelopmentChangeWorkspaceOperationKind.INSPECT, null));
        assertEquals("OBSERVE_ONLY", received.get().path("effect").asText());
        assertTrue(received.get().path("predecessorOperationId").isNull());

        UUID predecessor = UUID.fromString("48166062-d262-4a3a-b3a0-a2a01830aa5a");
        client.execute(command(
                DevelopmentChangeWorkspaceOperationKind.RECONCILE, predecessor));
        assertEquals("OBSERVE_ONLY", received.get().path("effect").asText());
        assertEquals(predecessor.toString(),
                received.get().path("predecessorOperationId").asText());
    }

    @Test
    void exactIdentityMismatchFailsAsOwnershipInsteadOfBeingAdopted() {
        server.createContext("/v1/development-changes/workspaces/provision",
                exchange -> respond(exchange, ResponseMode.WRONG_IDENTITY));
        DevelopmentChangeWorkspaceWorkerClient client =
                new DevelopmentChangeWorkspaceWorkerClient(properties, objectMapper);

        RemoteWorkerException failure = assertThrows(
                RemoteWorkerException.class,
                () -> client.execute(command()));

        assertEquals(RemoteWorkerFailureCategory.OWNERSHIP, failure.getCategory());
        assertEquals("DEVELOPMENT_CHANGE_WORKER_OWNERSHIP_MISMATCH",
                failure.getFailureCode());
        assertFalse(failure.isRetryable());
    }

    @Test
    void unexpectedResponseFieldFailsAsProtocolNotTransport() {
        objectMapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        server.createContext("/v1/development-changes/workspaces/provision",
                exchange -> respond(exchange, ResponseMode.EXTRA_FIELD));
        DevelopmentChangeWorkspaceWorkerClient client =
                new DevelopmentChangeWorkspaceWorkerClient(properties, objectMapper);

        RemoteWorkerException failure = assertThrows(
                RemoteWorkerException.class,
                () -> client.execute(command()));

        assertEquals(RemoteWorkerFailureCategory.PROTOCOL, failure.getCategory());
        assertEquals("DEVELOPMENT_CHANGE_WORKER_PROTOCOL_FAILURE",
                failure.getFailureCode());
        assertFalse(failure.isCompatibleTransportFailure());
    }

    @Test
    void duplicateResponseFieldFailsAsProtocolNotTransport() {
        server.createContext("/v1/development-changes/workspaces/provision",
                exchange -> respond(exchange, ResponseMode.DUPLICATE_FIELD));
        DevelopmentChangeWorkspaceWorkerClient client =
                new DevelopmentChangeWorkspaceWorkerClient(properties, objectMapper);

        RemoteWorkerException failure = assertThrows(
                RemoteWorkerException.class,
                () -> client.execute(command()));

        assertEquals(RemoteWorkerFailureCategory.PROTOCOL, failure.getCategory());
        assertEquals("DEVELOPMENT_CHANGE_WORKER_PROTOCOL_FAILURE",
                failure.getFailureCode());
        assertFalse(failure.isCompatibleTransportFailure());
    }

    @Test
    void invalidServerOwnedCommandFailsBeforeNetwork() {
        assertThrows(IllegalArgumentException.class, () ->
                new DevelopmentChangeWorkspaceCommand(
                        OPERATION_ID,
                        IDEMPOTENCY_KEY,
                        DevelopmentChangeWorkspaceOperationKind.PROVISION,
                        null,
                        CHANGE_KEY,
                        7L,
                        ProjectCodexIdentity.PROJECT_IDENTITY,
                        ProjectCodexIdentity.REPOSITORY,
                        ProjectCodexIdentity.BRANCH,
                        BASE_COMMIT,
                        BASE_COMMIT,
                        "client/chosen",
                        "remote:" + ProjectCodexIdentity.WORKER_ID + ":change:" + CHANGE_KEY,
                        ProjectCodexIdentity.WORKER_ID,
                        0,
                        SOURCE_FINGERPRINT));
    }

    private DevelopmentChangeWorkspaceCommand command() {
        return command(DevelopmentChangeWorkspaceOperationKind.PROVISION, null);
    }

    private DevelopmentChangeWorkspaceCommand command(
            DevelopmentChangeWorkspaceOperationKind kind,
            UUID predecessorOperationId) {
        return new DevelopmentChangeWorkspaceCommand(
                OPERATION_ID,
                IDEMPOTENCY_KEY,
                kind,
                predecessorOperationId,
                CHANGE_KEY,
                7L,
                ProjectCodexIdentity.PROJECT_IDENTITY,
                ProjectCodexIdentity.REPOSITORY,
                ProjectCodexIdentity.BRANCH,
                BASE_COMMIT,
                BASE_COMMIT,
                "atenea/change-" + CHANGE_KEY,
                "remote:" + ProjectCodexIdentity.WORKER_ID + ":change:" + CHANGE_KEY,
                ProjectCodexIdentity.WORKER_ID,
                0,
                SOURCE_FINGERPRINT);
    }

    private void respond(HttpExchange exchange, ResponseMode mode) throws IOException {
        try (exchange) {
            assertEquals("POST", exchange.getRequestMethod());
            assertEquals(IDEMPOTENCY_KEY.toString(),
                    exchange.getRequestHeaders().getFirst("Idempotency-Key"));
            JsonNode request = objectMapper.readTree(exchange.getRequestBody());
            received.set(request);
            ObjectNode response = objectMapper.createObjectNode();
            copy(request, response,
                    "schemaVersion", "protocolVersion", "effect", "operationId",
                    "idempotencyKey", "operation", "predecessorOperationId",
                    "changeKey", "databaseProjectId", "projectId", "repository",
                    "repositoryBranch", "baseCommit", "expectedCanonicalCommit",
                    "workspaceBranch", "workspaceIdentity", "workerId",
                    "sourceRevision", "requestFingerprintSha256");
            response.set("expectedSourceFingerprintSha256",
                    request.get("sourceFingerprintSha256"));
            response.put("state", "OWNED");
            response.put("canonicalCommit", BASE_COMMIT);
            response.put("sourceFingerprintSha256", SOURCE_FINGERPRINT);
            response.put("workspaceDirty", false);
            response.put("retainedDraft", false);
            response.put("ownershipFingerprintSha256", "b".repeat(64));
            response.put("valuesExposed", false);
            if (mode == ResponseMode.WRONG_IDENTITY) {
                response.put("workspaceIdentity", "remote:foreign:change:" + CHANGE_KEY);
            } else if (mode == ResponseMode.EXTRA_FIELD) {
                response.put("workspacePath", "/not/allowed");
            }
            byte[] encoded = objectMapper.writeValueAsBytes(response);
            if (mode == ResponseMode.DUPLICATE_FIELD) {
                String duplicate = new String(encoded, StandardCharsets.UTF_8)
                        .replace("\"state\":\"OWNED\"",
                                "\"state\":\"OWNED\",\"state\":\"OWNED\"");
                encoded = duplicate.getBytes(StandardCharsets.UTF_8);
            }
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, encoded.length);
            exchange.getResponseBody().write(encoded);
        }
    }

    private void copy(JsonNode source, ObjectNode target, String... fields) {
        for (String field : fields) {
            target.set(field, source.get(field));
        }
    }

    private Set<String> iterableSet(java.util.Iterator<String> values) {
        java.util.Set<String> result = new java.util.HashSet<>();
        values.forEachRemaining(result::add);
        return Set.copyOf(result);
    }

    private enum ResponseMode {
        OWNED,
        WRONG_IDENTITY,
        EXTRA_FIELD,
        DUPLICATE_FIELD
    }
}
