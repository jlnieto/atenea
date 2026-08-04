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
        assertTrue(properties.getRemoteCloseProjectAllowlist().isEmpty());
        assertFalse(properties.isRemoteCloseReleaseEnabledFor("atenea"));
        assertFalse(properties.isRemoteCloseReconciliationEnabledFor("atenea"));
        assertFalse(properties.isRemoteCloseReleaseEnabledFor("beautips"));
        assertFalse(properties.isRemoteCloseReconciliationEnabledFor("beautips"));
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
}
