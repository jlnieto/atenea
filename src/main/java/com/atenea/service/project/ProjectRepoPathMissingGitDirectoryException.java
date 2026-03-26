package com.atenea.service.project;

public class ProjectRepoPathMissingGitDirectoryException extends RuntimeException {

    public ProjectRepoPathMissingGitDirectoryException(String repoPath) {
        super("Project repoPath '" + repoPath + "' does not contain a .git directory");
    }
}
