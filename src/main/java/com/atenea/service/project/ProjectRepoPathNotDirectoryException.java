package com.atenea.service.project;

public class ProjectRepoPathNotDirectoryException extends RuntimeException {

    public ProjectRepoPathNotDirectoryException(String repoPath) {
        super("Project repoPath '" + repoPath + "' is not a directory");
    }
}
