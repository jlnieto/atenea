package com.atenea.service.taskexecution;

public class TaskRelaunchNotAllowedException extends RuntimeException {

    public TaskRelaunchNotAllowedException(Long taskId, String reason) {
        super("Task '" + taskId + "' cannot be relaunched: " + reason);
    }
}
