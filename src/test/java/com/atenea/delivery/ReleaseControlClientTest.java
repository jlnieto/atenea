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

    private ObjectNode androidObservation() {
        var value=observation().put("protocol","atenea-android-observation/v1").put("target","ANDROID_STABLE")
                .put("versionCode",1000042).put("versionName","0.6.42").put("apkSha256",hash).put("certificateSha256",hash);
        value.remove(java.util.List.of("imageSha256","healthy"));return value;
    }
    private ObjectNode platformObservation() {
        var value=observation().put("protocol","atenea-platform-observation/v1").put("target","AX42_PLATFORM").put("installationSha256",hash);
        value.remove(java.util.List.of("imageSha256","healthy"));return value;
    }
    @Test void androidAndPlatformObservationsAreClosedFreshAndDistinctFromExecutionReceipts() {
        assertEquals(1000042,ReleaseControlClient.verifyAndroidObservation(androidObservation()).versionCode());
        assertEquals(source,ReleaseControlClient.verifyPlatformObservation(platformObservation()).sourceCommit());
        for (var value:java.util.List.of(androidObservation(),platformObservation())) {
            java.util.function.Consumer<ObjectNode> verify=value.path("target").asText().equals("ANDROID_STABLE")
                    ? ReleaseControlClient::verifyAndroidObservation : ReleaseControlClient::verifyPlatformObservation;
            for (String field:java.util.List.of("path","command","token","host")) {
                assertThrows(DeliveryRejectedException.class,() -> verify.accept(value.deepCopy().put(field,"foreign")));
            }
            for (String field:java.util.List.of("sourceCommit","planId","operationId","receiptSha256","protocol")) {
                assertThrows(DeliveryRejectedException.class,() -> verify.accept(value.deepCopy().put(field,"foreign")));
            }
            assertThrows(DeliveryRejectedException.class,() -> verify.accept(value.deepCopy().put("observedAt",1L)));
            assertThrows(DeliveryRejectedException.class,() -> verify.accept(value.deepCopy().put("finishedAt",Long.MAX_VALUE)));
            assertThrows(DeliveryRejectedException.class,() -> ReleaseControlClient.verify(value,id,DeliveryTarget.APP_PROD,source,hash,execution,true));
        }
        assertThrows(DeliveryRejectedException.class,() -> ReleaseControlClient.verifyAndroidObservation(androidObservation().put("versionCode",0)));
        assertThrows(DeliveryRejectedException.class,() -> ReleaseControlClient.verifyAndroidObservation(androidObservation().put("certificateSha256","foreign")));
    }
    @Test void globalVersionObservationsNeverSendASelectableSourceOrHost() {
        var requests=new java.util.ArrayList<java.util.Map<String,String>>();
        var client=new ReleaseControlClient(mapper,true) {
            @Override protected com.fasterxml.jackson.databind.JsonNode exchange(java.util.Map<String,String> request) {
                requests.add(request);return request.get("operation").equals("OBSERVE_ANDROID") ? androidObservation() : platformObservation();
            }
        };
        client.observeAndroid();client.observePlatform();
        assertEquals(java.util.List.of(java.util.Map.of("operation","OBSERVE_ANDROID"),java.util.Map.of("operation","OBSERVE_PLATFORM")),requests);
    }
    private ObjectNode storage() {
        return mapper.createObjectNode().put("protocol","atenea-release-storage/v1").put("planId",id.toString())
                .put("target","APP_PROD").put("sourceCommit",source).put("phase","INSUFFICIENT_SPACE")
                .put("observedAt",java.time.Instant.now().getEpochSecond()).put("freeBytes",5L)
                .put("requiredBytes",20L).put("missingBytes",15L).put("reclaimedBytes",2L);
    }
    @Test void storageIsClosedBoundReadOnlyAndNotAnExecutionReceipt() {
        var requests=new java.util.ArrayList<java.util.Map<String,String>>();
        var client=new ReleaseControlClient(mapper,true) {
            @Override protected com.fasterxml.jackson.databind.JsonNode exchange(java.util.Map<String,String> request) {
                requests.add(request);return storage();
            }
        };
        assertEquals(15L,ReleaseControlClient.verifyStorage(client.observeStorage(id),id,DeliveryTarget.APP_PROD,source).missingBytes());
        assertEquals(java.util.List.of(java.util.Map.of("operation","OBSERVE_STORAGE","planId",id.toString())),requests);
        for(String field:java.util.List.of("planId","target","sourceCommit","phase","protocol")) {
            assertThrows(DeliveryRejectedException.class,()->ReleaseControlClient.verifyStorage(storage().put(field,"foreign"),id,DeliveryTarget.APP_PROD,source));
        }
        for(String field:java.util.List.of("path","command","imageId","token","host")) {
            assertThrows(DeliveryRejectedException.class,()->ReleaseControlClient.verifyStorage(storage().put(field,"foreign"),id,DeliveryTarget.APP_PROD,source));
        }
        for(String field:java.util.List.of("freeBytes","requiredBytes","missingBytes","reclaimedBytes")) {
            assertThrows(DeliveryRejectedException.class,()->ReleaseControlClient.verifyStorage(storage().put(field,-1),id,DeliveryTarget.APP_PROD,source));
            assertThrows(DeliveryRejectedException.class,()->ReleaseControlClient.verifyStorage(storage().put(field,"12"),id,DeliveryTarget.APP_PROD,source));
        }
        assertThrows(DeliveryRejectedException.class,()->ReleaseControlClient.verifyStorage(storage().put("missingBytes",1),id,DeliveryTarget.APP_PROD,source));
        assertThrows(DeliveryRejectedException.class,()->ReleaseControlClient.verify(storage(),id,DeliveryTarget.APP_PROD,source,hash,execution,true));
    }
}
