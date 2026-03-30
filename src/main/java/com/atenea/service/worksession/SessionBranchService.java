package com.atenea.service.worksession;

import com.atenea.persistence.worksession.WorkSessionEntity;
import com.atenea.service.git.GitRepositoryService;
import com.atenea.service.git.GitRepositoryOperationException;
import org.springframework.stereotype.Service;

@Service
public class SessionBranchService {

    private final GitRepositoryService gitRepositoryService;

    public SessionBranchService(GitRepositoryService gitRepositoryService) {
        this.gitRepositoryService = gitRepositoryService;
    }

    public String prepareWorkspaceBranch(WorkSessionEntity session, String repoPath) {
        String workspaceBranch = resolveWorkspaceBranch(session);

        try {
            String currentBranch = gitRepositoryService.getCurrentBranch(repoPath);
            if (workspaceBranch.equals(currentBranch)) {
                return workspaceBranch;
            }

            if (!gitRepositoryService.isWorkingTreeClean(repoPath)) {
                throw new WorkSessionOperationBlockedException(
                        "Repository '%s' is not clean; cannot prepare WorkSession '%s'"
                                .formatted(repoPath, session.getId()));
            }

            if (!session.getBaseBranch().equals(currentBranch)) {
                throw new WorkSessionOperationBlockedException(
                        "Repository is on branch '%s' but WorkSession '%s' can only prepare workspace branch '%s' from base branch '%s' or from the workspace branch itself. Switch branches manually and retry."
                                .formatted(currentBranch, session.getId(), workspaceBranch, session.getBaseBranch()));
            }

            if (gitRepositoryService.branchExists(repoPath, workspaceBranch)) {
                gitRepositoryService.checkoutBranch(repoPath, workspaceBranch);
            } else {
                gitRepositoryService.createAndCheckoutBranch(repoPath, session.getBaseBranch(), workspaceBranch);
            }

            return workspaceBranch;
        } catch (GitRepositoryOperationException exception) {
            throw new WorkSessionOperationBlockedException(
                    "Project repository is not operational for WorkSession branch preparation: "
                            + exception.getMessage());
        }
    }

    String resolveWorkspaceBranch(WorkSessionEntity session) {
        if (session.getWorkspaceBranch() != null && !session.getWorkspaceBranch().isBlank()) {
            return session.getWorkspaceBranch();
        }
        if (session.getId() == null) {
            throw new IllegalArgumentException("WorkSession id is required to derive workspaceBranch");
        }
        return "atenea/session-" + session.getId();
    }
}
