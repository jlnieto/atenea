package com.atenea.remoteworker;

import com.atenea.persistence.project.ProjectEntity;
import com.atenea.persistence.worksession.AgentRunEntity;
import com.atenea.persistence.worksession.ExecutionTarget;
import com.atenea.persistence.worksession.WorkSessionEntity;
import java.util.UUID;

public final class BeautipsProjectCodexIdentity {

    public static final String PROJECT_NAME = "Beautips";
    public static final String PROJECT_IDENTITY = "beautips";
    public static final String REPOSITORY = "https://github.com/jlnieto/beautips.git";
    public static final String REPO_PATH = "/workspace/repos/internal/beautips";
    public static final String BRANCH = "main";
    public static final String WORKER_ID = "ax42-01";
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

    public static boolean matchesPinnedSession(WorkSessionEntity session) {
        return matchesNewSession(session)
                && session.getExecutionTarget() == ExecutionTarget.REMOTE
                && WORKER_ID.equals(session.getSelectedWorkerId())
                && session.getRemoteSessionId() != null
                && ProjectCodexIdentity.WORKLOAD_KIND.equals(session.getRemoteWorkloadKind())
                && expectedWorkspaceIdentity(session.getRemoteSessionId())
                        .equals(session.getWorkspaceIdentity());
    }

    public static boolean matches(AgentRunEntity run) {
        return run != null
                && ProjectCodexIdentity.WORKLOAD_KIND.equals(run.getWorkloadKind())
                && WORKER_ID.equals(run.getSelectedWorkerId())
                && run.getRemoteSessionId() != null
                && expectedWorkspaceIdentity(run.getRemoteSessionId()).equals(run.getWorkspaceIdentity())
                && PROJECT_IDENTITY.equals(run.getProjectIdentity())
                && REPOSITORY.equals(run.getRepositoryUrl())
                && BRANCH.equals(run.getRepositoryBranch())
                && COMMIT.equals(run.getRepositoryCommit())
                && MANIFEST_SHA256.equals(run.getManifestSha256())
                && ReviewedInstructionBundleIdentity.matches(run);
    }

    private static String expectedWorkspaceIdentity(UUID remoteSessionId) {
        return "remote:" + WORKER_ID + ":work-session:" + remoteSessionId;
    }
}
