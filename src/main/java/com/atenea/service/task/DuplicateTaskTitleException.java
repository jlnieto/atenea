package com.atenea.service.task;

public class DuplicateTaskTitleException extends RuntimeException {

    public DuplicateTaskTitleException(Long projectId, String title) {
        super("Task with title '" + title + "' already exists in project '" + projectId + "'");
    }
}
