package com.atenea.api.taskexecution;

import com.atenea.service.taskexecution.TaskExecutionService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
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

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TaskExecutionResponse createExecution(
            @PathVariable Long taskId,
            @Valid @RequestBody CreateTaskExecutionRequest request
    ) {
        return taskExecutionService.createExecution(taskId, request);
    }
}
