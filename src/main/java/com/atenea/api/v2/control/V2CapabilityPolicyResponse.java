package com.atenea.api.v2.control;

public record V2CapabilityPolicyResponse(
        String capability,
        String projectIdentity,
        boolean globalEnabled,
        boolean projectEnabled,
        long projectPolicyRevision,
        boolean allowed) {

    public V2CapabilityPolicyResponse {
        if (capability == null || capability.isBlank()) {
            throw new IllegalArgumentException("Capability is required");
        }
        if (projectIdentity == null || projectIdentity.isBlank()) {
            throw new IllegalArgumentException("Project identity is required");
        }
        if (projectPolicyRevision < 0) {
            throw new IllegalArgumentException("Project policy revision must be non-negative");
        }
        if (allowed != (globalEnabled && projectEnabled && projectPolicyRevision > 0)) {
            throw new IllegalArgumentException("Allowed must be derived from both enabled gates");
        }
    }

    public static V2CapabilityPolicyResponse disabled(String capability, String projectIdentity) {
        return new V2CapabilityPolicyResponse(
                capability,
                projectIdentity,
                false,
                false,
                0,
                false);
    }
}
