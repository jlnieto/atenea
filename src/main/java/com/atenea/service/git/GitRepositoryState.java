package com.atenea.service.git;

public record GitRepositoryState(
        String currentBranch,
        boolean workingTreeClean,
        boolean baseBranchUpToDate,
        boolean taskBranchExists
) {
}
