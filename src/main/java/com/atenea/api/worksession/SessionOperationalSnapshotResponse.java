package com.atenea.api.worksession;

public record SessionOperationalSnapshotResponse(
        boolean repoValid,
        boolean workingTreeClean,
        String currentBranch,
        boolean runInProgress
) {
}
