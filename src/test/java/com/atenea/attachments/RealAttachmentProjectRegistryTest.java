package com.atenea.attachments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.atenea.remoteworker.ProjectCodexIdentity;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RealAttachmentProjectRegistryTest {

    @Test
    void defaultsClosedWhileRegisteringOnlyCanonicalAtenea() {
        RealAttachmentProjectRegistry registry = registry(Set.of());

        assertTrue(registry.isRegistered(ProjectCodexIdentity.PROJECT_IDENTITY));
        assertFalse(registry.isRegistered("beautips"));
        assertFalse(registry.isEnabled(ProjectCodexIdentity.PROJECT_IDENTITY));
        assertEquals(Set.of(), registry.enabledProjects());
    }

    @Test
    void enablesOnlyExactCanonicalAteneaIdentity() {
        RealAttachmentProjectRegistry registry = registry(Set.of("atenea"));

        assertTrue(registry.isEnabled("atenea"));
        assertEquals(
                new RealAttachmentProjectRegistry.CanonicalProject(
                        "atenea",
                        "ax42-01",
                        "atenea-real-attachments-v1"),
                registry.requireRegistered("atenea"));
        assertThrows(
                UnsupportedOperationException.class,
                () -> registry.enabledProjects().add("beautips"));
    }

    @Test
    void rejectsBeautipsAtStartup() {
        assertThrows(IllegalStateException.class, () -> registry(Set.of("beautips")));
    }

    @Test
    void rejectsDisplayNameAtStartup() {
        assertThrows(IllegalStateException.class, () -> registry(Set.of("Atenea")));
    }

    @Test
    void rejectsMixedKnownAndUnknownConfigurationAtStartup() {
        assertThrows(
                IllegalStateException.class,
                () -> registry(Set.of("atenea", "foreign-project")));
    }

    @Test
    void unknownLookupFailsClosed() {
        RealAttachmentProjectRegistry registry = registry(Set.of("atenea"));

        assertThrows(IllegalArgumentException.class, () -> registry.requireRegistered("beautips"));
    }

    private RealAttachmentProjectRegistry registry(Set<String> configured) {
        AttachmentProperties properties = new AttachmentProperties();
        properties.setRealProjectAllowlist(configured);
        return new RealAttachmentProjectRegistry(properties);
    }
}
