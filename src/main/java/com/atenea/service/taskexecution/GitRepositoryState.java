package com.atenea.service.taskexecution;

public record GitRepositoryState(
        String currentBranch,
        boolean workingTreeClean,
        boolean baseBranchUpToDate,
        boolean taskBranchExists
) {
}
