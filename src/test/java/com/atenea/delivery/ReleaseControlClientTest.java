package com.atenea.delivery;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReleaseControlClientTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final UUID id = UUID.randomUUID(), execution = UUID.randomUUID();
    private final String source = "1".repeat(40), hash = "2".repeat(64);
    private ObjectNode valid(String state, boolean accepted) {
        ObjectNode value = mapper.createObjectNode();
        value.put("protocol", ReleaseControlClient.PROTOCOL).put("planId", id.toString())
                .put("target", "APP_PROD").put("sourceCommit", source).put("artifactSha256", hash)
                .put("predecessorCommit", "3".repeat(40)).put("planSha256", hash).put("state", state)
                .put("createdAt", 1L).put("expiresAt", Long.MAX_VALUE);
        value.putNull("errorCode").putNull("finishedAt").putNull("versionCode").putNull("versionName");
        if (DeliveryOperation.TERMINAL.contains(state)) value.put("finishedAt",2L);
        value.put("effectiveSourceCommit",source).putNull("resultSha256");
        if (accepted) value.put("operationId", execution.toString()); else value.putNull("operationId");
        return value;
    }
    @Test void acceptsBoundPreparedPlanAndTerminalReceipt() {
        assertDoesNotThrow(() -> ReleaseControlClient.verify(valid("READY", false), id, DeliveryTarget.APP_PROD, source, null, execution, false));
        assertDoesNotThrow(() -> ReleaseControlClient.verify(valid("SUCCEEDED", true), id, DeliveryTarget.APP_PROD, source, hash, execution, true));
    }
    @Test void rejectsWrongPlanTargetSourceHashAndOperation() {
        for (String field : new String[]{"planId", "target", "sourceCommit", "planSha256", "operationId", "protocol", "state"}) {
            var value = valid("ACCEPTED", true).put(field, "foreign");
            assertThrows(DeliveryRejectedException.class, () -> ReleaseControlClient.verify(value, id,
                    DeliveryTarget.APP_PROD, source, hash, execution, true));
        }
    }
    @Test void rejectsHostPathsSecretsCommandsAndIncompleteReceipts() {
        for (String field : new String[]{"command", "path", "token", "host"}) {
            var value = valid("READY", false).put(field, "forbidden");
            assertThrows(DeliveryRejectedException.class, () -> ReleaseControlClient.verify(value, id,
                    DeliveryTarget.APP_PROD, source, null, execution, false));
        }
        var value = valid("READY", false); value.remove("artifactSha256");
        assertThrows(DeliveryRejectedException.class, () -> ReleaseControlClient.verify(value, id,
                DeliveryTarget.APP_PROD, source, null, execution, false));
    }
    @Test void disabledClientDoesNotContactTheExecutor() {
        var client = new ReleaseControlClient(mapper, false);
        assertEquals("RELEASE_CONTROL_DISABLED", assertThrows(DeliveryRejectedException.class,
                () -> client.plan(id, DeliveryTarget.APP_PROD, source)).code());
    }
    @Test void rejectsIncompleteTerminalReceiptAndRegressionToAnUnacceptedPlan() {
        var success=valid("SUCCEEDED",true); success.putNull("finishedAt");
        assertThrows(DeliveryRejectedException.class,()->ReleaseControlClient.verify(success,id,
                DeliveryTarget.APP_PROD,source,hash,execution,true));
        var prepared=valid("READY",true);
        assertThrows(DeliveryRejectedException.class,()->ReleaseControlClient.verify(prepared,id,
                DeliveryTarget.APP_PROD,source,hash,execution,true));
        var textLeak=valid("ACCEPTED",true).put("errorCode","/host/secret");
        assertThrows(DeliveryRejectedException.class,()->ReleaseControlClient.verify(textLeak,id,
                DeliveryTarget.APP_PROD,source,hash,execution,true));
    }
    @Test void controllerOnlyAcceptsClosedBodies() {
        assertDoesNotThrow(() -> MobileDeliveryController.exact(mapper.createObjectNode(), java.util.Set.of()));
        assertThrows(DeliveryRejectedException.class, () -> MobileDeliveryController.exact(
                mapper.createObjectNode().put("sourceCommit", source), java.util.Set.of()));
    }

    private ObjectNode observation() {
        return mapper.createObjectNode().put("protocol","atenea-app-observation/v1").put("target","APP_PROD")
                .put("state","OBSERVED").put("sourceCommit",source).put("imageSha256",hash).put("healthy",true)
                .put("observedAt",java.time.Instant.now().getEpochSecond()).put("planId",id.toString())
                .put("operationId",execution.toString()).put("receiptSha256",hash).put("finishedAt",1L);
    }
    @Test void appObservationIsFreshClosedAndCannotBeUsedAsAPublicationReceipt() {
        assertEquals(source,ReleaseControlClient.verifyAppObservation(observation()).sourceCommit());
        assertFalse(ReleaseControlClient.verifyAppObservation(observation().put("healthy",false)).healthy());
        for (String field: java.util.List.of("path","command","token","predecessor","version")) {
            assertThrows(DeliveryRejectedException.class,()->ReleaseControlClient.verifyAppObservation(observation().put(field,"foreign")));
        }
        for (String field: java.util.List.of("protocol","target","state","sourceCommit","imageSha256","receiptSha256","planId","operationId")) {
            assertThrows(DeliveryRejectedException.class,()->ReleaseControlClient.verifyAppObservation(observation().put(field,"foreign")));
        }
        assertThrows(DeliveryRejectedException.class,()->ReleaseControlClient.verifyAppObservation(observation().put("healthy","true")));
        assertThrows(DeliveryRejectedException.class,()->ReleaseControlClient.verifyAppObservation(observation().put("observedAt",1L)));
        assertThrows(DeliveryRejectedException.class,()->ReleaseControlClient.verifyAppObservation(observation().put("observedAt",Long.MAX_VALUE)));
        assertThrows(DeliveryRejectedException.class,()->ReleaseControlClient.verify(observation(),id,DeliveryTarget.APP_PROD,source,hash,execution,true));
    }
    @Test void readOnlyObservationRequestContainsNoCallerSelectableAuthority() {
        var client=new ReleaseControlClient(mapper,true) {
            @Override protected com.fasterxml.jackson.databind.JsonNode exchange(java.util.Map<String,String> request) {
                assertEquals(java.util.Map.of("operation","OBSERVE_APP"),request);
                return observation();
            }
        };
        assertEquals("OBSERVED",client.observeApp().path("state").asText());
    }
}
