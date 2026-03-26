package com.atenea.service.project;

public class ProjectRepoPathOutsideWorkspaceException extends RuntimeException {

    public ProjectRepoPathOutsideWorkspaceException(String repoPath, String workspaceRoot) {
        super("Project repoPath '" + repoPath + "' must be inside workspaceRoot '" + workspaceRoot + "'");
    }
}
