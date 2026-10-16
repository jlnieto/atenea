package com.atenea.remoteworker;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ValidationRecoveryGatewayTest {
    final RemoteWorkerClient worker=mock(RemoteWorkerClient.class);
    final ObjectMapper mapper=new ObjectMapper();
    final ValidationRecoveryGateway gateway=new ValidationRecoveryGateway(worker,mapper);
    final UUID change=UUID.randomUUID(),operation=UUID.randomUUID();
    final ValidationRecoveryCommand command=new ValidationRecoveryCommand(new DevelopmentChangeBranchPublicationCommand(
        operation,operation,change,1,"atenea",ProjectCodexIdentity.REPOSITORY,"main","1".repeat(40),"1".repeat(40),
        "atenea/change-"+change,"remote:ax42-01:change:"+change,"ax42-01",1,"2".repeat(64)),UUID.randomUUID());
    private void result(java.util.function.Consumer<ObjectNode> mutate) {
        when(worker.validationRecovery(anyMap(),any())).thenAnswer(call->{
            // Real HTTP JSON parsing uses IntNode for small integral values,
            // whereas Java owner fields are long. Identity is numeric, not a node implementation class.
            ObjectNode value=(ObjectNode)mapper.readTree(mapper.writeValueAsBytes(call.getArgument(0)));
            value.put("state","READY").put("recipeId","atenea-explicit-android-version-v1")
                .put("receiptSha256","3".repeat(64)).putNull("resultFingerprintSha256").put("valuesExposed",false);
            mutate.accept(value);return value;
        });
    }
    @Test void exactNumericAndOwnershipEchoIsAcceptedWithoutClientSelectors() {
        result(v->{});assertEquals("READY",gateway.call(command,ValidationRecoveryCommand.Action.PREPARE).state());
        verify(worker).validationRecovery(argThat(body->body.size()==20 && body.get("operation").equals("PREPARE")
            && body.get("effect").equals("PREPARE_EXACT_REPAIR") && body.get("requestFingerprintSha256").toString().matches("[0-9a-f]{64}")),eq(ValidationRecoveryCommand.Action.PREPARE));
    }
    @Test void foreignIdentityUnexpectedFieldAndMalformedHashFailClosed() {
        for(var mutation:java.util.List.<java.util.function.Consumer<ObjectNode>>of(
            v->v.put("changeKey",UUID.randomUUID().toString()),v->v.put("sourceRevision",2),v->v.put("databaseProjectId",1.0),
            v->v.put("path","/foreign"),v->v.put("valuesExposed",true),v->v.put("receiptSha256","invalid"),
            v->v.put("resultFingerprintSha256",123),v->v.put("receiptSha256",false),
            v->v.put("recipeId","caller-patch"))) {
            result(mutation);assertThrows(RemoteWorkerException.class,()->gateway.call(command,ValidationRecoveryCommand.Action.PREPARE));
        }
    }
    @Test void applyRequiresTerminalProofAndResultFingerprint() {
        result(v->{});assertThrows(RemoteWorkerException.class,()->gateway.call(command,ValidationRecoveryCommand.Action.APPLY));
        result(v->v.put("state","APPLIED"));assertThrows(RemoteWorkerException.class,()->gateway.call(command,ValidationRecoveryCommand.Action.APPLY));
        result(v->v.put("state","APPLIED").put("resultFingerprintSha256","4".repeat(64)));
        assertEquals("APPLIED",gateway.call(command,ValidationRecoveryCommand.Action.APPLY).state());
    }
    @Test void emptyOrNonObjectEvidenceFailsAsAProtocolErrorNotANullPointer() {
        when(worker.validationRecovery(anyMap(),any())).thenReturn(null);
        assertThrows(RemoteWorkerException.class,()->gateway.call(command,ValidationRecoveryCommand.Action.PREPARE));
        when(worker.validationRecovery(anyMap(),any())).thenReturn(mapper.createArrayNode());
        assertThrows(RemoteWorkerException.class,()->gateway.call(command,ValidationRecoveryCommand.Action.PREPARE));
    }
}
