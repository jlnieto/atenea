package com.atenea.service.project;

public class DuplicateProjectNameException extends RuntimeException {

    public DuplicateProjectNameException(String name) {
        super("Project with name '" + name + "' already exists");
    }
}
