package com.atenea.remoteworker;

import com.atenea.persistence.worksession.AgentRunEntity;
import com.atenea.persistence.worksession.WorkSessionEntity;
import com.atenea.persistence.project.ProjectEntity;

public final class ProjectCodexIdentity {

    public static final String WORKLOAD_KIND = "project-codex-v1";
    public static final String IMAGE_WORKLOAD_KIND = "project-codex-v3";
    public static final String PROJECT_NAME = "Atenea";
    public static final String PROJECT_IDENTITY = "atenea";
    public static final String WORKER_ID = "ax42-01";
    public static final String REPOSITORY = "https://github.com/jlnieto/atenea.git";
    public static final String REPO_PATH = "/workspace/repos/internal/atenea";
    public static final String BRANCH = "main";
    public static final String MANIFEST_SHA256 =
            "327a0c521017109d7c0067a11e7d8c3ad2079de4ea78d28296848f9de39c164b";

    private ProjectCodexIdentity() {
    }

    public static boolean matches(ProjectEntity project) {
        return project != null
                && PROJECT_NAME.equals(project.getName())
                && REPO_PATH.equals(project.getRepoPath())
                && BRANCH.equals(project.getDefaultBaseBranch());
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
                && (WORKLOAD_KIND.equals(run.getWorkloadKind())
                    || IMAGE_WORKLOAD_KIND.equals(run.getWorkloadKind()))
                && PROJECT_IDENTITY.equals(run.getProjectIdentity())
                && REPOSITORY.equals(run.getRepositoryUrl())
                && BRANCH.equals(run.getRepositoryBranch())
                && hasCanonicalSourceObservation(run.getSession())
                && run.getRepositoryCommit() != null
                && run.getRepositoryCommit().matches("^[0-9a-f]{40}$")
                && run.getRepositoryCommit().equals(run.getSession().getCanonicalSourceCommit())
                && MANIFEST_SHA256.equals(run.getManifestSha256())
                && ReviewedInstructionBundleIdentity.matches(run);
    }

    public static boolean hasCanonicalSourceObservation(WorkSessionEntity session) {
        return matches(session)
                && session.getCanonicalSourceCommit() != null
                && session.getCanonicalSourceCommit().matches("^[0-9a-f]{40}$")
                && ("refs/heads/" + BRANCH).equals(session.getCanonicalSourceRef())
                && session.getCanonicalSourceObservationSha256() != null
                && session.getCanonicalSourceObservationSha256().matches("^[0-9a-f]{64}$")
                && session.getCanonicalSourceObservedAt() != null;
    }
}
