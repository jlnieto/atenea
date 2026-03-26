package com.atenea.service.worksession;

public class OpenWorkSessionAlreadyExistsException extends RuntimeException {

    public OpenWorkSessionAlreadyExistsException(Long projectId) {
        super("Project with id '" + projectId + "' already has an open WorkSession");
    }
}
