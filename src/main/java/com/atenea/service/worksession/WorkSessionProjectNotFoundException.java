package com.atenea.service.worksession;

public class WorkSessionProjectNotFoundException extends RuntimeException {

    public WorkSessionProjectNotFoundException(Long projectId) {
        super("Project with id '" + projectId + "' was not found");
    }
}
