package com.atenea.remoteworker;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DevelopmentChangeSourceUpdateWorkerClientTest {
    final ObjectMapper mapper = new ObjectMapper();
    final RemoteWorkerProperties properties = new RemoteWorkerProperties();
    final DevelopmentChangeSourceUpdateWorkerClient client = new DevelopmentChangeSourceUpdateWorkerClient(properties, mapper);
    final UUID change = UUID.randomUUID();
    final DevelopmentChangeSourceUpdateCommand command = new DevelopmentChangeSourceUpdateCommand(
            new DevelopmentChangeBranchPublicationCommand(UUID.randomUUID(), UUID.randomUUID(), change, 7,
                    "atenea", "https://github.com/jlnieto/atenea.git", "main", "1".repeat(40), "2".repeat(40),
                    "atenea/change-"+change, "remote:ax42-01:change:"+change, "ax42-01", 3, null),
            "3".repeat(40), "4".repeat(64));

    ObjectNode response(DevelopmentChangeSourceUpdateCommand.Action action) {
        ObjectNode node = mapper.valueToTree(client.request(command, action));
        node.put("state", "NEEDS_RESOLUTION").put("preparedTreeSha", "5".repeat(40))
                .put("preparedFingerprintSha256", "6".repeat(64)).put("receiptSha256", "7".repeat(64))
                .put("valuesExposed", false);
        node.putArray("conflictFiles").add("src/test/java/Example.java");
        return node;
    }
    DevelopmentChangeSourceFinalizationCommand finalization() {
        var old=command.owner();
        return new DevelopmentChangeSourceFinalizationCommand(new DevelopmentChangeBranchPublicationCommand(old.operationId(),old.idempotencyKey(),
            old.changeKey(),old.databaseProjectId(),old.projectIdentity(),old.repository(),old.repositoryBranch(),old.baseCommit(),old.sourceCommit(),
            old.workspaceBranch(),old.workspaceIdentity(),old.workerId(),4,"a".repeat(64)),command.targetMainCommit(),command.publicationReceiptSha256(),
            UUID.randomUUID(),"b".repeat(64),"c".repeat(64));
    }
    @Test void continuationIsVersionedClosedAndBindsObservedSourceAndSealedPredecessor() throws Exception {
        var old=command.owner();
        var owner=new DevelopmentChangeBranchPublicationCommand(UUID.randomUUID(),UUID.randomUUID(),old.changeKey(),old.databaseProjectId(),
            old.projectIdentity(),old.repository(),old.repositoryBranch(),old.baseCommit(),old.sourceCommit(),old.workspaceBranch(),
            old.workspaceIdentity(),old.workerId(),5,"a".repeat(64));
        var next=new DevelopmentChangeSourceUpdateCommand(owner,"b".repeat(40),command.publicationReceiptSha256(),old.operationId(),"c".repeat(64),3L);
        var body=client.request(next,DevelopmentChangeSourceUpdateCommand.Action.PREPARE);
        assertEquals(24,body.size());assertEquals("development-change-source-update/v2",body.get("protocolVersion"));
        assertEquals("a".repeat(64),body.get("sourceFingerprintSha256"));assertEquals(3L,body.get("publishedSourceRevision"));
        assertEquals(old.operationId().toString(),body.get("predecessorPreparationOperationId"));
        String hash=(String)body.remove("requestFingerprintSha256");
        assertEquals(hash,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(body))));
        ObjectNode request=mapper.valueToTree(client.request(next,DevelopmentChangeSourceUpdateCommand.Action.INSPECT));
        ObjectNode response=request.deepCopy();response.put("state","ABSENT").putNull("preparedTreeSha").putNull("preparedFingerprintSha256")
            .putNull("receiptSha256").put("valuesExposed",false).putArray("conflictFiles");
        assertEquals(DevelopmentChangeSourceUpdateGateway.State.ABSENT,client.validate(response,request).state());
        assertThrows(RemoteWorkerException.class,()->client.validate(response.deepCopy().put("predecessorPreparationReceiptSha256","d".repeat(64)),request));
        assertThrows(IllegalArgumentException.class,()->new DevelopmentChangeSourceUpdateCommand(owner,"b".repeat(40),command.publicationReceiptSha256()));
    }
    @Test void finalizationContractBindsPreparationValidationAndNewRevision() throws Exception {
        var command=finalization();
        var body=client.finalizationRequest(command,DevelopmentChangeSourceFinalizationCommand.Action.FINALIZE);
        assertEquals(24,body.size()); assertEquals("development-change-source-finalization/v1",body.get("protocolVersion"));
        assertEquals(command.preparationOperationId().toString(),body.get("preparationOperationId"));
        String hash=(String)body.remove("requestFingerprintSha256");
        assertEquals(hash,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(body))));
        var request=mapper.valueToTree(client.finalizationRequest(command,DevelopmentChangeSourceFinalizationCommand.Action.FINALIZE));
        ObjectNode response=request.deepCopy(); response.put("state","PUBLISHED").put("publishedHeadSha","d".repeat(40))
            .put("expectedTreeSha","e".repeat(40)).put("finalizationReceiptSha256","f".repeat(64)).put("valuesExposed",false);
        assertEquals("d".repeat(40),client.validateFinalization(response,request).publishedHeadSha());
        for (String field:java.util.List.of("preparationOperationId","validationProjectionSha256","sourceRevision","valuesExposed","extra")) {
            var invalid=response.deepCopy().put(field,"foreign");
            assertThrows(RemoteWorkerException.class,()->client.validateFinalization(invalid,request));
        }
        assertThrows(RemoteWorkerException.class,()->client.validateFinalization(response.deepCopy().putNull("finalizationReceiptSha256"),request));
    }
    @Test void finalizationFixedEndpointAuthenticatesAndEchoesExactIntent(@TempDir Path temporary) throws Exception {
        Path token=temporary.resolve("synthetic.token"); Files.writeString(token,"x".repeat(40));
        properties.setTokenFile(token.toString());
        var command=finalization();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        properties.setEndpoint("http://127.0.0.1:"+server.getAddress().getPort());
        var calls=new AtomicInteger();
        server.createContext("/v1/development-changes/source-finalizations/inspect",exchange->{
            assertEquals("Bearer "+"x".repeat(40),exchange.getRequestHeaders().getFirst("Authorization"));
            assertEquals(command.owner().idempotencyKey().toString(),exchange.getRequestHeaders().getFirst("Idempotency-Key"));
            ObjectNode body=(ObjectNode)mapper.readTree(exchange.getRequestBody());
            assertEquals("OBSERVE_ONLY",body.path("effect").asText());
            body.put("state","ABSENT").putNull("publishedHeadSha").putNull("expectedTreeSha").putNull("finalizationReceiptSha256").put("valuesExposed",false);
            byte[] reply=mapper.writeValueAsBytes(body); exchange.sendResponseHeaders(200,reply.length);
            exchange.getResponseBody().write(reply);exchange.close();calls.incrementAndGet();
        });
        server.start();
        try {
            assertEquals(DevelopmentChangeSourceFinalizationGateway.FinalizationState.ABSENT,
                client.finalizeSource(command,DevelopmentChangeSourceFinalizationCommand.Action.INSPECT).state());
            assertEquals(1,calls.get());
        } finally { server.stop(0); }
    }
    @Test void fingerprintMatchesCanonicalSortedCompactProtocolAndIntentSurvivesInspection() throws Exception {
        var prepare = client.request(command, DevelopmentChangeSourceUpdateCommand.Action.PREPARE);
        String fingerprint = (String) prepare.remove("requestFingerprintSha256");
        assertEquals(fingerprint, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(mapper.writeValueAsBytes(prepare))));
        assertEquals(20, prepare.size());
        var inspect = client.request(command, DevelopmentChangeSourceUpdateCommand.Action.INSPECT);
        assertEquals(prepare.get("operationId"), inspect.get("operationId"));
        assertEquals(prepare.get("idempotencyKey"), inspect.get("idempotencyKey"));
        assertEquals("OBSERVE_ONLY", inspect.get("effect"));
    }
    @Test void exactResponseCarriesOnlyOwnedRelativeConflictFiles() {
        var action = DevelopmentChangeSourceUpdateCommand.Action.PREPARE;
        var result = client.validate(response(action), mapper.valueToTree(client.request(command, action)));
        assertEquals(DevelopmentChangeSourceUpdateGateway.State.NEEDS_RESOLUTION, result.state());
        assertEquals(java.util.List.of("src/test/java/Example.java"), result.conflictFiles());
    }
    @Test void rejectsCoercionsForeignOwnersUnknownFieldsAndExposedValues() {
        var action = DevelopmentChangeSourceUpdateCommand.Action.PREPARE;
        for (String field : java.util.List.of("databaseProjectId", "sourceRevision", "workerId", "valuesExposed", "extra")) {
            var node = response(action);
            node.put(field, "unexpected");
            assertThrows(RemoteWorkerException.class, () -> client.validate(node, mapper.valueToTree(client.request(command, action))));
        }
    }
    @Test void rejectsEscapesDuplicatesAndImpossiblePreparationStates() {
        var action = DevelopmentChangeSourceUpdateCommand.Action.PREPARE;
        for (String path : java.util.List.of("/secret", "../secret", "src/../secret", "src\\secret", "src\ncommand", "src//x")) {
            var node = response(action); node.putArray("conflictFiles").add(path);
            assertThrows(RemoteWorkerException.class, () -> client.validate(node, mapper.valueToTree(client.request(command, action))));
        }
        var node = response(action); node.putArray("conflictFiles").add("a").add("a");
        assertThrows(RemoteWorkerException.class, () -> client.validate(node, mapper.valueToTree(client.request(command, action))));
        for (String state : java.util.List.of("ABSENT", "PREPARED", "READY_TO_FINALIZE", "NEW_STATE")) {
            var invalid = response(action).put("state", state);
            assertThrows(RemoteWorkerException.class, () -> client.validate(invalid, mapper.valueToTree(client.request(command, action))));
        }
    }
    @Test void fixedAuthenticatedEndpointReusesKeyAndSanitizesReviewedError(@TempDir Path temporary) throws Exception {
        Path token = temporary.resolve("synthetic.token"); Files.writeString(token, "x".repeat(40));
        properties.setTokenFile(token.toString());
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        properties.setEndpoint("http://127.0.0.1:"+server.getAddress().getPort());
        var calls = new AtomicInteger();
        server.createContext("/v1/development-changes/source-updates/prepare", exchange -> {
            assertEquals("Bearer "+"x".repeat(40), exchange.getRequestHeaders().getFirst("Authorization"));
            assertEquals(command.owner().idempotencyKey().toString(), exchange.getRequestHeaders().getFirst("Idempotency-Key"));
            assertEquals(mapper.readTree(mapper.writeValueAsBytes(client.request(command, DevelopmentChangeSourceUpdateCommand.Action.PREPARE))),
                    mapper.readTree(exchange.getRequestBody()));
            calls.incrementAndGet();
            byte[] bytes = "{\"code\":\"SOURCE_UPDATE_REF_MOVED\",\"message\":\"private host path\"}".getBytes();
            exchange.sendResponseHeaders(409, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        try {
            var error = assertThrows(RemoteWorkerException.class, () -> client.exchange(command, DevelopmentChangeSourceUpdateCommand.Action.PREPARE));
            assertEquals("SOURCE_UPDATE_REF_MOVED", error.getFailureCode());
            assertFalse(error.getMessage().contains("private")); assertEquals(1, calls.get());
        } finally { server.stop(0); }
    }
    @Test void partialResponseBodyCannotHangTheDurableReconciler(@TempDir Path temporary) throws Exception {
        Path token=temporary.resolve("synthetic.token"); Files.writeString(token,"x".repeat(40));
        properties.setTokenFile(token.toString()); properties.setWorkspaceProvisionTimeout(java.time.Duration.ofMillis(200));
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        properties.setEndpoint("http://127.0.0.1:"+server.getAddress().getPort());
        var release=new java.util.concurrent.CountDownLatch(1);
        server.createContext("/v1/development-changes/source-updates/prepare", exchange -> {
            exchange.sendResponseHeaders(200,100); exchange.getResponseBody().write("{\"".getBytes()); exchange.getResponseBody().flush();
            try { release.await(3,java.util.concurrent.TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
        try {
            assertTimeoutPreemptively(java.time.Duration.ofSeconds(2),() ->
                assertThrows(RemoteWorkerException.class,() -> client.exchange(command,DevelopmentChangeSourceUpdateCommand.Action.PREPARE)));
        } finally { release.countDown(); server.stop(0); }
    }
}
