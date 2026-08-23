package com.atenea.v2.control;

import java.util.Map;

public final class V2CapabilityPolicy {

    private final boolean globalEnabled;
    private final Map<String, Long> exactProjectRevisions;

    public V2CapabilityPolicy(boolean globalEnabled, Map<String, Long> exactProjectRevisions) {
        this.globalEnabled = globalEnabled;
        this.exactProjectRevisions = Map.copyOf(exactProjectRevisions);
    }

    public boolean allows(String projectIdentity, long policyRevision) {
        if (!globalEnabled || projectIdentity == null || projectIdentity.isBlank()) {
            return false;
        }
        Long enabledRevision = exactProjectRevisions.get(projectIdentity);
        return enabledRevision != null && enabledRevision == policyRevision;
    }
}
