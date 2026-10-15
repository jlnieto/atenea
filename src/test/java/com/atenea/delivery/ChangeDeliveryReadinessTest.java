package com.atenea.delivery;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ChangeDeliveryReadinessTest {
    private final ObjectMapper mapper=new ObjectMapper();
    @Test void actualPathsDetermineDestinationsAndDocumentationIsNotADeployment() {
        assertEquals(List.of(DeliveryTarget.ANDROID_STABLE),ChangeDeliveryReadiness.classify(List.of("android/core-console/Screen.kt","docs/ui.md")));
        assertEquals(List.of(DeliveryTarget.APP_PROD),ChangeDeliveryReadiness.classify(List.of("src/main/java/Service.java")));
        assertTrue(ChangeDeliveryReadiness.classify(List.of("docs/guide.md")).isEmpty());
        assertEquals(2,ChangeDeliveryReadiness.classify(List.of(".github/workflows/release-artifacts-v1.yml")).size());
        assertThrows(DeliveryRejectedException.class,() -> ChangeDeliveryReadiness.classify(List.of()));
    }
    private ChangeDeliveryReadiness proof(boolean included) {
        long now=Instant.now().getEpochSecond();
        var apk=new ReleaseControlClient.AndroidObservation("8".repeat(40),"0.6.12",1000012,"a".repeat(64),"b".repeat(64),now,
                UUID.randomUUID(),UUID.randomUUID(),"c".repeat(64));
        return new ChangeDeliveryReadiness(21L,"CONFIRMED","3".repeat(40),"4".repeat(40),List.of(DeliveryTarget.ANDROID_STABLE),
                included,null,apk,included ? "INCLUDED" : "NOT_INCLUDED",null,now);
    }
    @Test void androidOnlyCompletionRequiresPublishedApkNotHealthyBackend() {
        var missing=proof(false);
        assertFalse(ChangeDeliveryReadiness.verifiedForClose(mapper.valueToTree(missing),21L,"3".repeat(40),"4".repeat(40)));
        assertTrue(ChangeDeliveryReadiness.verifiedForClose(mapper.valueToTree(proof(true)),21L,"3".repeat(40),"4".repeat(40)));
    }
    @Test void staleForeignAndIncompleteReceiptsCannotClose() {
        var good=mapper.valueToTree(proof(true));
        assertFalse(ChangeDeliveryReadiness.verifiedForClose(good,22L,"3".repeat(40),"4".repeat(40)));
        assertFalse(ChangeDeliveryReadiness.verifiedForClose(good,21L,"5".repeat(40),"4".repeat(40)));
        var stale=good.deepCopy(); ((com.fasterxml.jackson.databind.node.ObjectNode)stale).put("observedAt",1);
        assertFalse(ChangeDeliveryReadiness.verifiedForClose(stale,21L,"3".repeat(40),"4".repeat(40)));
        var forged=good.deepCopy(); ((com.fasterxml.jackson.databind.node.ObjectNode)forged.path("android")).remove("receiptSha256");
        assertFalse(ChangeDeliveryReadiness.verifiedForClose(forged,21L,"3".repeat(40),"4".repeat(40)));
    }
}
