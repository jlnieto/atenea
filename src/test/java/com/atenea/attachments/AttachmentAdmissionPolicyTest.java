package com.atenea.attachments;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

class AttachmentAdmissionPolicyTest {

    @Test
    void defaultsCreateAndBindOffForBothAdmissionClasses() {
        AttachmentProperties properties = new AttachmentProperties();
        AttachmentAdmissionPolicy policy = policy(properties);

        assertFalse(policy.isGlobalCreateBindEnabled());
        assertFalse(policy.isSyntheticCreationEnabled("synthetic-project"));
        assertFalse(policy.isRealCreateBindEnabled("atenea"));
        assertThrows(
                AttachmentFeatureDisabledException.class,
                () -> policy.requireSyntheticCreationAllowed("synthetic-project"));
        assertThrows(
                AttachmentFeatureDisabledException.class,
                () -> policy.requireRealCreateBindAllowed("atenea"));
    }

    @Test
    void syntheticAndRealAllowlistsStayIndependent() {
        AttachmentProperties properties = new AttachmentProperties();
        properties.setEnabled(true);
        properties.setSyntheticProjectAllowlist(Set.of("synthetic-project"));
        properties.setRealProjectAllowlist(Set.of("atenea"));
        AttachmentAdmissionPolicy policy = policy(properties);

        assertTrue(policy.isSyntheticCreationEnabled("synthetic-project"));
        assertFalse(policy.isSyntheticCreationEnabled("Atenea"));
        assertTrue(policy.isRealCreateBindEnabled("atenea"));
        assertFalse(policy.isRealCreateBindEnabled("synthetic-project"));
        policy.requireSyntheticCreationAllowed("synthetic-project");
        policy.requireRealCreateBindAllowed("atenea");
    }

    @Test
    void realAteneaConfigurationDoesNotEnableLegacySyntheticUpload() {
        AttachmentProperties properties = new AttachmentProperties();
        properties.setEnabled(true);
        properties.setRealProjectAllowlist(Set.of("atenea"));
        AttachmentAdmissionPolicy policy = policy(properties);

        assertTrue(policy.isRealCreateBindEnabled("atenea"));
        assertFalse(policy.isSyntheticCreationEnabled("Atenea"));
        assertThrows(
                AttachmentFeatureDisabledException.class,
                () -> policy.requireSyntheticCreationAllowed("Atenea"));
    }

    @Test
    void globalDisableOverridesBothConfiguredAllowlists() {
        AttachmentProperties properties = new AttachmentProperties();
        properties.setEnabled(false);
        properties.setSyntheticProjectAllowlist(Set.of("synthetic-project"));
        properties.setRealProjectAllowlist(Set.of("atenea"));
        AttachmentAdmissionPolicy policy = policy(properties);

        assertFalse(policy.isSyntheticCreationEnabled("synthetic-project"));
        assertFalse(policy.isRealCreateBindEnabled("atenea"));
    }

    @Test
    void foreignRuntimeIdentityFailsClosedWithoutBecomingRealAuthority() {
        AttachmentProperties properties = new AttachmentProperties();
        properties.setEnabled(true);
        properties.setRealProjectAllowlist(Set.of("atenea"));
        AttachmentAdmissionPolicy policy = policy(properties);

        assertFalse(policy.isRealCreateBindEnabled("beautips"));
        assertThrows(
                AttachmentFeatureDisabledException.class,
                () -> policy.requireRealCreateBindAllowed("beautips"));
    }

    private AttachmentAdmissionPolicy policy(AttachmentProperties properties) {
        return new AttachmentAdmissionPolicy(
                properties,
                new RealAttachmentProjectRegistry(properties));
    }
}
