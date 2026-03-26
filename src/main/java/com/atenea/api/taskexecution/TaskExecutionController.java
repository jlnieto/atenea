package com.atenea.api.taskexecution;

import com.atenea.service.taskexecution.TaskExecutionService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tasks/{taskId}/executions")
public class TaskExecutionController {

    private final TaskExecutionService taskExecutionService;

    public TaskExecutionController(TaskExecutionService taskExecutionService) {
        this.taskExecutionService = taskExecutionService;
    }

    @GetMapping
    public List<TaskExecutionResponse> getExecutions(@PathVariable Long taskId) {
        return taskExecutionService.getExecutions(taskId);
    }

    @GetMapping("/{executionId}")
    public TaskExecutionResponse getExecution(@PathVariable Long taskId, @PathVariable Long executionId) {
        return taskExecutionService.getExecution(taskId, executionId);
    }
}
