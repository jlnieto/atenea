package com.atenea.remoteworker;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

class RemoteCloseCapabilityDefaultsTest {

    @Test
    void releaseAndReconciliationAreDisabledWithAnEmptyAllowlistByDefault() {
        RemoteWorkerProperties properties = new RemoteWorkerProperties();

        assertFalse(properties.isRemoteCloseReleaseEnabled());
        assertFalse(properties.isRemoteCloseReconciliationEnabled());
        assertFalse(properties.isFreshSessionOnSourceAdvanceEnabled());
        assertTrue(properties.getRemoteCloseProjectAllowlist().isEmpty());
        assertTrue(properties.getFreshSessionProjectAllowlist().isEmpty());
        assertFalse(properties.isRemoteCloseReleaseEnabledFor("atenea"));
        assertFalse(properties.isRemoteCloseReconciliationEnabledFor("atenea"));
        assertFalse(properties.isRemoteCloseReleaseEnabledFor("beautips"));
        assertFalse(properties.isRemoteCloseReconciliationEnabledFor("beautips"));
        assertFalse(properties.isFreshSessionOnSourceAdvanceEnabledFor("atenea"));
        assertFalse(properties.isFreshSessionOnSourceAdvanceEnabledFor("beautips"));
    }

    @Test
    void bothTheGlobalGateAndExactServerAllowlistAreRequired() {
        RemoteWorkerProperties properties = new RemoteWorkerProperties();
        properties.setRemoteCloseProjectAllowlist(Set.of("atenea", "beautips"));

        assertFalse(properties.isRemoteCloseReleaseEnabledFor("atenea"));
        assertFalse(properties.isRemoteCloseReconciliationEnabledFor("atenea"));

        properties.setRemoteCloseReleaseEnabled(true);
        properties.setRemoteCloseReconciliationEnabled(true);

        assertTrue(properties.isRemoteCloseReleaseEnabledFor("atenea"));
        assertTrue(properties.isRemoteCloseReconciliationEnabledFor("atenea"));
        assertFalse(properties.isRemoteCloseReleaseEnabledFor("beautips"));
        assertFalse(properties.isRemoteCloseReconciliationEnabledFor("beautips"));
    }

    @Test
    void freshSessionGateRequiresExactCanonicalAteneaAllowlist() {
        RemoteWorkerProperties properties = new RemoteWorkerProperties();
        properties.setFreshSessionProjectAllowlist(Set.of("atenea", "beautips"));

        assertFalse(properties.isFreshSessionOnSourceAdvanceEnabledFor("atenea"));
        properties.setFreshSessionOnSourceAdvanceEnabled(true);

        assertTrue(properties.isFreshSessionOnSourceAdvanceEnabledFor("atenea"));
        assertFalse(properties.isFreshSessionOnSourceAdvanceEnabledFor("beautips"));
    }
}
