package com.atenea.remoteworker;

import com.atenea.developmentchange.DevelopmentChangeIdentity;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public record DevelopmentChangeBranchPublicationCommand(
        UUID operationId,
        UUID idempotencyKey,
        UUID changeKey,
        long databaseProjectId,
        String projectIdentity,
        String repository,
        String repositoryBranch,
        String baseCommit,
        String sourceCommit,
        String workspaceBranch,
        String workspaceIdentity,
        String workerId,
        long sourceRevision,
        String sourceFingerprintSha256
) {
    public static final int SCHEMA_VERSION = 1;
    public static final String PROTOCOL_VERSION =
            "development-change-branch-publication/v1";
    public static final String EFFECT = "PUBLISH_EXACT_CHANGE_BRANCH";
    public static final String OPERATION = "PUBLISH";
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern GIT_COMMIT = Pattern.compile("(?:[0-9a-f]{40}|[0-9a-f]{64})");

    public DevelopmentChangeBranchPublicationCommand {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(changeKey, "changeKey");
        if (databaseProjectId <= 0
                || !ProjectCodexIdentity.PROJECT_IDENTITY.equals(projectIdentity)
                || !ProjectCodexIdentity.REPOSITORY.equals(repository)
                || !ProjectCodexIdentity.BRANCH.equals(repositoryBranch)
                || !ProjectCodexIdentity.WORKER_ID.equals(workerId)
                || !GIT_COMMIT.matcher(Objects.toString(baseCommit, "")).matches()
                || !GIT_COMMIT.matcher(Objects.toString(sourceCommit, "")).matches()
                || sourceRevision < 0
                || (sourceFingerprintSha256 != null
                    && !SHA256.matcher(sourceFingerprintSha256).matches())) {
            throw new IllegalArgumentException("Development change publication identity is invalid");
        }
        new DevelopmentChangeIdentity(
                changeKey, databaseProjectId, workerId, workspaceBranch, workspaceIdentity);
    }
}
