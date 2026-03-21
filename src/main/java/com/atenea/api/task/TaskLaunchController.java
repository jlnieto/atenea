package com.atenea.api.task;

import com.atenea.api.taskexecution.TaskExecutionResponse;
import com.atenea.service.taskexecution.TaskExecutionService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tasks/{taskId}/launch")
public class TaskLaunchController {

    private final TaskExecutionService taskExecutionService;

    public TaskLaunchController(TaskExecutionService taskExecutionService) {
        this.taskExecutionService = taskExecutionService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TaskExecutionResponse launchTask(@PathVariable Long taskId) {
        return taskExecutionService.launchTask(taskId);
    }
}
