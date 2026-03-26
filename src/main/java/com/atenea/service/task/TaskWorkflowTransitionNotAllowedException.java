package com.atenea.service.task;

public class TaskWorkflowTransitionNotAllowedException extends RuntimeException {

    public TaskWorkflowTransitionNotAllowedException(Long taskId, String message) {
        super("Task '" + taskId + "' cannot change workflow state: " + message);
    }
}
