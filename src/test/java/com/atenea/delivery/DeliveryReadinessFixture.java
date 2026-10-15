package com.atenea.delivery;

import java.time.Instant;
import java.util.List;

final class DeliveryReadinessFixture {
    static ChangeDeliveryReadiness backend(MobileDeliveryService.DeploymentObservation b, String head) {
        if (b==null) return null;
        return new ChangeDeliveryReadiness(b.sessionId(),"CONFIRMED",head,b.integratedMergeCommit()==null ? "4".repeat(40) : b.integratedMergeCommit(),
                List.of(DeliveryTarget.APP_PROD),"DEPLOYED".equals(b.status()) && b.healthy(),b,null,"UNAVAILABLE",b.errorCode(),Instant.now().getEpochSecond());
    }
}
