package com.atenea.developmentchange;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public record DevelopmentChangeIdentity(
        UUID changeKey,
        long projectId,
        String selectedWorkerId,
        String workspaceBranch,
        String workspaceIdentity
) {

    private static final Pattern WORKER_ID =
            Pattern.compile("[a-z0-9](?:[a-z0-9._-]{0,78}[a-z0-9])?");

    public DevelopmentChangeIdentity {
        Objects.requireNonNull(changeKey, "changeKey");
        if (projectId <= 0) {
            throw new IllegalArgumentException("projectId must be positive");
        }
        if (selectedWorkerId == null || !WORKER_ID.matcher(selectedWorkerId).matches()) {
            throw new IllegalArgumentException("selectedWorkerId is invalid");
        }

        String expectedBranch = branchFor(changeKey);
        if (!expectedBranch.equals(workspaceBranch)) {
            throw new IllegalArgumentException("workspaceBranch is not server-derived");
        }
        String expectedWorkspace = workspaceFor(selectedWorkerId, changeKey);
        if (!expectedWorkspace.equals(workspaceIdentity)) {
            throw new IllegalArgumentException("workspaceIdentity is not server-derived");
        }
    }

    public static DevelopmentChangeIdentity create(
            UUID changeKey,
            long projectId,
            String selectedWorkerId
    ) {
        return new DevelopmentChangeIdentity(
                changeKey,
                projectId,
                selectedWorkerId,
                branchFor(changeKey),
                workspaceFor(selectedWorkerId, changeKey));
    }

    private static String branchFor(UUID changeKey) {
        return "atenea/change-" + Objects.requireNonNull(changeKey, "changeKey");
    }

    private static String workspaceFor(String selectedWorkerId, UUID changeKey) {
        return "remote:" + Objects.requireNonNull(selectedWorkerId, "selectedWorkerId")
                + ":change:" + Objects.requireNonNull(changeKey, "changeKey");
    }
}
