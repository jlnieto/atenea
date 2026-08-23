package com.atenea.remoteworker;

import com.atenea.developmentchange.DevelopmentChangeIdentity;
import com.atenea.persistence.developmentchange.DevelopmentChangeWorkspaceOperationKind;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public record DevelopmentChangeWorkspaceCommand(
        UUID operationId,
        UUID idempotencyKey,
        DevelopmentChangeWorkspaceOperationKind operationKind,
        UUID predecessorOperationId,
        UUID changeKey,
        long databaseProjectId,
        String projectIdentity,
        String repository,
        String repositoryBranch,
        String baseCommit,
        String expectedCanonicalCommit,
        String workspaceBranch,
        String workspaceIdentity,
        String workerId,
        long sourceRevision,
        String sourceFingerprintSha256
) {

    public static final int SCHEMA_VERSION = 1;
    public static final String PROTOCOL_VERSION = "development-change-workspace/v1";
    public static final String CREATE_EFFECT = "CREATE_IF_ABSENT_EXACT";
    public static final String OBSERVE_EFFECT = "OBSERVE_ONLY";
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern GIT_COMMIT = Pattern.compile("(?:[0-9a-f]{40}|[0-9a-f]{64})");

    public DevelopmentChangeWorkspaceCommand {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(operationKind, "operationKind");
        Objects.requireNonNull(changeKey, "changeKey");
        if ((operationKind == DevelopmentChangeWorkspaceOperationKind.RECONCILE)
                != (predecessorOperationId != null)) {
            throw new IllegalArgumentException("Only reconciliation has a predecessor");
        }
        if (databaseProjectId <= 0
                || !ProjectCodexIdentity.PROJECT_IDENTITY.equals(projectIdentity)
                || !ProjectCodexIdentity.REPOSITORY.equals(repository)
                || !ProjectCodexIdentity.BRANCH.equals(repositoryBranch)
                || !ProjectCodexIdentity.WORKER_ID.equals(workerId)
                || !GIT_COMMIT.matcher(Objects.toString(baseCommit, "")).matches()
                || !GIT_COMMIT.matcher(Objects.toString(expectedCanonicalCommit, "")).matches()
                || sourceRevision < 0
                || !SHA256.matcher(Objects.toString(sourceFingerprintSha256, "")).matches()) {
            throw new IllegalArgumentException("Development change worker identity is invalid");
        }
        new DevelopmentChangeIdentity(
                changeKey, databaseProjectId, workerId, workspaceBranch, workspaceIdentity);
    }

    public String effect() {
        return operationKind == DevelopmentChangeWorkspaceOperationKind.PROVISION
                ? CREATE_EFFECT
                : OBSERVE_EFFECT;
    }
}
