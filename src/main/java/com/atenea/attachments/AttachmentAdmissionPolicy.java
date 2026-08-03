package com.atenea.attachments;

import org.springframework.stereotype.Component;

@Component
public final class AttachmentAdmissionPolicy {

    private final AttachmentProperties properties;
    private final RealAttachmentProjectRegistry realProjectRegistry;

    public AttachmentAdmissionPolicy(
            AttachmentProperties properties,
            RealAttachmentProjectRegistry realProjectRegistry
    ) {
        this.properties = properties;
        this.realProjectRegistry = realProjectRegistry;
    }

    public boolean isGlobalCreateBindEnabled() {
        return properties.isEnabled();
    }

    public boolean isSyntheticCreationEnabled(String projectDisplayName) {
        return isGlobalCreateBindEnabled()
                && properties.getSyntheticProjectAllowlist().contains(projectDisplayName);
    }

    public boolean isRealCreateBindEnabled(String canonicalProjectIdentity) {
        return isGlobalCreateBindEnabled()
                && realProjectRegistry.isRegistered(canonicalProjectIdentity)
                && realProjectRegistry.isEnabled(canonicalProjectIdentity);
    }

    public void requireSyntheticCreationAllowed(String projectDisplayName) {
        requireGlobalCreateBindEnabled();
        if (!properties.getSyntheticProjectAllowlist().contains(projectDisplayName)) {
            throw new AttachmentFeatureDisabledException(
                    "Este proyecto no está autorizado para adjuntos sintéticos.");
        }
    }

    public void requireRealCreateBindAllowed(String canonicalProjectIdentity) {
        requireGlobalCreateBindEnabled();
        if (!realProjectRegistry.isRegistered(canonicalProjectIdentity)
                || !realProjectRegistry.isEnabled(canonicalProjectIdentity)) {
            throw new AttachmentFeatureDisabledException(
                    "Este proyecto no está autorizado para adjuntos reales.");
        }
    }

    private void requireGlobalCreateBindEnabled() {
        if (!isGlobalCreateBindEnabled()) {
            throw new AttachmentFeatureDisabledException(
                    "Los adjuntos nuevos están desactivados; la evidencia ya retenida sigue disponible.");
        }
    }
}
