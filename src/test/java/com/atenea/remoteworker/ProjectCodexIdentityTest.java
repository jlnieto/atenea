package com.atenea.remoteworker;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ProjectCodexIdentityTest {

    @Test
    void pinsCanonicalMainAndItsReviewedRuntimeManifest() {
        assertEquals("main", ProjectCodexIdentity.BRANCH);
        assertEquals(
                "327a0c521017109d7c0067a11e7d8c3ad2079de4ea78d28296848f9de39c164b",
                ProjectCodexIdentity.MANIFEST_SHA256);
    }
}
