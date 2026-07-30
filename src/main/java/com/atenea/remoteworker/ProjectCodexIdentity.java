package com.atenea.remoteworker;

import com.atenea.persistence.worksession.AgentRunEntity;
import com.atenea.persistence.worksession.WorkSessionEntity;

public final class ProjectCodexIdentity {

    public static final String WORKLOAD_KIND = "project-codex-v1";
    public static final String PROJECT_NAME = "Atenea";
    public static final String PROJECT_IDENTITY = "atenea";
    public static final String REPOSITORY = "https://github.com/jlnieto/atenea.git";
    public static final String REPO_PATH = "/workspace/repos/internal/atenea";
    public static final String BRANCH = "feature/actualizar-conversacion-en-web";
    public static final String COMMIT = "d5ea39e7b575b63c6fff3a66a0400c5af5e9ff2b";
    public static final String MANIFEST_SHA256 =
            "3b26e1899a06993bee69ac596e7cb69b6200a37d063d98203ad308058c91bfa3";

    private ProjectCodexIdentity() {
    }

    public static boolean matches(WorkSessionEntity session) {
        return session != null
                && session.getProject() != null
                && PROJECT_NAME.equals(session.getProject().getName())
                && REPO_PATH.equals(session.getProject().getRepoPath())
                && BRANCH.equals(session.getBaseBranch());
    }

    public static boolean matches(AgentRunEntity run) {
        return run != null
                && WORKLOAD_KIND.equals(run.getWorkloadKind())
                && PROJECT_IDENTITY.equals(run.getProjectIdentity())
                && REPOSITORY.equals(run.getRepositoryUrl())
                && BRANCH.equals(run.getRepositoryBranch())
                && COMMIT.equals(run.getRepositoryCommit())
                && MANIFEST_SHA256.equals(run.getManifestSha256());
    }
}
