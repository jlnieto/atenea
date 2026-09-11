package com.atenea.remoteworker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.atenea.persistence.project.ProjectEntity;

import org.junit.jupiter.api.Test;

class ProjectCodexIdentityTest {

    @Test
    void pinsCanonicalMainAndItsReviewedRuntimeManifest() {
        assertEquals("main", ProjectCodexIdentity.BRANCH);
        assertEquals(
                "327a0c521017109d7c0067a11e7d8c3ad2079de4ea78d28296848f9de39c164b",
                ProjectCodexIdentity.MANIFEST_SHA256);
    }

    @Test
    void acceptsCanonicalAteneaWithItsEffectiveConfiguredRepositoryPath() {
        ProjectEntity project = canonicalProject("/repos/atenea");

        assertTrue(ProjectCodexIdentity.matches(project));
    }

    @Test
    void rejectsProjectRepositoryPairsOutsideTheCanonicalIdentity() {
        ProjectEntity wrongProject = canonicalProject("/repos/atenea");
        wrongProject.setName("Other project");
        ProjectEntity missingRepository = canonicalProject(null);

        assertFalse(ProjectCodexIdentity.matches(wrongProject));
        assertFalse(ProjectCodexIdentity.matches(missingRepository));
    }

    private ProjectEntity canonicalProject(String repoPath) {
        ProjectEntity project = new ProjectEntity();
        project.setName(ProjectCodexIdentity.PROJECT_NAME);
        project.setRepoPath(repoPath);
        project.setDefaultBaseBranch(ProjectCodexIdentity.BRANCH);
        return project;
    }
}
