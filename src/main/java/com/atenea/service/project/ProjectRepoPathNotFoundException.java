package com.atenea.service.project;

public class ProjectRepoPathNotFoundException extends RuntimeException {

    public ProjectRepoPathNotFoundException(String repoPath) {
        super("Project repoPath '" + repoPath + "' does not exist");
    }
}
