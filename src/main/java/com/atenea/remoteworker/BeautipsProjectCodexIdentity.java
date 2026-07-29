package com.atenea.remoteworker;

import com.atenea.persistence.project.ProjectEntity;
import com.atenea.persistence.worksession.WorkSessionEntity;

public final class BeautipsProjectCodexIdentity {

    public static final String PROJECT_NAME = "Beautips";
    public static final String PROJECT_IDENTITY = "beautips";
    public static final String REPOSITORY = "https://github.com/jlnieto/beautips.git";
    public static final String REPO_PATH = "/workspace/repos/internal/beautips";
    public static final String BRANCH = "main";
    public static final String COMMIT = "e9e0b3c319c518363d4135f5378ebbddced96dfb";
    public static final String MANIFEST_SHA256 =
            "365f1c66c51c9018c2c6f48deddbaa619b4588cae2dd463dcd916cde884e2e82";

    private BeautipsProjectCodexIdentity() {
    }

    public static boolean matchesNewSession(WorkSessionEntity session) {
        if (session == null || session.getProject() == null) {
            return false;
        }
        ProjectEntity project = session.getProject();
        return PROJECT_NAME.equals(project.getName())
                && REPO_PATH.equals(project.getRepoPath())
                && BRANCH.equals(project.getDefaultBaseBranch())
                && BRANCH.equals(session.getBaseBranch());
    }
}
