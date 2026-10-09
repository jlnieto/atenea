package com.atenea.delivery;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReleaseRecoverySourceTest {
    private final ObjectMapper mapper=new ObjectMapper();
    private final UUID integration=UUID.randomUUID();
    private DeliveryOperation plan() {
        var proof=new ReleaseRecoverySource(ReleaseRecoverySource.PROTOCOL,integration,"1".repeat(40),"2".repeat(40),"3".repeat(40));
        var evidence=mapper.createObjectNode(); evidence.set("releaseRecovery",proof.json(mapper));
        return new DeliveryOperation(UUID.randomUUID(),21L,7L,"RELEASE",DeliveryTarget.APP_PROD,"3".repeat(40),
                UUID.randomUUID(),"PLANNING",null,evidence,null,Instant.now(),Instant.now());
    }
    @Test void executorReceiptsCannotDropTheOriginalSourceProof() {
        var op=plan(); var rootReceipt=mapper.createObjectNode().put("state","READY").put("sourceCommit",op.sourceCommit());
        var retained=DeliveryStore.retainRecoverySource(op,rootReceipt);
        assertEquals(op.evidence().path("releaseRecovery"),retained.path("releaseRecovery"));
        assertFalse(rootReceipt.has("releaseRecovery"));
        assertEquals(integration,op.view().releaseRecovery().integrationOperationId());
    }
    @Test void contradictoryOrInjectedAuthorityCannotReplaceThePinnedSource() {
        var op=plan(); var other=mapper.createObjectNode(); other.set("releaseRecovery",op.evidence().path("releaseRecovery").deepCopy());
        ((com.fasterxml.jackson.databind.node.ObjectNode)other.path("releaseRecovery")).put("selectedMainCommit","4".repeat(40));
        assertThrows(DeliveryRejectedException.class,()->DeliveryStore.retainRecoverySource(op,other));
        var legacy=new DeliveryOperation(op.id(),21L,7L,"RELEASE",op.target(),op.sourceCommit(),op.executionId(),"PLANNING",
                null,mapper.createObjectNode(),null,op.createdAt(),op.updatedAt());
        assertThrows(DeliveryRejectedException.class,()->DeliveryStore.retainRecoverySource(legacy,other));
    }
    @Test void invalidShapeProtocolAndMismatchedSourceFailClosed() {
        for (String field:new String[]{"selectedMainCommit","protocol","integrationOperationId"}) {
            var op=plan(); ((com.fasterxml.jackson.databind.node.ObjectNode)op.evidence().path("releaseRecovery")).put(field,"foreign");
            assertThrows(DeliveryRejectedException.class,()->ReleaseRecoverySource.from(op));
        }
        var op=plan(); ((com.fasterxml.jackson.databind.node.ObjectNode)op.evidence().path("releaseRecovery")).put("command","foreign");
        assertThrows(DeliveryRejectedException.class,()->ReleaseRecoverySource.from(op));
    }
}
