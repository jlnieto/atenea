package com.atenea.service.taskexecution;

public class TaskExecutionNotFoundException extends RuntimeException {

    public TaskExecutionNotFoundException(Long taskId, Long executionId) {
        super("Task execution with id '" + executionId + "' was not found for task '" + taskId + "'");
    }
}
