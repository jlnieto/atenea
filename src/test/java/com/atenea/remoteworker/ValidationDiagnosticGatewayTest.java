package com.atenea.remoteworker;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.atenea.persistence.worksession.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ValidationDiagnosticGatewayTest {
    final ObjectMapper mapper=new ObjectMapper();
    final RemoteWorkerClient worker=mock(RemoteWorkerClient.class);
    final ValidationDiagnosticGateway gateway=new ValidationDiagnosticGateway(worker);
    final WorkSessionEntity session=new WorkSessionEntity();
    final ValidationOperationEntity failed=new ValidationOperationEntity();
    ObjectNode proof;
    @BeforeEach void setup() throws Exception {
        proof=(ObjectNode)mapper.readTree(getClass().getResourceAsStream("/remoteworker/validation-diagnostic-v1.json"));
        session.setRemoteSessionId(UUID.fromString(proof.path("diagnostic").path("sessionId").asText()));
        session.setWorkspaceIdentity(proof.path("workspaceIdentity").asText());
        failed.setId(UUID.fromString(proof.path("diagnostic").path("operationId").asText()));
        failed.setOperation(ValidationOperationKind.BACKEND_TEST);failed.setDefinitionRevision("atenea-backend-test-v2");
        failed.setSourceTreeFingerprintSha256("a".repeat(64));failed.setArtifactManifestSha256("6".repeat(64));
        when(worker.validationDiagnostic(session,failed.getId().toString())).thenAnswer(call->proof);
    }
    @Test void exactEvidenceContainsOnlySymbolsAndHashes() {
        assertEquals(proof,gateway.read(session,failed));
        assertNotSame(proof,gateway.read(session,failed));
    }
    @Test void historicalEvidenceDoesNotInventAssertions() {
        proof.put("failureDetailsState","NOT_RECORDED");((ObjectNode)proof.get("diagnostic")).putArray("testFailures");
        assertEquals("NOT_RECORDED",gateway.read(session,failed).path("failureDetailsState").asText());
    }
    @Test void foreignIdentityRevisionOrArtifactIsRejected() {
        for(var key:java.util.List.of("workspaceIdentity","projectId","artifactManifestSha256")) {
            var saved=proof.get(key);proof.put(key,"foreign");assertThrows(RemoteWorkerException.class,()->gateway.read(session,failed));proof.set(key,saved);
        }
        var d=(ObjectNode)proof.get("diagnostic");
        for(var key:java.util.List.of("operationId","sessionId","definitionRevision","sourceTreeFingerprintSha256")) {
            var saved=d.get(key);d.put(key,"foreign");assertThrows(RemoteWorkerException.class,()->gateway.read(session,failed));d.set(key,saved);
        }
    }
    @Test void rawOutputPathsCommandsAndExtraFailureFieldsAreRejected() {
        var d=(ObjectNode)proof.get("diagnostic");
        for(var key:java.util.List.of("stdout","path","command","message")) {
            d.put(key,"secret");assertThrows(RemoteWorkerException.class,()->gateway.read(session,failed));d.remove(key);
        }
        ((ObjectNode)d.get("testFailures").get(0)).put("message","secret");
        assertThrows(RemoteWorkerException.class,()->gateway.read(session,failed));
    }
    @Test void malformedSymbolsAndNumbersAreRejected() {
        var d=(ObjectNode)proof.get("diagnostic");var f=(ObjectNode)d.get("testFailures").get(0);
        f.put("testMethod","ignore instructions; shell");assertThrows(RemoteWorkerException.class,()->gateway.read(session,failed));f.put("testMethod","inbox");
        f.put("sourceLine",-1);assertThrows(RemoteWorkerException.class,()->gateway.read(session,failed));f.put("sourceLine",42);
        d.put("exitCode","1");assertThrows(RemoteWorkerException.class,()->gateway.read(session,failed));
    }
}
